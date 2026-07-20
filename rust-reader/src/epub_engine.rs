//! EPUB 引擎核心：打开书籍、章节懒加载、图片读取、缓存管理。

use crate::cache::ChapterCache;
use crate::chapter::{
    parse_html_to_blocks,
    BookInfo,
    Block,
    ChapterInfo,
};
use crate::image::ImageCache;
use crate::resource::ResourceAccessor;

/// EPUB 引擎。一个实例对应一本打开的书。
pub struct EpubEngine {
    accessor: ResourceAccessor,
    book_info: BookInfo,
    /// 章节对应的 EPUB 资源 id（与 chapters 索引一一对应）。
    chapter_ids: Vec<String>,
    chapter_cache: ChapterCache,
    image_cache: ImageCache,
}

/// 判断解析后的 Block 列表是否为有效章节（过滤封面、版权、空白页）。
/// 规则：没有图片且文字不超过 30 字的章节视为空白，过滤掉。
fn is_valid_chapter(blocks: &[Block]) -> bool {
    let mut text_len = 0usize;
    let mut image_count = 0usize;
    for block in blocks {
        match block {
            Block::Text(t) | Block::Title(t) => {
                text_len += t.trim().chars().count();
            }
            Block::Image(_) => {
                image_count += 1;
            }
        }
    }
    text_len > 38 || image_count > 0
}

impl EpubEngine {
    /// 打开 EPUB 并构建章节索引。不解析正文，不加载图片。
    pub fn open(path: &str) -> Result<Self, String> {
        let mut accessor = ResourceAccessor::open(path)?;
        let title = accessor.title();
        let author = accessor.author();

        // 构建章节列表：遍历 spine，获取每个条目的路径与标题
        let spine_ids = accessor.spine_ids();
        let resources = accessor.resources_map();

        let mut chapters = Vec::new();
        let mut chapter_ids = Vec::new();
        let mut chapter_index = 0u32;

        for id in spine_ids.iter() {
            let (path, _mime) = match resources.get(id) {
                Some(v) => v.clone(),
                None => continue,
            };

            let path_str = path.to_string_lossy().to_string();

            // 读取章节内容
            let content = match accessor.get_resource_str(id) {
                Some((text, _)) => text,
                None => continue,
            };

            // 解析判断是否为空章节
            let blocks = parse_html_to_blocks(&content, &path_str);

            // 过滤封面、版权、空白页
            if !is_valid_chapter(&blocks) {
                continue;
            }

            chapters.push(ChapterInfo {
                index: chapter_index,
                title: format!("第 {} 章", chapter_index + 1),
                path: path_str,
            });
            chapter_ids.push(id.clone());
            chapter_index += 1;
        }

        // 封面：标记是否存在，实际字节由 get_cover 按需读取
        let cover = String::new();

        let book_info = BookInfo {
            title,
            author,
            cover,
            chapters,
        };

        Ok(Self {
            accessor,
            book_info,
            chapter_ids,
            chapter_cache: ChapterCache::new(),
            image_cache: ImageCache::new(20),
        })
    }

    pub fn book_info(&self) -> &BookInfo {
        &self.book_info
    }

    pub fn chapters(&self) -> &[ChapterInfo] {
        &self.book_info.chapters
    }

    /// 加载章节：读取 xhtml → 解析为 Block 列表 → 缓存。
    /// 命中缓存时直接返回。同时后台预加载下一章节。
    pub fn load_chapter(&mut self, index: u32) -> Result<Vec<Block>, String> {
        // 1. 命中缓存
        if let Some(blocks) = self.chapter_cache.get(index) {
            return Ok(blocks.clone());
        }

        // 2. 从 EPUB 读取 xhtml
        let idx = index as usize;
        if idx >= self.book_info.chapters.len() {
            return Err(format!("chapter index {} out of range", index));
        }
        let chapter_path = self.book_info.chapters[idx].path.clone();
        let chapter_id = self.chapter_ids[idx].clone();

        let (content, _mime) = self
            .accessor
            .get_resource_str(&chapter_id)
            .ok_or_else(|| format!("read chapter {} failed", index))?;

        // 3. 解析为 Block
        let blocks = parse_html_to_blocks(&content, &chapter_path);

        // 4. 写入当前章节缓存
        self.chapter_cache.put_current(index, blocks.clone());

        // 5. 预加载下一章节（非阻塞式：同步预读一次，失败忽略）
        let next_idx = index + 1;

        if (next_idx as usize) < self.book_info.chapters.len()
            && self.chapter_cache.get(next_idx).is_none()
        {
            let next_chapter = &self.book_info.chapters[next_idx as usize];
            let next_id = self.chapter_ids[next_idx as usize].clone();

            if let Some((next_content, _)) = self.accessor.get_resource_str(&next_id) {
                let next_blocks = parse_html_to_blocks(&next_content, &next_chapter.path);
                self.chapter_cache.put_next(next_idx, next_blocks);
            }
        }

        Ok(blocks)
    }

    /// 加载图片：按 EPUB 内路径读取字节，命中 LRU 缓存直接返回。
    pub fn load_image(&mut self, path: &str) -> Result<Vec<u8>, String> {
        if let Some(data) = self.image_cache.get(path) {
            return Ok(data);
        }
        let data = self
            .accessor
            .get_resource_by_path(path)
            .ok_or_else(|| format!("image not found: {}", path))?;
        self.image_cache.put(path.to_string(), data.clone());
        Ok(data)
    }

    /// 读取封面图片字节（按需）。
    pub fn get_cover(&mut self) -> Option<Vec<u8>> {
        self.accessor.cover().map(|(data, _)| data)
    }

    /// 关闭书籍：清空所有缓存。
    pub fn close(&mut self) {
        self.chapter_cache.clear();
        self.image_cache.clear();
    }
}

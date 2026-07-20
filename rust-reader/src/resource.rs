//! EPUB 资源访问封装。提供对 EpubDoc 的安全访问与路径解析工具。

use epub::doc::{EpubDoc, ResourceItem};
use std::collections::HashMap;
use std::fs::File;
use std::io::BufReader;
use std::path::PathBuf;

/// 资源访问器，封装 EpubDoc 提供的底层接口。
pub struct ResourceAccessor {
    pub doc: EpubDoc<BufReader<File>>,
}

impl ResourceAccessor {
    pub fn open(path: &str) -> Result<Self, String> {
        let doc = EpubDoc::new(path).map_err(|e| format!("open epub failed: {}", e))?;
        Ok(Self { doc })
    }

    /// 返回书名。
    pub fn title(&self) -> String {
        self.doc.get_title().unwrap_or_default()
    }

    /// 返回作者。
    pub fn author(&self) -> String {
        self.doc
            .mdata("creator")
            .map(|m| m.value.clone())
            .unwrap_or_default()
    }

    /// 返回封面图片字节与 mime。
    pub fn cover(&mut self) -> Option<(Vec<u8>, String)> {
        self.doc.get_cover()
    }

    /// 返回 spine 中所有章节的资源 id 列表。
    pub fn spine_ids(&self) -> Vec<String> {
        self.doc.spine.iter().map(|s| s.idref.clone()).collect()
    }

    /// 返回资源 id → (路径, mime) 的映射。
    pub fn resources_map(&self) -> HashMap<String, (PathBuf, String)> {
        self.doc
            .resources
            .iter()
            .map(|(id, item)| (id.clone(), (item.path.clone(), item.mime.clone())))
            .collect()
    }

    /// 通过资源 id 读取内容（返回字节与 mime）。
    pub fn get_resource(&mut self, id: &str) -> Option<(Vec<u8>, String)> {
        self.doc.get_resource(id)
    }

    /// 通过 EPUB 内的完整路径读取字节。
    pub fn get_resource_by_path(&mut self, path: &str) -> Option<Vec<u8>> {
        self.doc.get_resource_by_path(path)
    }

    /// 通过资源 id 读取为字符串。
    pub fn get_resource_str(&mut self, id: &str) -> Option<(String, String)> {
        self.doc.get_resource_str(id)
    }

    /// 跳转到指定章节并返回其字符串内容与所在路径。
    pub fn chapter_str(&mut self, index: usize) -> Option<(String, String)> {
        if !self.doc.set_current_chapter(index) {
            return None;
        }
        let (content, mime) = self.doc.get_current_str()?;
        let path = self
            .doc
            .get_current_path()
            .map(|p| p.to_string_lossy().to_string())
            .unwrap_or_default();
        Some((content, format!("{}|{}", mime, path)))
    }

    /// 返回资源条目（仅元数据，不读字节）。
    pub fn resource_item(&self, id: &str) -> Option<&ResourceItem> {
        self.doc.resources.get(id)
    }
}

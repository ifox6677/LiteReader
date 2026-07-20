//! PDF 引擎：使用 pdfium-render 渲染 PDF 页面为图片。
//! 每页作为一个"章节"，按需渲染并缓存。

use lru::LruCache;
use pdfium_render::prelude::*;
use std::num::NonZeroUsize;

/// PDF 渲染目标宽度（像素）。适配主流手机屏幕宽度，平衡清晰度与内存。
const RENDER_TARGET_WIDTH: Pixels = 1200;

/// PDF 引擎。一个实例对应一本打开的 PDF。
pub struct PdfEngine {
    /// pdfium 库绑定，只需创建一次。
    pdfium: Pdfium,
    /// PDF 文件全部字节，读入内存避免反复 IO。
    data: Vec<u8>,
    /// 总页数。
    page_count: u32,
    /// 已渲染页面的 LRU 缓存：page index → JPEG bytes。
    page_cache: LruCache<u32, Vec<u8>>,
}

// Pdfium 内部含 trait object 不自动实现 Send。
// 通过 Mutex 保证线程安全，手动标记 Send。
unsafe impl Send for PdfEngine {}

impl PdfEngine {
    /// 打开 PDF 文件，读取页数。
    pub fn open(path: &str) -> Result<Self, String> {
        let bindings =
            Pdfium::bind_to_system_library().map_err(|e| format!("bind pdfium failed: {}", e))?;
        let pdfium = Pdfium::new(bindings);

        let data = std::fs::read(path).map_err(|e| format!("read file failed: {}", e))?;

        // 在独立作用域中加载文档获取页数，避免借用阻碍 pdfium 移动
        let page_count = {
            let document = pdfium
                .load_pdf_from_byte_vec(data.clone(), None)
                .map_err(|e| format!("load pdf failed: {}", e))?;
            document.pages().len() as u32
        };

        Ok(Self {
            pdfium,
            data,
            page_count,
            page_cache: LruCache::new(NonZeroUsize::new(5).unwrap()),
        })
    }

    /// 返回总页数。
    pub fn page_count(&self) -> u32 {
        self.page_count
    }

    /// 渲染指定页面为 JPEG 字节。命中缓存直接返回。
    pub fn render_page(&mut self, page_index: u32) -> Result<Vec<u8>, String> {
        if page_index >= self.page_count {
            return Err(format!("page {} out of range {}", page_index, self.page_count));
        }

        // 1. 命中缓存
        if let Some(data) = self.page_cache.get(&page_index) {
            return Ok(data.clone());
        }

        // 2. 从内存加载 PDF 文档
        let document = self
            .pdfium
            .load_pdf_from_byte_vec(self.data.clone(), None)
            .map_err(|e| format!("load pdf failed: {}", e))?;

        // 3. 获取页面
        let page = document
            .pages()
            .get(page_index as u16)
            .map_err(|e| format!("get page {} failed: {}", page_index, e))?;

        // 4. 渲染为位图
        let bitmap = page
            .render_with_config(&PdfRenderConfig::new().set_target_width(RENDER_TARGET_WIDTH))
            .map_err(|e| format!("render page {} failed: {}", page_index, e))?;

        // 5. 转为 RGB 图像并编码为 JPEG
        let image = bitmap.as_image();
        let rgb = image.to_rgb8();
        let mut bytes = Vec::with_capacity(100_000);
        image::codecs::jpeg::JpegEncoder::new_with_quality(&mut bytes, 90)
            .encode(&rgb, rgb.width(), rgb.height(), image::ExtendedColorType::Rgb8)
            .map_err(|e| format!("encode jpeg failed: {}", e))?;

        // 6. 写入缓存
        self.page_cache.put(page_index, bytes.clone());

        Ok(bytes)
    }

    /// 提取封面（第一页）。
    pub fn render_cover(path: &str) -> Result<Vec<u8>, String> {
        let bindings =
            Pdfium::bind_to_system_library().map_err(|e| format!("bind pdfium failed: {}", e))?;
        let pdfium = Pdfium::new(bindings);

        let data = std::fs::read(path).map_err(|e| format!("read file failed: {}", e))?;
        let document = pdfium
            .load_pdf_from_byte_vec(data, None)
            .map_err(|e| format!("load pdf failed: {}", e))?;

        if document.pages().len() == 0 {
            return Err("pdf has no pages".to_string());
        }

        let page = document
            .pages()
            .get(0)
            .map_err(|e| format!("get page 0 failed: {}", e))?;

        let bitmap = page
            .render_with_config(&PdfRenderConfig::new().set_target_width(600))
            .map_err(|e| format!("render cover failed: {}", e))?;

        let image = bitmap.as_image();
        let rgb = image.to_rgb8();
        let mut bytes = Vec::with_capacity(50_000);
        image::codecs::jpeg::JpegEncoder::new_with_quality(&mut bytes, 85)
            .encode(&rgb, rgb.width(), rgb.height(), image::ExtendedColorType::Rgb8)
            .map_err(|e| format!("encode jpeg failed: {}", e))?;

        Ok(bytes)
    }

    /// 关闭书籍，清空缓存。
    pub fn close(&mut self) {
        self.page_cache.clear();
    }
}

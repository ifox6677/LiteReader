//! TXT 引擎：自动检测 GBK/UTF-8 编码，按章节标记切分文本。
//!
//! - 编码检测：优先 UTF-8 BOM → 严格 UTF-8 → 回退 GBK
//! - 章节切分：识别 "第X章/回/节/卷" 与 "Chapter X" 标记
//! - 无标记时按字符数切分（默认 50000 字符/章），在段落边界断开
//! - 与 EPUB 引擎一致：章节懒加载、当前+下一章缓存

use crate::cache::ChapterCache;
use crate::chapter::{Block, BookInfo, ChapterInfo};
use encoding_rs::{GBK, UTF_8};

/// 无章节标记时，每章的目标字符数
const CHARS_PER_CHAPTER: usize = 50_000;

/// TXT 引擎。一个实例对应一本打开的 TXT。
pub struct TxtEngine {
    book_info: BookInfo,
    /// 各章节在解码后 UTF-8 文本中的 (起始字节, 结束字节) 范围
    chapter_ranges: Vec<(usize, usize)>,
    /// 解码后的全文（UTF-8）
    content: String,
    chapter_cache: ChapterCache,
}

impl TxtEngine {
    /// 打开 TXT 文件，自动检测编码并切分章节。
    pub fn open(path: &str) -> Result<Self, String> {
        let bytes = std::fs::read(path).map_err(|e| format!("read file failed: {}", e))?;

        let (content, _encoding_name) = decode_bytes(&bytes);

        let (mut chapters, ranges) = split_chapters(&content);

        // 整本书都没有内容
        if chapters.is_empty() {
            chapters.push(ChapterInfo {
                index: 0,
                title: "空文档".to_string(),
                path: String::new(),
            });
        }

        let title = std::path::Path::new(path)
            .file_stem()
            .map(|s| s.to_string_lossy().to_string())
            .unwrap_or_else(|| "未知书名".to_string());

        let book_info = BookInfo {
            title,
            author: "未知作者".to_string(),
            cover: String::new(),
            chapters,
        };

        Ok(Self {
            book_info,
            chapter_ranges: ranges,
            content,
            chapter_cache: ChapterCache::new(),
        })
    }

    pub fn book_info(&self) -> &BookInfo {
        &self.book_info
    }

    /// 加载章节：从全文中切片出该章文本，按段落拆为 Block::Text。
    /// 命中缓存直接返回。同时预加载下一章节。
    pub fn load_chapter(&mut self, index: u32) -> Result<Vec<Block>, String> {
        if let Some(blocks) = self.chapter_cache.get(index) {
            return Ok(blocks.clone());
        }

        let idx = index as usize;
        if idx >= self.chapter_ranges.len() {
            return Err(format!("chapter index {} out of range", index));
        }

        let blocks = text_to_blocks(&self.content, self.chapter_ranges[idx]);

        self.chapter_cache.put_current(index, blocks.clone());

        // 预加载下一章
        let next_idx = index + 1;
        if (next_idx as usize) < self.chapter_ranges.len()
            && self.chapter_cache.get(next_idx).is_none()
        {
            let next_blocks = text_to_blocks(&self.content, self.chapter_ranges[next_idx as usize]);
            self.chapter_cache.put_next(next_idx, next_blocks);
        }

        Ok(blocks)
    }

    /// 关闭书籍：清空缓存（content 保留，由 close 后对象销毁释放）。
    pub fn close(&mut self) {
        self.chapter_cache.clear();
        self.content.clear();
    }
}

/// 把字节切片解码为 UTF-8 字符串。
///
/// 检测顺序：
/// 1. UTF-8 BOM (EF BB BF)
/// 2. 严格 UTF-8（无错误才接受）
/// 3. 回退 GBK（中文 TXT 常见编码）
fn decode_bytes(bytes: &[u8]) -> (String, &'static str) {
    // 1. UTF-8 BOM
    if bytes.starts_with(&[0xEF, 0xBB, 0xBF]) {
        let (cow, _, _) = UTF_8.decode(&bytes[3..]);
        return (cow.into_owned(), "UTF-8-BOM");
    }

    // 2. GBK BOM（罕见，但有些 Windows 文件会带）
    if bytes.starts_with(&[0xFF, 0xFE]) || bytes.starts_with(&[0xFE, 0xFF]) {
        let (cow, _, _) = GBK.decode(bytes);
        return (cow.into_owned(), "GBK-BOM");
    }

    // 3. 尝试严格 UTF-8
    let (cow, _encoding, had_errors) = UTF_8.decode(bytes);
    if !had_errors {
        return (cow.into_owned(), "UTF-8");
    }

    // 4. 回退 GBK
    let (cow, _, _) = GBK.decode(bytes);
    (cow.into_owned(), "GBK")
}

/// 把指定字节范围内的文本转换为 Block 列表。
///
/// - 第一行若为章节标题，作为 Block::Title
/// - 其余按段落（空行分隔）拆为 Block::Text
fn text_to_blocks(content: &str, (start, end): (usize, usize)) -> Vec<Block> {
    if start >= end || start >= content.len() {
        return vec![Block::Text("（本章为空）".to_string())];
    }
    let end = end.min(content.len());
    let slice = &content[start..end];

    let mut blocks = Vec::new();
    let mut paragraph = String::new();
    let mut first_line_consumed = false;

    for line in slice.split('\n') {
        let trimmed = line.trim_end_matches('\r').trim();
        if trimmed.is_empty() {
            // 段落结束
            if !paragraph.is_empty() {
                let decoded = paragraph.trim().to_string();
                if !decoded.is_empty() {
                    blocks.push(if !first_line_consumed {
                        first_line_consumed = true;
                        // 首段若像章节标题则作为 Title
                        if is_chapter_title(&decoded) && decoded.chars().count() <= 30 {
                            Block::Title(decoded)
                        } else {
                            Block::Text(decoded)
                        }
                    } else {
                        Block::Text(decoded)
                    });
                }
                paragraph.clear();
            }
        } else {
            if !paragraph.is_empty() {
                paragraph.push('\n');
            }
            paragraph.push_str(trimmed);
        }
    }
    // 处理最后一段
    if !paragraph.is_empty() {
        let decoded = paragraph.trim().to_string();
        if !decoded.is_empty() {
            blocks.push(if !first_line_consumed {
                if is_chapter_title(&decoded) && decoded.chars().count() <= 30 {
                    Block::Title(decoded)
                } else {
                    Block::Text(decoded)
                }
            } else {
                Block::Text(decoded)
            });
        }
    }

    if blocks.is_empty() {
        blocks.push(Block::Text("（本章为空）".to_string()));
    }
    blocks
}

/// 按章节标记切分文本。
///
/// 优先识别章节标记行；若无任何标记，则按字符数切分。
fn split_chapters(content: &str) -> (Vec<ChapterInfo>, Vec<(usize, usize)>) {
    // 收集所有章节标记的 (字节偏移, 标题文本)
    let mut marks: Vec<(usize, String)> = Vec::new();

    let mut offset = 0usize;
    for line in content.split_inclusive('\n') {
        let line_len = line.len();
        let trimmed = line.trim();
        if !trimmed.is_empty() && is_chapter_title(trimmed) {
            marks.push((offset, trimmed.to_string()));
        }
        offset += line_len;
    }

    if marks.is_empty() {
        return split_by_size(content, CHARS_PER_CHAPTER);
    }

    let mut chapters = Vec::new();
    let mut ranges = Vec::new();
    let mut chapter_idx = 0u32;

    // 第一个标记之前的内容作为"序章"（若有非空白内容）
    if marks[0].0 > 0 {
        let pre = &content[..marks[0].0];
        if !pre.trim().is_empty() {
            chapters.push(ChapterInfo {
                index: chapter_idx,
                title: "序章".to_string(),
                path: String::new(),
            });
            ranges.push((0, marks[0].0));
            chapter_idx += 1;
        }
    }

    // 按标记切分
    for (i, (start, title)) in marks.iter().enumerate() {
        let end = if i + 1 < marks.len() {
            marks[i + 1].0
        } else {
            content.len()
        };
        chapters.push(ChapterInfo {
            index: chapter_idx,
            title: title.clone(),
            path: String::new(),
        });
        ranges.push((*start, end));
        chapter_idx += 1;
    }

    (chapters, ranges)
}

/// 判断一行文本是否为章节标题。
///
/// 识别模式：
/// - `第X章/回/节/卷`，X 为阿拉伯数字或中文数字
/// - `Chapter N` / `CHAPTER N`（不区分大小写）
fn is_chapter_title(s: &str) -> bool {
    let s = s.trim();
    if s.is_empty() {
        return false;
    }
    // 章节标题一般不超过 30 字符
    if s.chars().count() > 30 {
        return false;
    }

    // 中文章节：第X章/回/节/卷
    if s.starts_with("第") {
        // "第" 占 3 字节
        let rest = &s[3..];
        for marker in &["章", "回", "节", "卷"] {
            if let Some(pos) = rest.find(marker) {
                let middle = &rest[..pos];
                if !middle.is_empty() && is_chapter_number(middle) {
                    // 标记后应无内容或仅有少量副标题
                    let after = &rest[pos + marker.len()..];
                    return after.trim().is_empty() || after.chars().count() <= 20;
                }
            }
        }
        return false;
    }

    // 英文章节：Chapter N
    let lower_first: String = s.chars().take(7).map(|c| c.to_ascii_lowercase()).collect();
    if lower_first == "chapter" && s.len() >= 7 {
        let rest = s[7..].trim_start();
        if let Some(first_word) = rest.split_whitespace().next() {
            return is_ascii_number(first_word) || is_roman_numeral(first_word);
        }
    }

    false
}

/// 判断字符串是否为章节编号（阿拉伯数字或中文数字）。
fn is_chapter_number(s: &str) -> bool {
    if s.is_empty() {
        return false;
    }
    // 纯阿拉伯数字
    if s.chars().all(|c| c.is_ascii_digit()) {
        return true;
    }
    // 中文数字（含复合："二十三", "一百零五", "两"等）
    const CN_DIGITS: &str = "零一二三四五六七八九十百千万两";
    if s.chars().all(|c| CN_DIGITS.contains(c)) {
        return true;
    }
    false
}

fn is_ascii_number(s: &str) -> bool {
    !s.is_empty() && s.chars().all(|c| c.is_ascii_digit())
}

fn is_roman_numeral(s: &str) -> bool {
    !s.is_empty() && s.chars().all(|c| matches!(c, 'I' | 'V' | 'X' | 'L' | 'C' | 'D' | 'M' | 'i' | 'v' | 'x' | 'l' | 'c' | 'd' | 'm'))
}

/// 无章节标记时按字符数切分。在段落边界（换行处）断开避免截断段落。
fn split_by_size(content: &str, max_chars: usize) -> (Vec<ChapterInfo>, Vec<(usize, usize)>) {
    let total_chars = content.chars().count();
    if total_chars <= max_chars || content.is_empty() {
        return (
            vec![ChapterInfo {
                index: 0,
                title: "全文".to_string(),
                path: String::new(),
            }],
            vec![(0, content.len())],
        );
    }

    let mut chapters = Vec::new();
    let mut ranges = Vec::new();

    let mut chapter_start_byte = 0usize;
    let mut char_count = 0usize;
    let mut last_newline_byte: Option<usize> = None;
    let mut chapter_idx = 0u32;

    for (byte_idx, ch) in content.char_indices() {
        char_count += 1;
        if ch == '\n' {
            last_newline_byte = Some(byte_idx + 1);
        }

        // 达到当前章的目标字符数
        if char_count >= (chapter_idx as usize + 1) * max_chars {
            let split_pos = match last_newline_byte {
                Some(p) if p > chapter_start_byte => p,
                _ => byte_idx + ch.len_utf8(),
            };

            chapters.push(ChapterInfo {
                index: chapter_idx,
                title: format!("第 {} 部分", chapter_idx + 1),
                path: String::new(),
            });
            ranges.push((chapter_start_byte, split_pos));
            chapter_start_byte = split_pos;
            chapter_idx += 1;
            last_newline_byte = None;

            if chapter_start_byte >= content.len() {
                break;
            }
        }
    }

    // 最后一部分
    if chapter_start_byte < content.len() {
        chapters.push(ChapterInfo {
            index: chapter_idx,
            title: format!("第 {} 部分", chapter_idx + 1),
            path: String::new(),
        });
        ranges.push((chapter_start_byte, content.len()));
    }

    (chapters, ranges)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_decode_utf8_bom() {
        let bytes = [0xEF, 0xBB, 0xBF, b'h', b'i'];
        let (s, enc) = decode_bytes(&bytes);
        assert_eq!(s, "hi");
        assert_eq!(enc, "UTF-8-BOM");
    }

    #[test]
    fn test_decode_utf8_plain() {
        let bytes = "你好，世界".as_bytes();
        let (s, enc) = decode_bytes(bytes);
        assert_eq!(s, "你好，世界");
        assert_eq!(enc, "UTF-8");
    }

    #[test]
    fn test_decode_gbk() {
        // "你好" 的 GBK 编码
        let bytes = [0xC4, 0xE3, 0xBA, 0xC3];
        let (s, enc) = decode_bytes(&bytes);
        assert_eq!(s, "你好");
        assert_eq!(enc, "GBK");
    }

    #[test]
    fn test_is_chapter_title_chinese() {
        assert!(is_chapter_title("第一章"));
        assert!(is_chapter_title("第二十三章"));
        assert!(is_chapter_title("第1章"));
        assert!(is_chapter_title("第100章"));
        assert!(is_chapter_title("第五回"));
        assert!(is_chapter_title("第十二节"));
        assert!(is_chapter_title("第一卷"));
    }

    #[test]
    fn test_is_chapter_title_english() {
        assert!(is_chapter_title("Chapter 1"));
        assert!(is_chapter_title("CHAPTER 12"));
        assert!(is_chapter_title("Chapter II"));
    }

    #[test]
    fn test_is_chapter_title_negative() {
        assert!(!is_chapter_title("这是一个普通句子"));
        assert!(!is_chapter_title(""));
        assert!(!is_chapter_title("第三人民医院")); // "第三人民" 不是有效编号
    }

    #[test]
    fn test_split_chapters_with_marks() {
        let content = "序章内容\n这是开头。\n第一章 开始\n内容A\n\n内容B\n第二章 结束\n内容C\n";
        let (chapters, ranges) = split_chapters(content);
        // 序章 + 第一章 + 第二章
        assert_eq!(chapters.len(), 3);
        assert_eq!(chapters[0].title, "序章");
        assert_eq!(chapters[1].title, "第一章 开始");
        assert_eq!(chapters[2].title, "第二章 结束");
        // 范围连续且覆盖全文
        assert_eq!(ranges[0].0, 0);
        assert_eq!(ranges[ranges.len() - 1].1, content.len());
    }

    #[test]
    fn test_split_chapters_no_marks() {
        let content = "a".repeat(50_001);
        let (chapters, ranges) = split_chapters(&content);
        assert_eq!(chapters.len(), 2);
        assert_eq!(ranges[0].0, 0);
        assert_eq!(ranges[1].1, content.len());
    }

    #[test]
    fn test_split_chapters_no_marks_short() {
        let content = "短文本";
        let (chapters, ranges) = split_chapters(content);
        assert_eq!(chapters.len(), 1);
        assert_eq!(chapters[0].title, "全文");
        assert_eq!(ranges[0], (0, content.len()));
    }

    #[test]
    fn test_text_to_blocks_paragraphs() {
        let content = "第一章 测试\n\n第一段。\n\n第二段。\n";
        let (_chapters, ranges) = split_chapters(content);
        let blocks = text_to_blocks(content, ranges[0]);
        // 第一行 "第一章 测试" 被识别为标题
        assert!(blocks.iter().any(|b| matches!(b, Block::Title(t) if t.contains("第一章"))));
        assert!(blocks.iter().any(|b| matches!(b, Block::Text(t) if t.contains("第一段"))));
        assert!(blocks.iter().any(|b| matches!(b, Block::Text(t) if t.contains("第二段"))));
    }

    #[test]
    fn test_load_chapter_returns_blocks() {
        let temp = std::env::temp_dir().join("litereader_test_txt.txt");
        let content = "第一章 开始\n段落一\n\n段落二\n第二章 结束\n内容\n";
        std::fs::write(&temp, content).unwrap();

        let mut engine = TxtEngine::open(temp.to_str().unwrap()).unwrap();
        assert_eq!(engine.book_info.chapters.len(), 2);

        let blocks = engine.load_chapter(0).unwrap();
        assert!(!blocks.is_empty());
        // 第一章首块应为 Title
        assert!(matches!(blocks[0], Block::Title(_)));

        let _ = std::fs::remove_file(&temp);
    }
}

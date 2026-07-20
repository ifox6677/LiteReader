//! 章节相关数据结构与 HTML→Block 解析。

use serde::{Deserialize, Serialize};

/// 整本书的元信息。
#[derive(Serialize, Deserialize, Clone, Default)]
pub struct BookInfo {
    pub title: String,
    pub author: String,
    pub cover: String,
    pub chapters: Vec<ChapterInfo>,
}

/// 章节索引。
#[derive(Serialize, Deserialize, Clone)]
pub struct ChapterInfo {
    pub index: u32,
    pub title: String,
    pub path: String,
}

/// 渲染块。Rust 侧把 XHTML 转换为该枚举列表后返回给 Kotlin。
#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(tag = "type", content = "content")]
pub enum Block {
    Text(String),
    Image(String),
    Title(String),
}

/// 块级 HTML 标签——遇到时刷新文本缓冲区，确保每段独立。
const BLOCK_TAGS: &[&str] = &[
    "p", "h1", "h2", "h3", "h4", "h5", "h6", "div", "section", "blockquote",
    "li", "dt", "dd", "pre", "article", "aside", "header", "footer", "main",
    "figure", "figcaption", "details", "summary", "tr", "table",
];

/// 把 XHTML 字符串解析为 Block 列表。
///
/// `base_path` 为章节 xhtml 在 EPUB 内的相对路径，用于把图片相对路径转换为绝对路径。
///
/// 本实现严格按 UTF-8 字符边界进行切片，避免多字节字符（如中文）导致的 panic。
pub fn parse_html_to_blocks(html: &str, base_path: &str) -> Vec<Block> {
    let cleaned = remove_script_style(html);
    let bytes = cleaned.as_bytes();
    let mut blocks: Vec<Block> = Vec::new();
    let mut text_buf = String::new();
    let mut i = 0;

    while i < bytes.len() {
        if bytes[i] == b'<' {
            // XML 处理指令 <?xml ... ?>
            if cleaned[i..].starts_with("<?") {
                if let Some(end) = cleaned[i..].find("?>") {
                    i += end + 2;
                } else if let Some(end) = cleaned[i..].find('>') {
                    i += end + 1;
                } else {
                    i = cleaned.len();
                }
                continue;
            }
            // 注释 <!-- ... -->
            if cleaned[i..].starts_with("<!--") {
                if let Some(end) = cleaned[i + 4..].find("-->") {
                    i += 4 + end + 3;
                    continue;
                }
            }
            // DOCTYPE / CDATA 等声明 <!...>
            if cleaned[i..].starts_with("<!") {
                if let Some(end) = cleaned[i..].find('>') {
                    i += end + 1;
                } else {
                    i = cleaned.len();
                }
                continue;
            }
            if let Some((tag, attrs, _self_closing, end)) = parse_tag(&cleaned, i) {
                let tag_lower = tag.to_lowercase();
                match tag_lower.as_str() {
                    "img" => {
                        flush_text(&mut text_buf, &mut blocks);
                        if let Some(src) = attrs.get("src") {
                            let resolved = resolve_relative_path(base_path, src);
                            if !resolved.is_empty() {
                                blocks.push(Block::Image(resolved));
                            }
                        }
                    }
                    "br" => text_buf.push('\n'),
                    "h1" | "h2" | "h3" | "h4" | "h5" | "h6" => {
                        flush_text(&mut text_buf, &mut blocks);
                        // 取整个起始标签后到闭合标签之间的纯文本
                        let close = format!("</{}", tag_lower);
                        let inner_start = end;
                        let inner_end = cleaned[inner_start..]
                            .find(&close)
                            .map(|p| inner_start + p)
                            .unwrap_or(cleaned.len());
                        let title_text = extract_text(&cleaned[inner_start..inner_end]);
                        let decoded = decode_entities(&title_text);
                        let trimmed = decoded.trim();
                        if !trimmed.is_empty() {
                            blocks.push(Block::Title(trimmed.to_string()));
                        }
                        // 跳过闭合标签
                        if let Some(pos) = cleaned[inner_end..].find('>') {
                            i = inner_end + pos + 1;
                            continue;
                        }
                    }
                    _ => {
                        if BLOCK_TAGS.contains(&tag_lower.as_str()) {
                            flush_text(&mut text_buf, &mut blocks);
                        }
                    }
                }
                i = end;
            } else {
                // 非法 <，按普通字符处理
                push_char_at(&cleaned, &mut text_buf, &mut i);
            }
        } else {
            push_char_at(&cleaned, &mut text_buf, &mut i);
        }
    }
    flush_text(&mut text_buf, &mut blocks);
    blocks
}

/// 在 `s` 的字节位置 `i` 处读取一个 UTF-8 字符，压入 `buf`，并把 `i` 前进到下一个字符。
///
/// 调用方需保证 `i` 位于 UTF-8 字符边界上。由于本模块中 `i` 只会在 ASCII 字节
/// （如 `<`、`>`）处停留或从 0 开始线性前进，该前提始终成立。
fn push_char_at(s: &str, buf: &mut String, i: &mut usize) {
    match s[*i..].chars().next() {
        Some(ch) => {
            buf.push(ch);
            *i += ch.len_utf8();
        }
        None => {
            *i += 1;
        }
    }
}

fn flush_text(buf: &mut String, blocks: &mut Vec<Block>) {
    let decoded = decode_entities(buf);
    let trimmed = decoded.trim();
    if !trimmed.is_empty() {
        blocks.push(Block::Text(trimmed.to_string()));
    }
    buf.clear();
}

/// 解析从 `start` 位置开始的 HTML 标签。
/// 返回 (标签名, 属性表, 是否自闭合, 标签结束位置)。
fn parse_tag(html: &str, start: usize) -> Option<(String, std::collections::HashMap<String, String>, bool, usize)> {
    let bytes = html.as_bytes();
    if start >= bytes.len() || bytes[start] != b'<' {
        return None;
    }
    let mut i = start + 1;
    // 跳过 /
    if i < bytes.len() && bytes[i] == b'/' {
        i += 1;
    }
    let name_start = i;
    while i < bytes.len() && bytes[i].is_ascii_alphanumeric() {
        i += 1;
    }
    if i == name_start {
        return None;
    }
    let name = html[name_start..i].to_lowercase();

    let mut attrs = std::collections::HashMap::new();
    let mut self_closing = false;

    while i < bytes.len() && bytes[i] != b'>' {
        let c = bytes[i];
        if c.is_ascii_whitespace() {
            i += 1;
            continue;
        }
        if c == b'/' {
            self_closing = true;
            i += 1;
            continue;
        }
        if c == b'>' {
            break;
        }
        // 解析属性名
        let attr_name_start = i;
        while i < bytes.len()
            && bytes[i] != b'='
            && bytes[i] != b'>'
            && !bytes[i].is_ascii_whitespace()
        {
            i += 1;
        }
        let attr_name = html[attr_name_start..i].to_lowercase();
        // 跳过空白
        while i < bytes.len() && bytes[i].is_ascii_whitespace() {
            i += 1;
        }
        let mut value = String::new();
        if i < bytes.len() && bytes[i] == b'=' {
            i += 1;
            while i < bytes.len() && bytes[i].is_ascii_whitespace() {
                i += 1;
            }
            if i < bytes.len() && (bytes[i] == b'"' || bytes[i] == b'\'') {
                let quote = bytes[i];
                i += 1;
                let val_start = i;
                while i < bytes.len() && bytes[i] != quote {
                    i += 1;
                }
                value = html[val_start..i].to_string();
                if i < bytes.len() {
                    i += 1; // 跳过结束引号
                }
            } else {
                let val_start = i;
                while i < bytes.len()
                    && !bytes[i].is_ascii_whitespace()
                    && bytes[i] != b'>'
                {
                    i += 1;
                }
                value = html[val_start..i].to_string();
            }
        }
        if !attr_name.is_empty() {
            attrs.insert(attr_name, value);
        }
    }
    // 跳过 >
    if i < bytes.len() && bytes[i] == b'>' {
        i += 1;
    }
    Some((name, attrs, self_closing, i))
}

/// 提取一段 HTML 片段中的纯文本（去掉所有标签）。UTF-8 安全。
fn extract_text(html: &str) -> String {
    let mut out = String::new();
    let bytes = html.as_bytes();
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'<' {
            // 跳过整个标签
            while i < bytes.len() && bytes[i] != b'>' {
                i += 1;
            }
            if i < bytes.len() {
                i += 1;
            }
        } else {
            push_char_at(html, &mut out, &mut i);
        }
    }
    out
}

/// 解码常见 HTML 实体。
fn decode_entities(s: &str) -> String {
    s.replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&copy;", "©")
        .replace("&reg;", "®")
        .replace("&mdash;", "—")
        .replace("&ndash;", "–")
        .replace("&hellip;", "…")
        .replace("&#160;", " ")
}

/// 移除 `<script>` 与 `<style>` 标签及其内容。
///
/// 关键：不能使用 `String::to_lowercase()`，因为某些 Unicode 字符的小写形式
/// 字节长度会变化（例如 'İ' U+0130 为 2 字节，小写为 "i̇" 3 字节），导致
/// 后续按原始字符串字节位置切片时越过字符边界 → panic。
/// 这里仅在 ASCII 范围内做小写比较，字节长度恒定不变。
fn remove_script_style(html: &str) -> String {
    let bytes = html.as_bytes();
    // 仅 ASCII 小写：字节长度与原字符串相同，索引可共用
    let lower: Vec<u8> = bytes.iter().map(|b| b.to_ascii_lowercase()).collect();
    let mut out = String::with_capacity(html.len());
    let mut i = 0;
    while i < bytes.len() {
        if starts_with_ascii_ci(&lower[i..], b"<script") {
            if let Some(end) = find_sub_bytes(&lower[i..], b"</script>") {
                i += end + b"</script>".len();
                continue;
            } else {
                break;
            }
        }
        if starts_with_ascii_ci(&lower[i..], b"<style") {
            if let Some(end) = find_sub_bytes(&lower[i..], b"</style>") {
                i += end + b"</style>".len();
                continue;
            } else {
                break;
            }
        }
        // 找下一个可能是 <script / <style 的位置，批量拷贝原文字本
        let next = (i + 1..bytes.len())
            .find(|&p| lower[p] == b'<' && lower[p..].starts_with(b"<script") || {
                p < bytes.len() && lower[p] == b'<' && lower[p..].starts_with(b"<style")
            })
            .unwrap_or(bytes.len());
        // i 与 next 都位于 '<'（ASCII，必为字符边界）或 len 处，切片安全
        out.push_str(&html[i..next]);
        i = next;
    }
    out
}

/// ASCII 大小写不敏感的前缀匹配。
fn starts_with_ascii_ci(haystack: &[u8], needle: &[u8]) -> bool {
    if haystack.len() < needle.len() {
        return false;
    }
    haystack[..needle.len()]
        .iter()
        .zip(needle.iter())
        .all(|(a, b)| a.eq_ignore_ascii_case(b))
}

/// 在字节切片中查找子串（精确匹配，因 needle 已是小写）。
fn find_sub_bytes(haystack: &[u8], needle: &[u8]) -> Option<usize> {
    if needle.is_empty() || haystack.len() < needle.len() {
        return None;
    }
    (0..=haystack.len() - needle.len())
        .find(|&i| &haystack[i..i + needle.len()] == needle)
}

/// 将相对路径基于章节路径解析为 EPUB 内的绝对路径。
/// 例如 base="OEBPS/Text/ch1.xhtml", src="../Images/a.jpg" → "OEBPS/Images/a.jpg"
pub fn resolve_relative_path(base: &str, src: &str) -> String {
    // 绝对 / epub:// / http(s):// / data: 不处理
    if src.starts_with('/')
        || src.starts_with("epub://")
        || src.starts_with("http://")
        || src.starts_with("https://")
        || src.starts_with("data:")
    {
        return src.trim_start_matches('/').to_string();
    }
    let base_dir = base.rfind('/').map(|p| &base[..p]).unwrap_or("");
    let combined = if base_dir.is_empty() {
        src.to_string()
    } else {
        format!("{}/{}", base_dir, src)
    };
    let mut stack: Vec<&str> = Vec::new();
    for seg in combined.split('/') {
        match seg {
            "." | "" => {}
            ".." => {
                stack.pop();
            }
            s => stack.push(s),
        }
    }
    stack.join("/")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_resolve_relative_path() {
        assert_eq!(
            resolve_relative_path("OEBPS/Text/ch1.xhtml", "../Images/a.jpg"),
            "OEBPS/Images/a.jpg"
        );
        assert_eq!(
            resolve_relative_path("OEBPS/Text/ch1.xhtml", "img/b.jpg"),
            "OEBPS/Text/img/b.jpg"
        );
        assert_eq!(
            resolve_relative_path("ch1.xhtml", "a.jpg"),
            "a.jpg"
        );
    }

    #[test]
    fn test_parse_simple_html() {
        let html = r#"<html><body><h1>Title</h1><p>Hello world</p><img src="a.jpg"/><p>End</p></body></html>"#;
        let blocks = parse_html_to_blocks(html, "ch.xhtml");
        assert_eq!(blocks.len(), 4);
        assert!(matches!(&blocks[0], Block::Title(t) if t == "Title"));
        assert!(matches!(&blocks[1], Block::Text(t) if t.contains("Hello")));
        assert!(matches!(&blocks[2], Block::Image(p) if p == "a.jpg"));
        assert!(matches!(&blocks[3], Block::Text(t) if t == "End"));
    }

    #[test]
    fn test_parse_chinese_html() {
        let html = r#"<html><body><h1>第一章</h1><p>你好，世界！</p><p>中文测试。</p></body></html>"#;
        let blocks = parse_html_to_blocks(html, "ch.xhtml");
        assert!(blocks.len() >= 3);
        assert!(matches!(&blocks[0], Block::Title(t) if t.contains("第一章")));
        assert!(matches!(&blocks[1], Block::Text(t) if t.contains("你好")));
    }

    #[test]
    fn test_remove_script_style() {
        let html = r#"<p>before</p><script>var x=1;</script><style>.a{}</style><p>after</p>"#;
        let cleaned = remove_script_style(html);
        assert!(!cleaned.contains("script"));
        assert!(!cleaned.contains("style"));
        assert!(cleaned.contains("before"));
        assert!(cleaned.contains("after"));
    }

    #[test]
    fn test_remove_script_style_with_unicode() {
        // 包含可能改变小写长度的字符，验证不会 panic
        let html = "İ<p>x</p><script>var y=2;</script><p>end</p>";
        let cleaned = remove_script_style(html);
        assert!(!cleaned.contains("script"));
        assert!(cleaned.contains("end"));
    }
}

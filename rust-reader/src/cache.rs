//! 章节缓存：缓存当前章节与下一章节的 Block 列表。

use crate::chapter::Block;

/// 章节缓存。按设计文档要求，仅缓存当前章节和下一章节。
pub struct ChapterCache {
    current: Option<(u32, Vec<Block>)>,
    next: Option<(u32, Vec<Block>)>,
}

impl ChapterCache {
    pub fn new() -> Self {
        Self {
            current: None,
            next: None,
        }
    }

    /// 查询缓存中是否存在指定章节。
    pub fn get(&self, index: u32) -> Option<&Vec<Block>> {
        if let Some((idx, blocks)) = &self.current {
            if *idx == index {
                return Some(blocks);
            }
        }
        if let Some((idx, blocks)) = &self.next {
            if *idx == index {
                return Some(blocks);
            }
        }
        None
    }

    /// 写入当前章节缓存。如果下一章正好是新的当前章，则提升。
    pub fn put_current(&mut self, index: u32, blocks: Vec<Block>) {
        // 若 next 恰好是新的当前章，提升为 current
        if let Some((idx, _)) = &self.next {
            if *idx == index {
                let moved = self.next.take().unwrap();
                self.current = Some(moved);
                return;
            }
        }
        self.current = Some((index, blocks));
    }

    /// 写入下一章节缓存。
    pub fn put_next(&mut self, index: u32, blocks: Vec<Block>) {
        self.next = Some((index, blocks));
    }

    /// 清空缓存。
    pub fn clear(&mut self) {
        self.current = None;
        self.next = None;
    }
}

impl Default for ChapterCache {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_cache_put_and_get() {
        let mut cache = ChapterCache::new();
        cache.put_current(1, vec![Block::Text("a".into())]);
        assert!(cache.get(1).is_some());
        assert!(cache.get(2).is_none());

        cache.put_next(2, vec![Block::Text("b".into())]);
        assert!(cache.get(2).is_some());

        // 提升测试
        cache.put_current(2, vec![]);
        assert!(cache.get(2).is_some());
        assert!(cache.get(1).is_none());
    }
}

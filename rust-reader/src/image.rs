//! 图片 LRU 缓存。

use lru::LruCache;
use std::num::NonZeroUsize;

/// 基于 LRU 的图片字节缓存。
pub struct ImageCache {
    cache: LruCache<String, Vec<u8>>,
}

impl ImageCache {
    pub fn new(capacity: usize) -> Self {
        let cap = NonZeroUsize::new(capacity.max(1)).unwrap();
        Self {
            cache: LruCache::new(cap),
        }
    }

    /// 查询缓存。命中时会更新 LRU 顺序。
    pub fn get(&mut self, path: &str) -> Option<Vec<u8>> {
        self.cache.get(path).cloned()
    }

    /// 写入缓存。
    pub fn put(&mut self, path: String, data: Vec<u8>) {
        self.cache.put(path, data);
    }

    pub fn clear(&mut self) {
        self.cache.clear();
    }
}

impl Default for ImageCache {
    fn default() -> Self {
        Self::new(20)
    }
}

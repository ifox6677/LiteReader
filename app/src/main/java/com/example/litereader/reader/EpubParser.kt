package com.example.litereader.reader

import android.util.Log
import com.example.litereader.domain.model.Chapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * EPUB 解析入口。委托给 Rust 引擎完成实际解析工作。
 *
 * 旧实现使用 epublib 在 Kotlin 侧解析；现改为：
 * - 打开书籍：RustEpubEngine.openBook
 * - 加载章节：RustEpubEngine.loadChapter（返回 Block 列表）
 * - 提取封面：RustEpubEngine.getCover
 */
object EpubParser {

    private const val TAG = "EpubParser"

    data class ChapterMetadata(
        val index: Int,
        val title: String,
        val path: String
    )

    /**
     * 打开 EPUB 并返回章节目录。Rust 侧会保留全局书籍实例。
     */
    fun parseMetadata(file: File): List<ChapterMetadata> {
        val info = RustEpubEngine.openBookInfo(file.absolutePath) ?: return emptyList()
        return info.chapters.map { ChapterMetadata(it.index, it.title, it.path) }
    }

    /**
     * 加载章节内容。Rust 侧读取 xhtml 并转换为 Block 列表。
     * 下一章的预加载由 Rust 引擎内部完成，Kotlin 侧无需重复。
     */
    suspend fun loadChapterContent(index: Int, title: String): Chapter =
        withContext(Dispatchers.IO) {
            RustEpubEngine.loadChapterBlocks(index, title)
        }

    /**
     * 提取封面图片并写入缓存目录。返回封面文件路径。
     */
    fun extractCover(epubFile: File, cacheDir: File): String? {
        return try {
            val data = RustEpubEngine.getCover(epubFile.absolutePath)
            if (data == null || data.isEmpty()) return null
            val coverFile = File(cacheDir, "${epubFile.nameWithoutExtension}_cover.jpg")
            FileOutputStream(coverFile).use { it.write(data) }
            coverFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "extractCover failed", e)
            null
        }
    }

    /**
     * 关闭书籍，释放 Rust 侧资源。离开阅读器时调用。
     */
    fun closeBook() {
        try {
            RustEpubEngine.closeBook()
        } catch (e: Exception) {
            Log.e(TAG, "closeBook failed", e)
        }
    }
}

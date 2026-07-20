package com.example.litereader.reader

import android.util.Log
import com.example.litereader.domain.model.Chapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * PDF 解析入口。委托给 Rust 引擎完成实际渲染工作。
 * 接口与 EpubParser 一致，便于 ReaderScreen 统一调用。
 */
object PdfParser {

    private const val TAG = "PdfParser"

    data class ChapterMetadata(
        val index: Int,
        val title: String,
        val path: String
    )

    /**
     * 打开 PDF 并返回页面目录。Rust 侧会保留全局 PDF 实例。
     */
    fun parseMetadata(file: File): List<ChapterMetadata> {
        val info = RustPdfEngine.openBookInfo(file.absolutePath) ?: return emptyList()
        return info.chapters.map { ChapterMetadata(it.index, it.title, it.path) }
    }

    /**
     * 加载页面内容。Rust 侧渲染页面为图片并返回 Block 列表。
     */
    suspend fun loadChapterContent(index: Int, title: String): Chapter =
        withContext(Dispatchers.IO) {
            RustPdfEngine.loadChapterBlocks(index, title)
        }

    /**
     * 提取封面（第一页）并写入缓存目录。返回封面文件路径。
     */
    fun extractCover(pdfFile: File, cacheDir: File): String? {
        return try {
            val data = RustPdfEngine.getPdfCover(pdfFile.absolutePath)
            if (data == null || data.isEmpty()) return null
            val coverFile = File(cacheDir, "${pdfFile.nameWithoutExtension}_cover.jpg")
            FileOutputStream(coverFile).use { it.write(data) }
            coverFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "extractCover failed", e)
            null
        }
    }

    /**
     * 关闭 PDF，释放 Rust 侧资源。
     */
    fun closeBook() {
        try {
            RustPdfEngine.closePdf()
        } catch (e: Exception) {
            Log.e(TAG, "closeBook failed", e)
        }
    }
}

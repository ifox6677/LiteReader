package com.example.litereader.reader

import android.util.Log
import com.example.litereader.domain.model.Chapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * TXT 解析入口。委托给 Rust 引擎完成编码检测与章节切分。
 *
 * - 编码：Rust 侧使用 encoding_rs 自动检测 UTF-8 BOM / UTF-8 / GBK
 * - 章节切分：识别 "第X章/回/节/卷" / "Chapter N" 标记，无标记时按字符数切分
 * - 接口与 EpubParser 一致，便于 ReaderScreen 统一调用
 * - 复用 EPUB 的设置面板与渲染组件
 */
object TxtParser {

    private const val TAG = "TxtParser"

    data class ChapterMetadata(
        val index: Int,
        val title: String,
        val path: String
    )

    /**
     * 打开 TXT 并返回章节目录。Rust 侧会保留全局书籍实例。
     */
    fun parseMetadata(file: File): List<ChapterMetadata> {
        val info = RustTxtEngine.openBookInfo(file.absolutePath) ?: return emptyList()
        return info.chapters.map { ChapterMetadata(it.index, it.title, it.path) }
    }

    /**
     * 加载章节内容。Rust 侧切片原文按段落返回 Block 列表。
     * 下一章预加载由 Rust 引擎内部完成。
     */
    suspend fun loadChapterContent(index: Int, title: String): Chapter =
        withContext(Dispatchers.IO) {
            RustTxtEngine.loadChapterBlocks(index, title)
        }

    /**
     * 关闭书籍，释放 Rust 侧资源。离开阅读器时调用。
     */
    fun closeBook() {
        try {
            RustTxtEngine.closeTxt()
        } catch (e: Exception) {
            Log.e(TAG, "closeBook failed", e)
        }
    }
}

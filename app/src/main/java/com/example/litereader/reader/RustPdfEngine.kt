package com.example.litereader.reader

import android.util.Log
import com.example.litereader.domain.model.Block
import com.example.litereader.domain.model.Chapter
import org.json.JSONArray
import org.json.JSONObject

/**
 * Rust PDF 引擎的 JNI 桥接层。
 *
 * 使用 pdfium-render 将 PDF 每页渲染为图片，按需加载并缓存。
 * 接口与 RustEpubEngine 一致，便于 ReaderScreen 统一调用。
 */
object RustPdfEngine {

    private const val TAG = "RustPdfEngine"

    init {
        try {
            System.loadLibrary("litereader")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "failed to load native library", e)
        }
    }

    data class BookInfo(
        val title: String,
        val author: String,
        val cover: String,
        val chapters: List<ChapterInfo>
    )

    data class ChapterInfo(
        val index: Int,
        val title: String,
        val path: String
    )

    // -------- JNI 原生方法 --------

    external fun openPdf(path: String): String

    external fun loadPdfChapter(index: Int): String

    external fun loadPdfImage(path: String): ByteArray

    external fun getPdfCover(path: String): ByteArray

    external fun closePdf()

    // -------- 解析辅助 --------

    fun openBookInfo(path: String): BookInfo? {
        val json = try {
            openPdf(path)
        } catch (e: Exception) {
            Log.e(TAG, "openPdf jni failed", e)
            return null
        }
        return parseBookInfo(json)
    }

    fun parseBookInfo(json: String): BookInfo? {
        return try {
            val obj = JSONObject(json)
            if (obj.has("error")) {
                Log.e(TAG, "openPdf error: ${obj.getString("error")}")
                return null
            }
            val chapters = mutableListOf<ChapterInfo>()
            val arr = obj.optJSONArray("chapters") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val c = arr.getJSONObject(i)
                chapters.add(
                    ChapterInfo(
                        index = c.optInt("index", i),
                        title = c.optString("title", "第 ${i + 1} 页"),
                        path = c.optString("path", "")
                    )
                )
            }
            BookInfo(
                title = obj.optString("title", "未知书名"),
                author = obj.optString("author", "未知作者"),
                cover = obj.optString("cover", ""),
                chapters = chapters
            )
        } catch (e: Exception) {
            Log.e(TAG, "parseBookInfo failed", e)
            null
        }
    }

    fun loadChapterBlocks(index: Int, chapterTitle: String): Chapter {
        val json = try {
            loadPdfChapter(index)
        } catch (e: Exception) {
            Log.e(TAG, "loadPdfChapter jni failed", e)
            return Chapter(chapterTitle, emptyList())
        }
        val blocks = parseBlocks(json)
        if (blocks.isEmpty()) {
            return Chapter(chapterTitle, listOf(Block.Text("（本页内容为空）")))
        }
        return Chapter(chapterTitle, blocks)
    }

    fun parseBlocks(json: String): List<Block> {
        val result = mutableListOf<Block>()
        return try {
            val arr = JSONArray(json)
            if (arr.length() == 0) return result
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val type = obj.optString("type", "")
                val content = obj.optString("content", "")
                when (type) {
                    "Text" -> if (content.isNotBlank()) result.add(Block.Text(content))
                    "Image" -> if (content.isNotBlank()) result.add(Block.Image(content))
                    "Title" -> if (content.isNotBlank()) result.add(Block.Title(content))
                }
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "parseBlocks failed", e)
            result
        }
    }
}

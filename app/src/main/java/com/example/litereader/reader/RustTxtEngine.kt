package com.example.litereader.reader

import android.util.Log
import com.example.litereader.domain.model.Block
import com.example.litereader.domain.model.Chapter
import org.json.JSONArray
import org.json.JSONObject

/**
 * Rust TXT 引擎的 JNI 桥接层。
 *
 * 由 Rust 完成编码检测（GBK / UTF-8 自动识别）与章节切分，
 * 接口与 RustEpubEngine 一致，便于 ReaderScreen 统一调用。
 * TXT 复用 EPUB 的设置面板与渲染组件（ReaderContent / ReaderSettingsPanel）。
 */
object RustTxtEngine {

    private const val TAG = "RustTxtEngine"

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

    /** 打开 TXT，返回 JSON 格式的 BookInfo。 */
    external fun openTxt(path: String): String

    /** 加载指定章节，返回 JSON 格式的 Block 列表。 */
    external fun loadTxtChapter(index: Int): String

    /** 关闭当前书籍，释放缓存。 */
    external fun closeTxt()

    // -------- 解析辅助 --------

    /** 打开书籍并解析 BookInfo。失败返回 null。 */
    fun openBookInfo(path: String): BookInfo? {
        val json = try {
            openTxt(path)
        } catch (e: Exception) {
            Log.e(TAG, "openTxt jni failed", e)
            return null
        }
        return parseBookInfo(json)
    }

    /** 解析 BookInfo JSON。 */
    fun parseBookInfo(json: String): BookInfo? {
        return try {
            val obj = JSONObject(json)
            if (obj.has("error")) {
                Log.e(TAG, "openTxt error: ${obj.getString("error")}")
                return null
            }
            val chapters = mutableListOf<ChapterInfo>()
            val arr = obj.optJSONArray("chapters") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val c = arr.getJSONObject(i)
                chapters.add(
                    ChapterInfo(
                        index = c.optInt("index", i),
                        title = c.optString("title", "第 ${i + 1} 章"),
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

    /** 加载章节并解析为 Chapter（包含 Block 列表）。 */
    fun loadChapterBlocks(index: Int, chapterTitle: String): Chapter {
        val json = try {
            loadTxtChapter(index)
        } catch (e: Exception) {
            Log.e(TAG, "loadTxtChapter jni failed", e)
            return Chapter(chapterTitle, emptyList())
        }
        val blocks = parseBlocks(json)
        if (blocks.isEmpty()) {
            return Chapter(chapterTitle, listOf(Block.Text("（本章节内容为空）")))
        }
        return Chapter(chapterTitle, blocks)
    }

    /** 解析 Block 列表 JSON。 */
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

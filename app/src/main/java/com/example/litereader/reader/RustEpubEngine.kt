package com.example.litereader.reader

import android.util.Log
import com.example.litereader.domain.model.Block
import com.example.litereader.domain.model.Chapter
import org.json.JSONArray
import org.json.JSONObject

/**
 * Rust EPUB 引擎的 JNI 桥接层。
 *
 * Kotlin 侧通过本对象调用 Rust 完成的 EPUB 解析、章节加载与图片读取。
 * 引擎状态（当前打开的书）保存在 Rust 侧的全局单例中。
 */
object RustEpubEngine {

    private const val TAG = "RustEpubEngine"

    init {
        try {
            System.loadLibrary("litereader")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "failed to load native library", e)
        }
    }

    // -------- Rust 返回的元数据 --------

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

    /** 打开 EPUB，返回 JSON 格式的 BookInfo。 */
    external fun openBook(path: String): String

    /** 返回 JSON 格式的章节列表。 */
    external fun getChapters(): String

    /** 加载指定章节，返回 JSON 格式的 Block 列表。 */
    external fun loadChapter(index: Int): String

    /** 按路径加载图片字节。 */
    external fun loadImage(path: String): ByteArray

    /** 提取封面图片字节（不依赖当前打开的书）。 */
    external fun getCover(path: String): ByteArray

    /** 关闭当前书籍，释放缓存。 */
    external fun closeBook()

    // -------- 解析辅助 --------

    /** 打开书籍并解析 BookInfo。失败返回 null。 */
    fun openBookInfo(path: String): BookInfo? {
        val json = try {
            openBook(path)
        } catch (e: Exception) {
            Log.e(TAG, "openBook jni failed", e)
            return null
        }
        return parseBookInfo(json)
    }

    /** 解析 BookInfo JSON。 */
    fun parseBookInfo(json: String): BookInfo? {
        return try {
            val obj = JSONObject(json)
            if (obj.has("error")) {
                Log.e(TAG, "openBook error: ${obj.getString("error")}")
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
            loadChapter(index)
        } catch (e: Exception) {
            Log.e(TAG, "loadChapter jni failed", e)
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
            // 可能是 {"error":"..."} 对象
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

package com.example.litereader.reader

import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * 书籍缓存清理工具。
 *
 * EPUB 的章节缓存与图片缓存已由 Rust 引擎在内存中管理（LRU），
 * 本对象仅负责在书籍被删除时清理残留的磁盘缓存目录。
 */
object BookCacheManager {

    private const val TAG = "BookCacheManager"

    /**
     * 获取某本书的缓存根目录。
     */
    fun getBookCacheDir(file: File, cacheDir: File): File {
        val key = "${file.md5()}_${file.lastModified()}"
        return File(cacheDir, "books/$key").apply { mkdirs() }
    }

    /**
     * 清理某本书的磁盘缓存（书籍删除时调用）。
     */
    fun clear(file: File, cacheDir: File) {
        try {
            getBookCacheDir(file, cacheDir).deleteRecursively()
        } catch (e: Exception) {
            Log.e(TAG, "clear cache failed", e)
        }
    }

    private fun File.md5(): String {
        return try {
            MessageDigest.getInstance("MD5").run {
                inputStream().use { input ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        update(buffer, 0, read)
                    }
                }
                digest().joinToString("") { "%02x".format(it) }
            }
        } catch (e: Exception) {
            "${name}_${length()}_${lastModified()}"
        }
    }
}

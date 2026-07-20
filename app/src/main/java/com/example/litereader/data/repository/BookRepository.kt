package com.example.litereader.data.repository

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import com.example.litereader.data.api.LibgenBookSource
import com.example.litereader.data.local.BookDao
import com.example.litereader.domain.model.Book
import com.example.litereader.reader.BookCacheManager
import com.example.litereader.reader.EpubParser
import com.example.litereader.reader.PdfParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

class BookRepository(
    private val source: LibgenBookSource,
    private val dao: BookDao,
    private val okHttpClient: OkHttpClient,
    private val context: Context
) {
    companion object {
        private const val TAG = "BookRepository"
        private const val RESUME_THRESHOLD = 4L * 1024 * 1024 // 4MB
    }

    val shelfBooks: Flow<List<Book>> = dao.getAllBooks()

    suspend fun search(query: String, page: Int): Result<List<Book>> {
        return try {
            Result.success(source.search(query, page))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 获取书籍下载目录：优先使用公共 Download/LiteReader（Android 10 及以下），
     * Android 11+ 受限，回退到应用外部文件 Documents/LiteReader。
     */
    private fun getDownloadDir(): File {
        val baseDir = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        } else {
            context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
        }
        return File(baseDir, "LiteReader").apply { mkdirs() }
    }

    suspend fun downloadBook(
        book: Book,
        cacheDir: File,
        onProgress: (Float) -> Unit
    ): Result<Book> = withContext(Dispatchers.IO) {
        try {
            val finalUrl = source.resolveDownloadUrl(book)
            // 书籍文件保存到外部存储，便于其他阅读器访问
            val downloadDir = getDownloadDir()
            val safeTitle = book.title.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val file = File(downloadDir, "${safeTitle}_${book.id}.${book.fileFormat}")

            // 先 HEAD 请求获取文件大小
            val totalSize = getFileSize(finalUrl)
            Log.d(TAG, "totalSize=$totalSize, threshold=$RESUME_THRESHOLD")

            if (totalSize > RESUME_THRESHOLD) {
                // 大文件：断点续传
                downloadWithResume(finalUrl, file, totalSize, onProgress)
            } else {
                // 小文件：直接下载
                downloadFresh(finalUrl, file, totalSize, onProgress)
            }

            val downloaded = book.copy(localPath = file.absolutePath, isDownloaded = true)
            val finalBook = when (book.fileFormat) {
                "epub" -> {
                    val coverPath = EpubParser.extractCover(file, cacheDir)
                    if (coverPath != null) downloaded.copy(coverUrl = coverPath) else downloaded
                }
                "pdf" -> {
                    val coverPath = PdfParser.extractCover(file, cacheDir)
                    if (coverPath != null) downloaded.copy(coverUrl = coverPath) else downloaded
                }
                else -> downloaded
            }
            dao.insertBook(finalBook)
            onProgress(1f)
            Result.success(finalBook)
        } catch (e: Exception) {
            Log.e(TAG, "downloadBook failed", e)
            Result.failure(e)
        }
    }

    /**
     * 获取文件总大小（HEAD 请求）
     */
    private fun getFileSize(url: String): Long {
        return try {
            val request = Request.Builder()
                .url(url)
                .head()
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                response.header("Content-Length")?.toLongOrNull() ?: -1L
            }
        } catch (e: Exception) {
            Log.e(TAG, "getFileSize failed", e)
            -1L
        }
    }

    /**
     * 普通下载：从头开始写入
     */
    private fun downloadFresh(
        url: String,
        file: File,
        total: Long,
        onProgress: (Float) -> Unit
    ) {
        val request = Request.Builder().url(url).build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty response body")

            body.byteStream().use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var downloaded = 0L
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (total > 0) {
                            onProgress((downloaded.toFloat() / total).coerceIn(0f, 0.99f))
                        }
                    }
                    output.flush()
                }
            }
        }
    }

    /**
     * 断点续传下载：检查已有部分文件，从断点继续
     */
    private fun downloadWithResume(
        url: String,
        file: File,
        total: Long,
        onProgress: (Float) -> Unit
    ) {
        val existingSize = if (file.exists()) file.length() else 0L

        // 如果已下载完整则跳过
        if (total > 0 && existingSize >= total) {
            Log.d(TAG, "file already complete, skip download")
            onProgress(0.99f)
            return
        }

        val requestBuilder = Request.Builder().url(url)

        if (existingSize > 0) {
            requestBuilder.header("Range", "bytes=$existingSize-")
            Log.d(TAG, "resuming from byte $existingSize / $total")
        }

        okHttpClient.newCall(requestBuilder.build()).execute().use { response ->
            val body = response.body ?: throw IOException("Empty response body")

            // 206 = 支持断点续传，200 = 服务器不支持，需从头下载
            val isResume = response.code == 206
            val startOffset = if (isResume) existingSize else 0L
            val effectiveTotal = if (total > 0) total else (startOffset + (body.contentLength().takeIf { it > 0 } ?: 0))

            if (!isResume && file.exists()) {
                // 服务器不支持 Range，重新下载
                file.delete()
            }

            body.byteStream().use { input ->
                java.io.FileOutputStream(file, isResume).use { output ->
                    val buffer = ByteArray(8192)
                    var downloaded = startOffset
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (effectiveTotal > 0) {
                            onProgress((downloaded.toFloat() / effectiveTotal).coerceIn(0f, 0.99f))
                        }
                    }
                    output.flush()
                }
            }
        }
    }

    /**
     * 导入本地文件：把用户选择的 Uri 文件复制到应用存储目录，
     * 检测格式（EPUB / PDF / TXT），生成 Book 记录入库。
     * EPUB / PDF 会提取封面，TXT 无封面。
     */
    suspend fun importLocalBook(uri: Uri, fileName: String): Result<Book> =
        withContext(Dispatchers.IO) {
            try {
                val format = when {
                    fileName.endsWith(".epub", ignoreCase = true) -> "epub"
                    fileName.endsWith(".pdf", ignoreCase = true) -> "pdf"
                    fileName.endsWith(".txt", ignoreCase = true) -> "txt"
                    else -> return@withContext Result.failure(
                        IllegalArgumentException("不支持的文件格式，仅支持 EPUB / PDF / TXT")
                    )
                }

                val title = fileName.substringBeforeLast('.')
                val safeTitle = title.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                val bookId = "local_${System.currentTimeMillis()}"
                val targetFile = File(getDownloadDir(), "${safeTitle}_${bookId}.${format}")

                // 复制 Uri 内容到本地文件
                context.contentResolver.openInputStream(uri)?.use { input ->
                    targetFile.outputStream().use { output -> input.copyTo(output) }
                } ?: return@withContext Result.failure(IOException("无法读取文件"))

                val book = Book(
                    id = bookId,
                    title = title,
                    author = "本地导入",
                    coverUrl = null,
                    downloadUrl = "",
                    fileFormat = format,
                    fileSize = targetFile.length().toString(),
                    source = "local",
                    localPath = targetFile.absolutePath,
                    isDownloaded = true
                )

                // EPUB / PDF 提取封面；TXT 无封面
                val finalBook = when (format) {
                    "epub" -> {
                        EpubParser.extractCover(targetFile, context.cacheDir)?.let { path ->
                            book.copy(coverUrl = path)
                        } ?: book
                    }
                    "pdf" -> {
                        PdfParser.extractCover(targetFile, context.cacheDir)?.let { path ->
                            book.copy(coverUrl = path)
                        } ?: book
                    }
                    else -> book
                }

                dao.insertBook(finalBook)
                Log.d(TAG, "imported local book: ${finalBook.title} ($format)")
                Result.success(finalBook)
            } catch (e: Exception) {
                Log.e(TAG, "importLocalBook failed", e)
                Result.failure(e)
            }
        }

    /**
     * 删除书籍：同时删除数据库记录和本地文件
     */
    suspend fun deleteBook(book: Book) = withContext(Dispatchers.IO) {
        // 删除本地文件
        book.localPath?.let { path ->
            try {
                val file = File(path)
                if (file.exists()) {
                    file.delete()
                    Log.d(TAG, "deleted file: $path")
                }
            } catch (e: Exception) {
                Log.e(TAG, "delete file failed", e)
            }
        }
        // 删除封面缓存
        book.coverUrl?.let { coverPath ->
            if (!coverPath.startsWith("http")) {
                try {
                    val coverFile = File(coverPath)
                    if (coverFile.exists()) coverFile.delete()
                } catch (e: Exception) {
                    Log.e(TAG, "delete cover failed", e)
                }
            }
        }
        // 删除章节解析缓存
        book.localPath?.let { path ->
            try {
                BookCacheManager.clear(File(path), context.cacheDir)
            } catch (e: Exception) {
                Log.e(TAG, "delete chapter cache failed", e)
            }
        }
        // 删除数据库记录
        dao.deleteBook(book)
    }

    suspend fun updateBookProgress(bookId: String, chapter: Int, offset: Int) {
        dao.getBookById(bookId)?.let { book ->
            dao.updateBook(book.copy(lastReadChapter = chapter, lastReadOffset = offset))
        }
    }

    suspend fun getBookById(id: String): Book? = dao.getBookById(id)
}

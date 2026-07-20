package com.example.litereader.data.api

import android.util.Log
import com.example.litereader.domain.model.Book
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder

class LibgenBookSource(private var baseUrl: String = "https://libgen.vg") : BookSource {

    override val name = "LibGen"

    companion object {
        /** 瞬时错误（503/空响应）的重试次数（含首次）。 */
        private const val MAX_ATTEMPTS = 3
        /** 重试之间的延迟（毫秒）。 */
        private const val RETRY_DELAY_MS = 1500L
    }

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/137.0.0.0 Safari/537.36"

    fun updateBaseUrl(url: String) {
        var cleaned = url.trim()
        if (cleaned.endsWith("/")) cleaned = cleaned.dropLast(1)
        baseUrl = cleaned
    }

    fun getBaseUrl(): String = baseUrl

    override suspend fun search(
        query: String,
        page: Int
    ): List<Book> = withContext(Dispatchers.IO) {
        try {
            val url = buildSearchUrl(query, page)
            //Log.d("LibGen", "URL=$url")

            val doc = fetchDocument(url)

            //Log.d("LibGen", "title=${doc.title()}")

            val result = mutableListOf<Book>()
            val tables = doc.select("table")

            for (table in tables) {
                val rows = table.select("tr")
                for (row in rows) {
                    val book = parseRow(row)
                    if (book != null) {
                        result.add(book)
                    }
                }
            }

            Log.d("LibGen", "found=${result.size}")
            result
        } catch (e: Exception) {
            Log.e("LibGen", "search failed", e)
            emptyList()
        }
    }

    override suspend fun resolveDownloadUrl(book: Book): String = withContext(Dispatchers.IO) {
        val editionUrl = book.downloadUrl
        //Log.d("LibGen", "resolveDownloadUrl: edition=$editionUrl")

        // 1. 取 edition 页（带重试）
        val editionDoc = try {
            fetchDocumentWithRetry(editionUrl)
        } catch (e: Exception) {
            Log.e("LibGen", "resolveDownloadUrl: edition page failed", e)
            return@withContext editionUrl
        }

        // 2. 找 ads 链接
        val adsLink = editionDoc.select("a[href*=ads.php]").firstOrNull()
            ?: editionDoc.select("a[href*=md5]").firstOrNull()
            ?: findAdsLinkByMirrorText(editionDoc)

        if (adsLink == null) {
            //Log.w("LibGen", "resolveDownloadUrl: no ads link in edition page")
            return@withContext editionUrl
        }

        val adsUrl = adsLink.attr("abs:href")
        if (adsUrl.isBlank()) {
            Log.w("LibGen", "resolveDownloadUrl: ads link has empty href")
            return@withContext editionUrl
        }
        //Log.d("LibGen", "resolveDownloadUrl: ads=$adsUrl")

        // 3. 取 ads 页（带重试）
        val adsDoc = try {
            fetchDocumentWithRetry(adsUrl)
        } catch (e: Exception) {
            Log.e("LibGen", "resolveDownloadUrl: ads page failed", e)
            return@withContext adsUrl
        }

        // 4. 找最终下载链接
        val downloadLink = adsDoc.select("a[href*=get.php]").firstOrNull()
            ?.attr("abs:href")

        val finalDownloadUrl = if (downloadLink.isNullOrEmpty()) {
            val textLink = adsDoc.select("a").firstOrNull { a ->
                val text = a.text().trim().lowercase()
                text == "get" || text.contains("download") || text.contains("get this")
            }?.attr("abs:href")

            if (textLink.isNullOrEmpty()) {
                val fileLink = adsDoc.select("a[href*=/download/], a[href*=.epub], a[href*=.pdf], a[href*=.mobi], a[href*=.txt]").firstOrNull()
                fileLink?.attr("abs:href")
            } else {
                textLink
            }
        } else {
            downloadLink
        }

        //Log.d("LibGen", "resolveDownloadUrl: download=$finalDownloadUrl")
        finalDownloadUrl ?: adsUrl
    }

    /**
     * 抓取文档（单次尝试）。统一配置浏览器伪装头，避免被识别为 bot。
     */
    private fun fetchDocument(url: String): org.jsoup.nodes.Document {
        return Jsoup.connect(url)
            .userAgent(userAgent)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9,zh-CN;q=0.8,zh;q=0.7")
            .header("Accept-Encoding", "gzip, deflate, br")
            .header("Connection", "keep-alive")
            .header("Upgrade-Insecure-Requests", "1")
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", "none")
            .header("Sec-Fetch-User", "?1")
            .header("Cache-Control", "max-age=0")
            .timeout(30000)
            .followRedirects(true)
            .ignoreHttpErrors(false)
            .get()
    }

    /**
     * 抓取文档并对瞬时错误（503 / 空响应 /  socket timeout）做有限重试。
     */
    private suspend fun fetchDocumentWithRetry(url: String): org.jsoup.nodes.Document {
        var lastError: Exception? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                return fetchDocument(url)
            } catch (e: org.jsoup.HttpStatusException) {
                // 503 / 502 / 504 通常为服务端瞬时过载，可重试
                if (e.statusCode in 500..599) {
                    Log.w("LibGen", "HTTP ${e.statusCode} at $url (attempt ${attempt + 1}/$MAX_ATTEMPTS)")
                    lastError = e
                } else {
                    throw e
                }
            } catch (e: java.io.IOException) {
                // 空响应、超时等也属于可重试的瞬时错误
                Log.w("LibGen", "IO error at $url (attempt ${attempt + 1}/$MAX_ATTEMPTS): ${e.message}")
                lastError = e
            }
            if (attempt < MAX_ATTEMPTS - 1) {
                delay(RETRY_DELAY_MS)
            }
        }
        throw lastError ?: java.io.IOException("retry exhausted for $url")
    }

    private fun findAdsLinkByMirrorText(editionDoc: org.jsoup.nodes.Document): Element? {
        return editionDoc.select("a").firstOrNull { a ->
            val text = a.text().trim().lowercase()
            text.contains("libgen") && !text.contains("tor")
        } ?: editionDoc.select("a").firstOrNull { a ->
            val href = a.attr("abs:href").lowercase()
            href.contains("ads.php") || href.contains("md5=")
        }
    }

    private fun buildSearchUrl(query: String, page: Int): String {
        val q = URLEncoder.encode(query, "UTF-8")
        return buildString {
            append(baseUrl)
            append("/index.php?")
            append("req=$q")
            listOf("t", "a", "s", "y", "p", "i").forEach {
                append("&columns[]=$it")
            }
            listOf("f", "e", "s", "a", "p", "w").forEach {
                append("&objects[]=$it")
            }
            listOf("l", "c", "f", "a", "m", "r", "s").forEach {
                append("&topics[]=$it")
            }
            append("&res=25")
            append("&filesuns=all")
            if (page > 1) {
                append("&page=$page")
            }
        }
    }

    private fun parseRow(row: Element): Book? {
        val cells = row.select("td")
        if (cells.size < 5) return null

        var title = ""
        var author = ""
        var format = ""
        var size = ""
        var link = ""

        for (cell in cells) {
            val text = cell.text().trim()
            val a = cell.selectFirst("a")

            if (title.isEmpty() && a != null && text.length > 2) {
                title = text
                link = a.attr("abs:href")
            }

            if (author.isEmpty() && a != null && title.isNotEmpty() && text != title) {
                val href = a.attr("abs:href")
                if (href.contains("author") || href.contains("search") && !href.contains("edition")) {
                    author = text
                }
            }

            if (text.matches(Regex(".*\\b(pdf|epub|mobi|azw3|txt|fb2)\\b.*", RegexOption.IGNORE_CASE))) {
                val match = Regex("\\b(pdf|epub|mobi|azw3|txt|fb2)\\b", RegexOption.IGNORE_CASE).find(text)
                format = match?.value?.lowercase() ?: "unknown"
            }

            if (text.matches(Regex(".*(KB|MB|GB).*", RegexOption.IGNORE_CASE))) {
                size = text
            }
        }

        if (title.isBlank()) return null
        if (format.isBlank()) format = "unknown"

        return Book(
            id = "libgen_${title.hashCode()}",
            title = title,
            author = author.ifBlank { "佚名" },
            coverUrl = null,
            downloadUrl = link,
            fileFormat = format,
            fileSize = size.ifBlank { null },
            source = name
        )
    }
}

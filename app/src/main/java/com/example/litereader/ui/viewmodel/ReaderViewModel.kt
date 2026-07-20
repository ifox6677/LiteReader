package com.example.litereader.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.litereader.data.local.BookDatabase
import com.example.litereader.data.local.ReaderSettingsDataStore
import com.example.litereader.domain.model.Book
import com.example.litereader.domain.model.Chapter
import com.example.litereader.reader.EpubParser
import com.example.litereader.reader.PdfParser
import com.example.litereader.reader.TxtParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 阅读器 UI 状态。所有阅读相关状态集中于此，Composable 只负责渲染。
 */
data class ReaderUiState(
    val book: Book? = null,
    val metadataList: List<EpubParser.ChapterMetadata> = emptyList(),
    val currentChapterIdx: Int = 0,
    val currentChapter: Chapter? = null,
    val isLoading: Boolean = false,
    val loadError: String? = null,
    val bookFormat: String = "epub",  // "epub" / "pdf" / "txt"
    val isAutoScrolling: Boolean = false,
    val isBookReady: Boolean = false  // 元数据就绪，可渲染
)

/**
 * 阅读器 ViewModel。
 *
 * 职责：
 * - 加载书籍元数据与章节内容
 * - 管理当前章节索引与切换
 * - 持久化阅读进度
 * - 暴露阅读偏好（字号/主题/行距/亮度/自滚/PDF 设置）
 *
 * 设计：
 * - ReaderUiState 为单一数据源，Composable 通过 collectAsState 订阅
 * - 章节加载支持取消（loadJob），快速切换章节时取消旧加载
 * - 阅读偏好通过 ReaderSettingsDataStore 持久化，与 BookViewModel 共享同一 DataStore
 */
class ReaderViewModel(application: Application) : AndroidViewModel(application) {

    private val bookDao = BookDatabase.getInstance(application).bookDao()
    private val settings = ReaderSettingsDataStore(application)

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    // 当前章节加载任务，切换章节时取消上一个
    private var loadJob: Job? = null

    // -------- 阅读偏好（与 BookViewModel 共享同一 DataStore） --------

    val fontSize = settings.fontSize
    val themeMode = settings.themeMode
    val lineSpacing = settings.lineSpacing
    val brightness = settings.brightness
    val autoScrollSpeed = settings.autoScrollSpeed
    val pdfZoom = settings.pdfZoom
    val pdfOrientation = settings.pdfOrientation

    // -------- 书籍加载 --------

    /**
     * 加载书籍：读取文件、识别格式、解析元数据、设置初始章节。
     * PDF 模式仅设置元数据（由 PdfContinuousContent 渲染），
     * EPUB/TXT 模式还会加载首章内容。
     */
    fun loadBook(bookId: String) {
        viewModelScope.launch {
            try {
                val dbBook = bookDao.getBookById(bookId)
                if (dbBook == null || dbBook.localPath == null) {
                    _uiState.value = _uiState.value.copy(
                        loadError = "未找到书籍",
                        isBookReady = false
                    )
                    return@launch
                }
                val file = File(dbBook.localPath)
                if (!file.exists()) {
                    _uiState.value = _uiState.value.copy(
                        loadError = "文件不存在：${file.absolutePath}",
                        isBookReady = false
                    )
                    return@launch
                }

                val realFormat = detectFileType(file)
                if (realFormat != "epub" && realFormat != "pdf" && realFormat != "txt") {
                    _uiState.value = _uiState.value.copy(
                        loadError = "仅支持 EPUB / PDF / TXT 格式",
                        isBookReady = false
                    )
                    return@launch
                }

                val meta = when (realFormat) {
                    "pdf" -> PdfParser.parseMetadata(file).map {
                        EpubParser.ChapterMetadata(it.index, it.title, it.path)
                    }
                    "txt" -> TxtParser.parseMetadata(file).map {
                        EpubParser.ChapterMetadata(it.index, it.title, it.path)
                    }
                    else -> EpubParser.parseMetadata(file)
                }

                if (meta.isEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        loadError = "书籍解析失败，可能格式损坏",
                        isBookReady = false
                    )
                    return@launch
                }

                val initialIdx = dbBook.lastReadChapter.coerceIn(0, meta.size - 1)
                _uiState.value = _uiState.value.copy(
                    book = dbBook,
                    metadataList = meta,
                    currentChapterIdx = initialIdx,
                    bookFormat = realFormat,
                    loadError = null,
                    isBookReady = true
                )

                // PDF 由 PdfContinuousContent 直接渲染所有页，无需加载单章
                if (realFormat != "pdf") {
                    loadChapter(initialIdx)
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    loadError = "加载书籍失败：${e.message}",
                    isBookReady = false
                )
            }
        }
    }

    /**
     * 加载章节内容。取消上一个加载任务，避免快速切换时旧加载覆盖新章节。
     */
    fun loadChapter(index: Int) {
        val state = _uiState.value
        if (index !in state.metadataList.indices) return

        loadJob?.cancel()
        _uiState.value = state.copy(isLoading = true)
        val meta = state.metadataList[index]
        val format = state.bookFormat

        loadJob = viewModelScope.launch {
            try {
                val chapter = when (format) {
                    "pdf" -> PdfParser.loadChapterContent(meta.index, meta.title)
                    "txt" -> TxtParser.loadChapterContent(meta.index, meta.title)
                    else -> EpubParser.loadChapterContent(meta.index, meta.title)
                }
                _uiState.value = _uiState.value.copy(
                    currentChapter = chapter,
                    isLoading = false
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    loadError = "章节加载失败：${e.message}",
                    isLoading = false
                )
            }
        }
    }

    /**
     * 切换章节索引。仅更新索引，由 LaunchedEffect 监听后触发 loadChapter。
     * 这样 UI 立即响应（高亮变化），加载异步进行。
     */
    fun setChapterIndex(index: Int) {
        val state = _uiState.value
        if (index !in state.metadataList.indices) return
        if (index == state.currentChapterIdx) return
        _uiState.value = state.copy(currentChapterIdx = index)
    }

    /**
     * 保存阅读进度（章节索引 + 滚动偏移）。
     */
    fun saveProgress(bookId: String, chapter: Int, offset: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val book = bookDao.getBookById(bookId) ?: return@launch
            bookDao.updateBook(book.copy(lastReadChapter = chapter, lastReadOffset = offset))
        }
    }

    /**
     * 切换自动滚动开关。
     */
    fun toggleAutoScroll() {
        _uiState.value = _uiState.value.copy(isAutoScrolling = !_uiState.value.isAutoScrolling)
    }

    /**
     * 设置自动滚动开关。
     */
    fun setAutoScrolling(value: Boolean) {
        _uiState.value = _uiState.value.copy(isAutoScrolling = value)
    }

    // -------- 阅读偏好持久化 --------

    fun saveFontSize(size: Float) = viewModelScope.launch { settings.saveFontSize(size) }
    fun saveThemeMode(mode: Int) = viewModelScope.launch { settings.saveThemeMode(mode) }
    fun saveLineSpacing(spacing: Float) = viewModelScope.launch { settings.saveLineSpacing(spacing) }
    fun saveBrightness(value: Float) = viewModelScope.launch { settings.saveBrightness(value) }
    fun saveAutoScrollSpeed(speed: Float) = viewModelScope.launch { settings.saveAutoScrollSpeed(speed) }
    fun savePdfZoom(zoom: Float) = viewModelScope.launch { settings.savePdfZoom(zoom) }
    fun savePdfOrientation(orientation: Int) = viewModelScope.launch { settings.savePdfOrientation(orientation) }

    /**
     * 关闭书籍，释放 Rust 侧资源。
     */
    fun closeBook() {
        val format = _uiState.value.bookFormat
        when (format) {
            "pdf" -> PdfParser.closeBook()
            "txt" -> TxtParser.closeBook()
            else -> EpubParser.closeBook()
        }
    }

    override fun onCleared() {
        super.onCleared()
        closeBook()
    }

    // -------- 文件类型识别 --------

    private fun detectFileType(file: File): String {
        return try {
            val header = ByteArray(5)
            file.inputStream().use { it.read(header) }
            when {
                header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() -> "epub"
                header[0] == 0x25.toByte() && header[1] == 0x50.toByte() -> "pdf"
                else -> "txt"
            }
        } catch (e: Exception) {
            "txt"
        }
    }
}

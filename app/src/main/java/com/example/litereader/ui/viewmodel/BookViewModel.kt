package com.example.litereader.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.litereader.data.api.LibgenBookSource
import com.example.litereader.data.local.BookDatabase
import com.example.litereader.data.local.ReaderSettingsDataStore
import com.example.litereader.data.repository.BookRepository
import com.example.litereader.domain.model.Book
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

class BookViewModel(application: Application) : AndroidViewModel(application) {

    private val db = BookDatabase.getInstance(application)

    private val okHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val libgenSource = LibgenBookSource()
    val repository = BookRepository(libgenSource, db.bookDao(), okHttpClient, application)
    private val settings = ReaderSettingsDataStore(application)

    // UI 状态
    val shelfBooks = repository.shelfBooks
    private val _searchResults = MutableStateFlow<List<Book>>(emptyList())
    val searchResults: StateFlow<List<Book>> = _searchResults
    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing
    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting
    val downloadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())

    // 阅读器偏好
    val fontSize = settings.fontSize
    val themeMode = settings.themeMode
    val lineSpacing = settings.lineSpacing
    val brightness = settings.brightness
    val libgenUrl = settings.libgenUrl
    val autoScrollSpeed = settings.autoScrollSpeed
    val pdfZoom = settings.pdfZoom
    val pdfOrientation = settings.pdfOrientation

    private var currentQuery = ""
    private var currentPage = 1
    private var hasMore = true

    init {
        viewModelScope.launch {
            settings.libgenUrl.collect { url ->
                libgenSource.updateBaseUrl(url)
            }
        }
    }

    /**
     * 搜索书籍。refresh=true 时重置结果并从第一页开始。
     */
    fun search(query: String, refresh: Boolean = false) {
        if (_isSearching.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _isSearching.value = true
            if (refresh) {
                _isRefreshing.value = true
                currentQuery = query
                currentPage = 1
                hasMore = true
                _searchResults.value = emptyList()
            }

            try {
                val result = repository.search(currentQuery, currentPage)
                result.onSuccess { list ->
                    _searchResults.value = _searchResults.value + list
                    hasMore = list.isNotEmpty()
                    if (list.isNotEmpty()) currentPage++
                }.onFailure { e ->
                    e.printStackTrace()
                }
            } finally {
                _isSearching.value = false
                _isRefreshing.value = false
            }
        }
    }

    fun loadMore() {
        if (currentQuery.isBlank() || !hasMore || _isSearching.value) return
        search(currentQuery, refresh = false)
    }

    fun downloadBook(book: Book) {
        viewModelScope.launch(Dispatchers.IO) {
            downloadProgress.value = downloadProgress.value + (book.id to 0.1f)
            repository.downloadBook(book, getApplication<Application>().cacheDir) { progress ->
                downloadProgress.value = downloadProgress.value + (book.id to progress)
            }
            downloadProgress.value = downloadProgress.value - book.id
        }
    }

    fun deleteBook(book: Book) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteBook(book)
        }
    }

    /**
     * 导入本地文件。完成或失败后通过 onResult 回调通知 UI。
     */
    fun importLocalBook(uri: Uri, fileName: String, onResult: (Result<Book>) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            _isImporting.value = true
            try {
                val result = repository.importLocalBook(uri, fileName)
                onResult(result)
            } finally {
                _isImporting.value = false
            }
        }
    }

    fun updateReadingProgress(bookId: String, chapter: Int, offset: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateBookProgress(bookId, chapter, offset)
        }
    }

    fun updateLibgenUrl(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            settings.saveLibgenUrl(url)
        }
    }

    fun saveFontSize(size: Float) = viewModelScope.launch { settings.saveFontSize(size) }
    fun saveThemeMode(mode: Int) = viewModelScope.launch { settings.saveThemeMode(mode) }
    fun saveLineSpacing(spacing: Float) = viewModelScope.launch { settings.saveLineSpacing(spacing) }
    fun saveBrightness(value: Float) = viewModelScope.launch { settings.saveBrightness(value) }
    fun saveAutoScrollSpeed(speed: Float) = viewModelScope.launch { settings.saveAutoScrollSpeed(speed) }
    fun savePdfZoom(zoom: Float) = viewModelScope.launch { settings.savePdfZoom(zoom) }
    fun savePdfOrientation(orientation: Int) = viewModelScope.launch { settings.savePdfOrientation(orientation) }
}

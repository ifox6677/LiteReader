package com.example.litereader.ui.screen

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.litereader.domain.model.Book
import com.example.litereader.ui.component.BookCover
import com.example.litereader.ui.viewmodel.BookViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    viewModel: BookViewModel,
    onNavigateToSearch: (String) -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToReader: (String) -> Unit
) {
    val shelfBooks by viewModel.shelfBooks.collectAsState(initial = emptyList())
    val isImporting by viewModel.isImporting.collectAsState()
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var bookToDelete by remember { mutableStateOf<Book?>(null) }
    var importMessage by remember { mutableStateOf<String?>(null) }

    // 本地文件选择器：选择 EPUB / PDF / TXT 文件
    val pickFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val fileName = queryFileName(context, uri)
        if (fileName.isNullOrBlank()) {
            importMessage = "无法获取文件名"
            return@rememberLauncherForActivityResult
        }
        // 保留读取权限（复制过程在 IO 线程，立即读取无需持久权限，但加上更稳妥）
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // 部分 picker 不支持持久化权限，忽略即可（导入流程会立即读完）
        }
        viewModel.importLocalBook(uri, fileName) { result ->
            result.onSuccess {
                importMessage = "已导入《${it.title}》"
            }.onFailure { e ->
                importMessage = e.message ?: "导入失败"
            }
        }
    }

    fun launchPicker() {
        pickFileLauncher.launch(
            arrayOf(
                "application/epub+zip",
                "application/pdf",
                "text/plain",
                "application/octet-stream"
            )
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("我的书架") },
                actions = {
                    IconButton(onClick = ::launchPicker, enabled = !isImporting) {
                        Icon(Icons.Default.Add, contentDescription = "导入本地文件")
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(modifier = Modifier.padding(paddingValues)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("搜索书名/作者") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                trailingIcon = {
                    TextButton(onClick = { onNavigateToSearch(query) }) {
                        Text("搜索")
                    }
                }
            )

            if (shelfBooks.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "书架空空如也，快去搜索下载图书或导入本地文件吧~",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(shelfBooks, key = { it.id }) { book ->
                        BookItem(
                            book = book,
                            onClick = { onNavigateToReader(book.id) },
                            onLongClick = { bookToDelete = book }
                        )
                    }
                }
            }
        }

        // 导入中蒙层
        if (isImporting) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    elevation = CardDefaults.cardElevation(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("正在导入…")
                    }
                }
            }
        }
    }

    // 长按删除确认弹窗
    bookToDelete?.let { book ->
        AlertDialog(
            onDismissRequest = { bookToDelete = null },
            title = { Text("删除书籍") },
            text = { Text("确定要删除《${book.title}》吗？本地文件也会一并删除。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteBook(book)
                        bookToDelete = null
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { bookToDelete = null }) {
                    Text("取消")
                }
            }
        )
    }

    // 导入结果提示
    importMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { importMessage = null },
            title = { Text("导入结果") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { importMessage = null }) {
                    Text("确定")
                }
            }
        )
    }
}

/**
 * 从 Uri 查询文件显示名（DISPLAY_NAME）。
 * 支持 content:// 与 file:// 两种 scheme。
 */
private fun queryFileName(context: Context, uri: Uri): String? {
    if (uri.scheme == "file") {
        return uri.lastPathSegment
    }
    if (uri.scheme != "content") return null
    return try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) cursor.getString(idx) else null
            } else null
        }
    } catch (e: Exception) {
        null
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookItem(book: Book, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(
        modifier = Modifier
            .padding(8.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .fillMaxWidth()
    ) {
        Card(
            modifier = Modifier
                .height(140.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp)),
            elevation = CardDefaults.cardElevation(4.dp)
        ) {
            BookCover(
                title = book.title,
                coverUrl = book.coverUrl,
                modifier = Modifier.fillMaxSize()
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = book.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

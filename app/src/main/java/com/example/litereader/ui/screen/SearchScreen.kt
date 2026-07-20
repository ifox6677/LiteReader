package com.example.litereader.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.example.litereader.domain.model.Book
import com.example.litereader.ui.component.BookCover
import com.example.litereader.ui.viewmodel.BookViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: BookViewModel,
    onBack: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val results by viewModel.searchResults.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val progressMap by viewModel.downloadProgress.collectAsState()
    val savedUrl by viewModel.libgenUrl.collectAsState(initial = "https://libgen.la")
    var urlInput by remember { mutableStateOf("") }
    var showUrlInput by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("LibGen 搜索") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showUrlInput = !showUrlInput }) {
                        Text("域名", style = MaterialTheme.typography.labelSmall)
                    }
                    IconButton(
                        onClick = { if (query.isNotBlank()) viewModel.search(query, refresh = true) },
                        enabled = !isRefreshing
                    ) {
                        if (isRefreshing) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新")
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
        ) {
            // 域名输入区
            if (showUrlInput) {
                Surface(tonalElevation = 4.dp) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "LibGen 域名（不稳定时可切换镜像）",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "当前: $savedUrl",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = urlInput,
                                onValueChange = { urlInput = it },
                                placeholder = { Text("https://libgen.is") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(onClick = {
                                if (urlInput.isNotBlank()) {
                                    viewModel.updateLibgenUrl(urlInput.trim())
                                    urlInput = ""
                                }
                            }) {
                                Text("保存")
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "常用: libgen.la / libgen.is / libgen.rs / libgen.st",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }

            // 搜索栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("输入书名/作者搜索") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = { viewModel.search(query, refresh = true) }) {
                    Text("搜索")
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                if (isSearching && results.isEmpty()) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                } else if (results.isEmpty()) {
                    Text(
                        "暂无搜索结果，请输入关键词搜索",
                        modifier = Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 80.dp)
                    ) {
                        items(results, key = { it.id }) { book ->
                            SearchResultItem(
                                book = book,
                                isDownloading = progressMap.containsKey(book.id),
                                progress = progressMap[book.id] ?: 0f,
                                onDownload = { viewModel.downloadBook(book) }
                            )
                            HorizontalDivider()
                        }
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Button(onClick = { viewModel.loadMore() }) {
                                    Text("加载更多")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SearchResultItem(
    book: Book,
    isDownloading: Boolean,
    progress: Float,
    onDownload: () -> Unit
) {
    ListItem(
        leadingContent = {
            BookCover(
                title = book.title,
                coverUrl = book.coverUrl,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(4.dp))
            )
        },
        headlineContent = { Text(book.title, maxLines = 2) },
        supportingContent = {
            Text(
                "${book.author} · ${book.fileFormat.uppercase()}" +
                        (book.fileSize?.let { " · $it" } ?: "")
            )
        },
        trailingContent = {
            if (isDownloading) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.size(32.dp),
                        strokeWidth = 3.dp
                    )
                    Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                }
            } else {
                TextButton(onClick = onDownload) {
                    Text("下载")
                }
            }
        }
    )
}

package com.example.litereader.reader

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.litereader.domain.model.Block
import com.example.litereader.domain.model.Chapter
import com.example.litereader.ui.viewmodel.ReaderViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    bookId: String,
    readerViewModel: ReaderViewModel,
    onBack: () -> Unit
) {
    // -------- 阅读器状态（来自 ReaderViewModel） --------
    val state by readerViewModel.uiState.collectAsState()
    val fontSize by readerViewModel.fontSize.collectAsState(initial = 18f)
    val themeMode by readerViewModel.themeMode.collectAsState(initial = 2)
    val lineSpacing by readerViewModel.lineSpacing.collectAsState(initial = 1.5f)
    val brightness by readerViewModel.brightness.collectAsState(initial = 1f)
    val autoScrollSpeed by readerViewModel.autoScrollSpeed.collectAsState(initial = 0f)
    val pdfZoom by readerViewModel.pdfZoom.collectAsState(initial = 0.8f)
    val pdfOrientation by readerViewModel.pdfOrientation.collectAsState(initial = 0)

    // -------- UI-only 状态（留在 Composable） --------
    var showSettings by remember { mutableStateOf(false) }
    var showChaptersSheet by remember { mutableStateOf(false) }
    var topBarVisible by remember { mutableStateOf(true) }
    var lastScrollPosition by remember { mutableStateOf(0L) }
    var accumulatedDragX by remember { mutableStateOf(0f) }

    val scope = rememberCoroutineScope()
    val scrollState = rememberLazyListState()
    val context = LocalContext.current
    val metrics = context.resources.displayMetrics
    val screenWidth = metrics.widthPixels
    var contentWidthPx by remember { mutableStateOf(screenWidth) }

    // -------- 加载书籍 --------
    LaunchedEffect(bookId) {
        readerViewModel.loadBook(bookId)
    }

    // 窗口亮度
    LaunchedEffect(brightness) {
        context.findActivity()?.window?.let { w ->
            val attrs = w.attributes
            attrs.screenBrightness = brightness
            w.attributes = attrs
        }
    }

    // PDF 横竖屏控制
    LaunchedEffect(state.bookFormat, pdfOrientation) {
        val activity = context.findActivity()
        activity?.requestedOrientation = if (state.bookFormat == "pdf") {
            when (pdfOrientation) {
                1 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                2 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        } else {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // 切换章节：加载新章节 + 重置滚动 + 保存进度
    // ⚠️ 不再监听"滚到底部自动下一章"——换章固定通过左右滑动或目录导航
    LaunchedEffect(state.currentChapterIdx) {
        if (state.metadataList.isEmpty()) return@LaunchedEffect
        state.book?.let {
            readerViewModel.saveProgress(
                it.id,
                state.currentChapterIdx,
                if (state.bookFormat == "pdf") 0 else scrollState.firstVisibleItemIndex
            )
        }
        if (state.bookFormat == "pdf") return@LaunchedEffect
        if (state.currentChapterIdx !in state.metadataList.indices) return@LaunchedEffect
        readerViewModel.loadChapter(state.currentChapterIdx)
        scrollState.scrollToItem(0)
    }

    // 保存 EPUB 滚动进度（item 内偏移变化）
    LaunchedEffect(scrollState.firstVisibleItemScrollOffset, scrollState.firstVisibleItemIndex) {
        if (state.bookFormat == "pdf") return@LaunchedEffect
        state.book?.let {
            readerViewModel.saveProgress(
                it.id,
                state.currentChapterIdx,
                scrollState.firstVisibleItemIndex
            )
        }
    }

    // 自动滚动（仅 EPUB/TXT）——到底即停，不再自动跳章
    LaunchedEffect(state.isAutoScrolling, autoScrollSpeed, state.currentChapterIdx) {
        if (state.bookFormat == "pdf") return@LaunchedEffect
        if (!state.isAutoScrolling || autoScrollSpeed <= 0f) return@LaunchedEffect
        while (true) {
            delay(16)
            val scrolled = scrollState.scrollBy(autoScrollSpeed * 0.6f)
            if (scrolled == 0f) {
                readerViewModel.setAutoScrolling(false)
                break
            }
        }
    }

    // 顶部栏自动隐藏（向下滚隐藏，向上滚显示）
    LaunchedEffect(Unit) {
        snapshotFlow {
            scrollState.firstVisibleItemIndex.toLong() * 100000000L +
                    scrollState.firstVisibleItemScrollOffset
        }.collect { current ->
            if (current > lastScrollPosition + 20 && topBarVisible) {
                topBarVisible = false
            } else if (current < lastScrollPosition - 20 && !topBarVisible) {
                topBarVisible = true
            }
            lastScrollPosition = current
        }
    }

    // -------- 错误 / 加载中检查 --------

    if (state.loadError != null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(state.loadError!!, color = MaterialTheme.colorScheme.error)
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = onBack) { Text("返回") }
            }
        }
        return
    }

    // 加载中：PDF 只要 metadataList 就绪即可；EPUB/TXT 还需 currentChapter
    if (!state.isBookReady || (state.bookFormat != "pdf" && state.currentChapter == null)) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val sheetState = rememberModalBottomSheetState()

    val (textColor, bgColor) = when (themeMode) {
        0 -> MaterialTheme.colorScheme.onBackground to MaterialTheme.colorScheme.background
        1 -> Color(0xFF3E4A3A) to Color(0xFFE8E6D0)
        2 -> Color(0xFFCCCCCC) to Color(0xFF1A1A1A)
        else -> Color(0xFFFFFFFF) to Color(0xFF000000)  // 纯黑白
    }

    Scaffold(
        containerColor = bgColor,
        topBar = {
            AnimatedVisibility(
                visible = topBarVisible,
                enter = slideInVertically { -it } + fadeIn(),
                exit = slideOutVertically { -it } + fadeOut()
            ) {
                TopAppBar(
                    title = { Text(state.book?.title ?: "", maxLines = 1) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    actions = {
                        if (state.bookFormat != "pdf") {
                        IconButton(onClick = {
                            if (!state.isAutoScrolling && autoScrollSpeed <= 0f) {
                                readerViewModel.saveAutoScrollSpeed(3f)
                            }
                            readerViewModel.toggleAutoScroll()
                        }) {
                            if (state.isAutoScrolling) {
                                PauseIcon(contentDescription = "暂停自动滚动")
                            } else {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = "自动滚动"
                                )
                            }
                        }
                        }
                        IconButton(onClick = { showChaptersSheet = true }) {
                            Icon(Icons.Default.List, contentDescription = "目录")
                        }
                    }
                )
            }
        }
    ) { paddingValues ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .pointerInput(state.bookFormat) {
                    // PDF 由 PdfContinuousContent 内部统一处理 tap/long press/double tap
                    if (state.bookFormat == "pdf") return@pointerInput
                    detectTapGestures(
                        onTap = { topBarVisible = !topBarVisible },
                        onLongPress = { showSettings = true }
                    )
                }
                .pointerInput(state.bookFormat) {
                    // PDF 由 PdfContinuousContent 内部处理缩放/双击，这里只处理 EPUB 翻页
                    if (state.bookFormat == "pdf") return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { accumulatedDragX = 0f },
                        onHorizontalDrag = { _, dragAmount ->
                            accumulatedDragX += dragAmount
                        },
                        onDragEnd = {
                            val threshold = 80f
                            when {
                                accumulatedDragX > threshold -> {
                                    readerViewModel.setChapterIndex(state.currentChapterIdx - 1)
                                }
                                accumulatedDragX < -threshold -> {
                                    readerViewModel.setChapterIndex(state.currentChapterIdx + 1)
                                }
                            }
                            accumulatedDragX = 0f
                        },
                        onDragCancel = { accumulatedDragX = 0f }
                    )
                }
        ) {
            val density = LocalDensity.current.density
            contentWidthPx = (maxWidth.value * density).toInt()
            val textContentWidth = (contentWidthPx - 36.dp.value * density).toInt()
            val contentHeightPx = (maxHeight.value * density).toInt()
            val isLandscape = maxWidth > maxHeight

            if (state.bookFormat == "pdf") {
                PdfContinuousContent(
                    metadataList = state.metadataList,
                    initialPageIndex = state.currentChapterIdx,
                    screenWidth = contentWidthPx,
                    screenHeight = contentHeightPx,
                    isLandscape = isLandscape,
                    baseZoom = pdfZoom,
                    onPageIndexChange = { newIdx ->
                        readerViewModel.setChapterIndex(newIdx)
                    },
                    onUserScroll = {
                        // 横屏滚动时自动隐藏顶栏（单击可重新显示）
                        if (isLandscape && topBarVisible) topBarVisible = false
                    },
                    onTap = { topBarVisible = !topBarVisible },
                    onLongPress = { showSettings = true }
                )
            } else {
                ReaderContent(
                    chapter = state.currentChapter!!,
                    fontSize = fontSize,
                    lineSpacing = lineSpacing,
                    scrollState = scrollState,
                    textColor = textColor,
                    bgColor = bgColor,
                    contentWidth = textContentWidth
                )
            }
        }
    }

    // 设置面板
    if (showSettings) {
        Dialog(onDismissRequest = { showSettings = false }) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 8.dp,
                color = MaterialTheme.colorScheme.surface
            ) {
                if (state.bookFormat == "pdf") {
                    PdfSettingsPanel(
                        chapters = state.metadataList.map { it.title },
                        currentChapterIdx = state.currentChapterIdx,
                        pdfZoom = pdfZoom,
                        pdfOrientation = pdfOrientation,
                        brightness = brightness,
                        onChapterChange = { readerViewModel.setChapterIndex(it) },
                        onZoomChange = { readerViewModel.savePdfZoom(it) },
                        onOrientationChange = { readerViewModel.savePdfOrientation(it) },
                        onBrightnessChange = { readerViewModel.saveBrightness(it) },
                        onDismiss = { showSettings = false }
                    )
                } else {
                    ReaderSettingsPanel(
                        chapters = state.metadataList.map { it.title },
                        currentChapterIdx = state.currentChapterIdx,
                        fontSize = fontSize,
                        themeMode = themeMode,
                        lineSpacing = lineSpacing,
                        brightness = brightness,
                        autoScrollSpeed = autoScrollSpeed,
                        onChapterChange = { readerViewModel.setChapterIndex(it) },
                        onFontSizeChange = { readerViewModel.saveFontSize(it) },
                        onThemeChange = { readerViewModel.saveThemeMode(it) },
                        onLineSpacingChange = { readerViewModel.saveLineSpacing(it) },
                        onBrightnessChange = { readerViewModel.saveBrightness(it) },
                        onAutoScrollSpeedChange = { readerViewModel.saveAutoScrollSpeed(it) },
                        onDismiss = { showSettings = false }
                    )
                }
            }
        }
    }

    // 目录
    if (showChaptersSheet) {
        ModalBottomSheet(
            onDismissRequest = { showChaptersSheet = false },
            sheetState = sheetState
        ) {
            Text("本书目录", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
            HorizontalDivider()
            LazyColumn {
                items(state.metadataList.size) { index ->
                    val isCurrent = index == state.currentChapterIdx
                    ListItem(
                        headlineContent = {
                            Text(
                                state.metadataList[index].title,
                                maxLines = 1,
                                color = if (isCurrent) MaterialTheme.colorScheme.primary else LocalContentColor.current
                            )
                        },
                        modifier = Modifier.clickable {
                            readerViewModel.setChapterIndex(index)
                            scope.launch { sheetState.hide() }
                            showChaptersSheet = false
                        }
                    )
                }
            }
        }
    }
}

// ==================== 渲染组件 ====================

@Composable
fun ReaderContent(
    chapter: Chapter,
    fontSize: Float,
    lineSpacing: Float,
    scrollState: LazyListState,
    textColor: Color = MaterialTheme.colorScheme.onBackground,
    bgColor: Color = MaterialTheme.colorScheme.background,
    contentWidth: Int
) {
    LazyColumn(
        state = scrollState,
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 20.dp)
    ) {
        item {
            Text(
                text = chapter.title,
                fontSize = (fontSize + 6).sp,
                style = MaterialTheme.typography.titleLarge,
                lineHeight = (fontSize * lineSpacing * 1.2).sp,
                color = textColor,
                modifier = Modifier.padding(vertical = 16.dp)
            )
        }
        items(chapter.blocks) { block ->
            BlockView(
                block = block,
                fontSize = fontSize,
                lineSpacing = lineSpacing,
                textColor = textColor,
                contentWidth = contentWidth
            )
        }
    }
}

// ==================== Block 渲染 ====================

@Composable
fun BlockView(
    block: Block,
    fontSize: Float,
    lineSpacing: Float,
    textColor: Color,
    contentWidth: Int
) {
    when (block) {
        is Block.Text -> Text(
            text = block.text,
            fontSize = fontSize.sp,
            lineHeight = (fontSize * lineSpacing).sp,
            color = textColor,
            textAlign = TextAlign.Justify,
            modifier = Modifier.padding(vertical = 4.dp)
        )
        is Block.Title -> Text(
            text = block.text,
            fontSize = (fontSize + 6).sp,
            lineHeight = ((fontSize + 6) * lineSpacing).sp,
            style = MaterialTheme.typography.titleMedium,
            color = textColor,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        is Block.Image -> BlockImage(block.path, contentWidth, textColor)
    }
}

@Composable
fun BlockImage(path: String, contentWidth: Int, textColor: Color) {
    // 注意：不使用 remember(path)，因为需要在 path 变化时回收旧 bitmap
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(path) {
        failed = false
        withContext(Dispatchers.IO) {
            try {
                val data = if (path.startsWith("pdf://")) {
                    RustPdfEngine.loadPdfImage(path)
                } else {
                    RustEpubEngine.loadImage(path)
                }
                if (data != null && data.isNotEmpty()) {
                    val opts = BitmapFactory.Options().apply {
                        inSampleSize = calculateSampleSize(contentWidth, data)
                    }
                    val newBitmap = BitmapFactory.decodeByteArray(data, 0, data.size, opts)
                    if (newBitmap == null) {
                        failed = true
                    } else {
                        // 先保存旧 bitmap 引用，再更新状态，最后回收旧 bitmap
                        val old = bitmap
                        bitmap = newBitmap
                        old?.recycle()
                    }
                } else {
                    failed = true
                }
            } catch (e: Exception) {
                Log.w("BlockImage", "load image failed: $path", e)
                failed = true
            }
        }
    }

    // 组件离开组合时回收 bitmap，防止内存泄漏
    DisposableEffect(Unit) {
        onDispose {
            bitmap?.recycle()
            bitmap = null
        }
    }

    val bmp = bitmap
    if (bmp != null) {
        val aspectRatio = bmp.width.toFloat() / bmp.height.toFloat().coerceAtLeast(1f)
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspectRatio)
                .padding(vertical = 4.dp)
        )
    } else if (failed) {
        Text(
            "[图片加载失败]",
            fontSize = 12.sp,
            color = textColor.copy(alpha = 0.5f),
            modifier = Modifier.padding(vertical = 4.dp)
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp))
        }
    }
}

private fun calculateSampleSize(targetWidth: Int, data: ByteArray): Int {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(data, 0, data.size, options)
    val srcWidth = options.outWidth
    if (srcWidth <= 0 || targetWidth <= 0) return 1
    var sample = 1
    while (srcWidth / sample > targetWidth * 2) {
        sample *= 2
    }
    return sample.coerceAtLeast(1)
}

// ==================== 设置面板 ====================

@Composable
fun ReaderSettingsPanel(
    chapters: List<String>,
    currentChapterIdx: Int,
    fontSize: Float,
    themeMode: Int,
    lineSpacing: Float,
    brightness: Float,
    autoScrollSpeed: Float,
    onChapterChange: (Int) -> Unit,
    onFontSizeChange: (Float) -> Unit,
    onThemeChange: (Int) -> Unit,
    onLineSpacingChange: (Float) -> Unit,
    onBrightnessChange: (Float) -> Unit,
    onAutoScrollSpeedChange: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    Column(modifier = Modifier.padding(20.dp)) {
        Text("阅读设置", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { if (currentChapterIdx > 0) onChapterChange(currentChapterIdx - 1) }) {
                Text("上一章")
            }
            Slider(
                value = currentChapterIdx.toFloat(),
                onValueChange = { onChapterChange(it.toInt()) },
                valueRange = 0f..(chapters.size - 1).coerceAtLeast(1).toFloat(),
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { if (currentChapterIdx < chapters.size - 1) onChapterChange(currentChapterIdx + 1) }) {
                Text("下一章")
            }
        }
        Text("第 ${currentChapterIdx + 1}/${chapters.size} 章", style = MaterialTheme.typography.labelSmall)

        Spacer(modifier = Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("字号", modifier = Modifier.width(40.dp))
            TextButton(onClick = { if (fontSize > 12f) onFontSizeChange(fontSize - 2) }) { Text("A-") }
            Slider(value = fontSize, onValueChange = onFontSizeChange, valueRange = 12f..36f, modifier = Modifier.weight(1f))
            TextButton(onClick = { if (fontSize < 36f) onFontSizeChange(fontSize + 2) }) { Text("A+") }
            Text("${fontSize.toInt()}", modifier = Modifier.width(32.dp))
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("行距", modifier = Modifier.width(40.dp))
            Slider(value = lineSpacing, onValueChange = onLineSpacingChange, valueRange = 1f..2.5f, modifier = Modifier.weight(1f))
            Text("${"%.1f".format(lineSpacing)}", modifier = Modifier.width(32.dp))
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("亮度", modifier = Modifier.width(40.dp))
            Slider(value = brightness, onValueChange = onBrightnessChange, valueRange = 0.1f..1f, modifier = Modifier.weight(1f))
            Text("${(brightness * 100).toInt()}%", modifier = Modifier.width(40.dp))
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("主题", modifier = Modifier.width(40.dp))
            FilterChip(selected = themeMode == 0, onClick = { onThemeChange(0) }, label = { Text("白天") })
            Spacer(modifier = Modifier.width(8.dp))
            FilterChip(selected = themeMode == 1, onClick = { onThemeChange(1) }, label = { Text("护眼") })
            Spacer(modifier = Modifier.width(8.dp))
            FilterChip(selected = themeMode == 2, onClick = { onThemeChange(2) }, label = { Text("夜间") })
            Spacer(modifier = Modifier.width(8.dp))
            FilterChip(selected = themeMode == 3, onClick = { onThemeChange(3) }, label = { Text("黑白") })
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("自滚", modifier = Modifier.width(40.dp))
            Slider(
                value = autoScrollSpeed,
                onValueChange = onAutoScrollSpeedChange,
                valueRange = 0f..10f,
                modifier = Modifier.weight(1f)
            )
            Text(
                if (autoScrollSpeed <= 0f) "关" else "${"%.1f".format(autoScrollSpeed)}",
                modifier = Modifier.width(40.dp)
            )
        }

        Spacer(modifier = Modifier.height(20.dp))
        TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
            Text("关闭")
        }
    }
}

// ==================== PDF 渲染组件 ====================

// Rust 侧 pdf_engine.rs 中 RENDER_TARGET_WIDTH 常量，需保持同步
private const val PDF_RENDER_WIDTH = 1200

/**
 * PDF 连续滚动渲染组件。
 *
 * - 所有页面以 LazyColumn 连续滚动展示，无翻页闪烁
 * - 双指缩放（pinchZoom 累积），仅在放大状态时支持水平平移
 * - 双击切换 fit-width / 默认（竖屏）；横屏默认 fit-page
 * - 横屏自动适应满屏（fit-page，整页可见）
 * - 单指垂直滚动由 LazyColumn 处理，与 pinch 手势不冲突
 * - 加载中/失败/成功三态统一用 aspectRatio 高度，避免高度突变导致 LazyColumn 跳跃
 */
@Composable
fun PdfContinuousContent(
    metadataList: List<EpubParser.ChapterMetadata>,
    initialPageIndex: Int,
    screenWidth: Int,
    screenHeight: Int,
    isLandscape: Boolean,
    baseZoom: Float,
    onPageIndexChange: (Int) -> Unit,
    onUserScroll: () -> Unit = {},
    onTap: () -> Unit = {},
    onLongPress: () -> Unit = {}
) {
    // 取第一页加载到的 bitmap 尺寸代表整本（同一 PDF 页面比例通常一致）
    var pageNaturalSize by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    // 缩放模式：0=默认(baseZoom), 1=fit width(同宽), 2=fit page(整页可见)
    var zoomMode by remember(isLandscape) {
        mutableStateOf(if (isLandscape) 2 else 0)
    }
    var pinchZoom by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }

    val scrollState = rememberLazyListState(initialFirstVisibleItemIndex = initialPageIndex.coerceIn(0, metadataList.size - 1))

    // 通知外层当前页索引变化
    LaunchedEffect(scrollState) {
        snapshotFlow { scrollState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .filter { it in metadataList.indices }
            .collect { onPageIndexChange(it) }
    }

    // 用户开始滚动时通知外层（用于横屏自动隐藏顶栏）
    LaunchedEffect(scrollState) {
        snapshotFlow { scrollState.isScrollInProgress }
            .filter { it }
            .collect { onUserScroll() }
    }

    // 加载中占位的预估比例：优先用已加载页面的实际比例，否则用 A4 默认 1/1.4
    val defaultAspect = pageNaturalSize?.let { (w, h) ->
        w.toFloat() / h.toFloat().coerceAtLeast(1f)
    } ?: (1f / 1.4f)

    // 计算 fit zoom 系数
    val fitZoom = when (zoomMode) {
        1 -> screenWidth.toFloat() / PDF_RENDER_WIDTH  // fit width：屏幕宽 / 渲染宽
        2 -> {
            // fit page：整页可见，取宽和高的较小比例
            pageNaturalSize?.let { (w, h) ->
                if (w > 0 && h > 0) {
                    minOf(screenWidth.toFloat() / w, screenHeight.toFloat() / h)
                } else 1f
            } ?: 1f
        }
        else -> 1f
    }

    val finalZoom = baseZoom * fitZoom * pinchZoom

    LazyColumn(
        state = scrollState,
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onLongPress = { onLongPress() },
                    onDoubleTap = {
                        // 竖屏：在 默认 / fit-width 之间切换
                        // 横屏：在 fit-page / fit-width 之间切换
                        zoomMode = when {
                            isLandscape -> if (zoomMode == 2) 1 else 2
                            else -> if (zoomMode == 1) 0 else 1
                        }
                        pinchZoom = 1f
                        offsetX = 0f
                    }
                )
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, gestureZoom, _ ->
                    if (gestureZoom != 1f) {
                        val newPinch = (pinchZoom * gestureZoom).coerceIn(0.3f, 5f)
                        pinchZoom = newPinch
                        // 缩回 <= 1 时重置 offset
                        if (newPinch < 1.001f) offsetX = 0f
                    }
                    // 仅在放大时支持水平平移（垂直仍由 LazyColumn 处理）
                    if (pan != Offset.Zero && pinchZoom > 1.001f) {
                        offsetX += pan.x
                    }
                }
            }
    ) {
        items(metadataList.size, key = { it }) { index ->
            val meta = metadataList[index]
            PdfImage(
                path = meta.path,
                contentWidth = screenWidth,
                zoom = finalZoom,
                offsetX = offsetX,
                defaultAspect = defaultAspect,
                onSizeMeasured = { w, h ->
                    if (pageNaturalSize == null && w > 0 && h > 0) {
                        pageNaturalSize = w to h
                    }
                }
            )
        }
    }
}

@Composable
fun PdfImage(
    path: String,
    contentWidth: Int,
    zoom: Float,
    offsetX: Float,
    defaultAspect: Float = 1f / 1.4f,
    onSizeMeasured: (Int, Int) -> Unit = { _, _ -> }
) {
    var bitmap by remember(path) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(path) { mutableStateOf(false) }

    LaunchedEffect(path) {
        withContext(Dispatchers.IO) {
            try {
                val data = RustPdfEngine.loadPdfImage(path)
                if (data != null && data.isNotEmpty()) {
                    val bmp = BitmapFactory.decodeByteArray(data, 0, data.size)
                    if (bmp == null) {
                        failed = true
                    } else {
                        bitmap = bmp
                        onSizeMeasured(bmp.width, bmp.height)
                    }
                } else {
                    failed = true
                }
            } catch (e: Exception) {
                failed = true
            }
        }
    }

    val bmp = bitmap
    // 加载中用 defaultAspect，加载完用实际比例
    val aspect = if (bmp != null) {
        bmp.width.toFloat() / bmp.height.toFloat().coerceAtLeast(1f)
    } else {
        defaultAspect
    }
    val safeAspect = aspect.coerceAtLeast(0.1f)
    val safeZoom = zoom.coerceAtLeast(0.1f)  // 避免除零

    // 关键：Box layout 高度 = 放大后的实际绘制高度，避免相邻 item 图像重叠
    // aspect / zoom 让 layout 高度 = screenWidth * zoom / aspect = 绘制高度
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(safeAspect / safeZoom)
            .background(Color.Black),
        contentAlignment = Alignment.TopStart
    ) {
        when {
            bmp != null -> {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(aspect)
                        .graphicsLayer(
                            scaleX = zoom,
                            scaleY = zoom,
                            translationX = offsetX,
                            translationY = 0f,
                            transformOrigin = TransformOrigin(0f, 0f)  // 从左上角缩放，绘制范围 = layout 范围
                        )
                )
            }
            failed -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("[PDF 页面渲染失败]", color = MaterialTheme.colorScheme.error)
                }
            }
            else -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp), color = Color.White)
                }
            }
        }
    }
}

// ==================== PDF 设置面板 ====================

@Composable
fun PdfSettingsPanel(
    chapters: List<String>,
    currentChapterIdx: Int,
    pdfZoom: Float,
    pdfOrientation: Int,
    brightness: Float,
    onChapterChange: (Int) -> Unit,
    onZoomChange: (Float) -> Unit,
    onOrientationChange: (Int) -> Unit,
    onBrightnessChange: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    Column(modifier = Modifier.padding(20.dp)) {
        Text("PDF 设置", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(16.dp))

        // 章节导航
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { if (currentChapterIdx > 0) onChapterChange(currentChapterIdx - 1) }) {
                Text("上一页")
            }
            Slider(
                value = currentChapterIdx.toFloat(),
                onValueChange = { onChapterChange(it.toInt()) },
                valueRange = 0f..(chapters.size - 1).coerceAtLeast(1).toFloat(),
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { if (currentChapterIdx < chapters.size - 1) onChapterChange(currentChapterIdx + 1) }) {
                Text("下一页")
            }
        }
        Text("第 ${currentChapterIdx + 1}/${chapters.size} 页", style = MaterialTheme.typography.labelSmall)

        Spacer(modifier = Modifier.height(16.dp))

        // 缩放
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("缩放", modifier = Modifier.width(40.dp))
            TextButton(onClick = { if (pdfZoom > 0.5f) onZoomChange(pdfZoom - 0.1f) }) { Text("-") }
            Slider(value = pdfZoom, onValueChange = onZoomChange, valueRange = 0.5f..3f, modifier = Modifier.weight(1f))
            TextButton(onClick = { if (pdfZoom < 3f) onZoomChange(pdfZoom + 0.1f) }) { Text("+") }
            Text("${"%.1f".format(pdfZoom)}x", modifier = Modifier.width(40.dp))
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 横竖屏
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("方向", modifier = Modifier.width(40.dp))
            FilterChip(selected = pdfOrientation == 0, onClick = { onOrientationChange(0) }, label = { Text("自动") })
            Spacer(modifier = Modifier.width(8.dp))
            FilterChip(selected = pdfOrientation == 1, onClick = { onOrientationChange(1) }, label = { Text("竖屏") })
            Spacer(modifier = Modifier.width(8.dp))
            FilterChip(selected = pdfOrientation == 2, onClick = { onOrientationChange(2) }, label = { Text("横屏") })
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 亮度
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("亮度", modifier = Modifier.width(40.dp))
            Slider(value = brightness, onValueChange = onBrightnessChange, valueRange = 0.1f..1f, modifier = Modifier.weight(1f))
            Text("${(brightness * 100).toInt()}%", modifier = Modifier.width(40.dp))
        }

        Spacer(modifier = Modifier.height(20.dp))
        TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
            Text("关闭")
        }
    }
}

// ==================== 辅助函数 ====================

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

@Composable
private fun PauseIcon(contentDescription: String?) {
    val tint = LocalContentColor.current
    Canvas(modifier = Modifier.size(24.dp)) {
        val barW = size.width * 0.22f
        val barH = size.height * 0.55f
        val top = size.height * 0.225f
        drawRect(
            color = tint,
            topLeft = Offset(size.width * 0.26f, top),
            size = Size(barW, barH)
        )
        drawRect(
            color = tint,
            topLeft = Offset(size.width * 0.52f, top),
            size = Size(barW, barH)
        )
    }
}
package com.example.litereader.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/**
 * 通用书籍封面组件。
 * 有 coverUrl 时加载网络图片，否则显示带书名首字的占位图。
 */
@Composable
fun BookCover(
    title: String,
    coverUrl: String?,
    modifier: Modifier = Modifier
) {
    if (coverUrl != null && coverUrl.isNotBlank()) {
        AsyncImage(
            model = coverUrl,
            contentDescription = title,
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    } else {
        // 无封面时显示带书名首字的渐变色块
        val bgColor = colorFromTitle(title)
        Box(
            modifier = modifier.background(bgColor),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = title.firstOrNull()?.toString() ?: "?",
                color = Color.White,
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * 根据书名生成稳定的颜色。
 */
private fun colorFromTitle(title: String): Color {
    val colors = listOf(
        Color(0xFF2B5C8F),
        Color(0xFF8D6E63),
        Color(0xFF558B2F),
        Color(0xFF6A1B9A),
        Color(0xFFAD1457),
        Color(0xFFEF6C00),
        Color(0xFF00838F),
        Color(0xFF37474F),
    )
    val hash = title.hashCode().let { if (it < 0) -it else it }
    return colors[hash % colors.size]
}

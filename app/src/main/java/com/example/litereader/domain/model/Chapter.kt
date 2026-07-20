package com.example.litereader.domain.model

import java.io.Serializable

/**
 * 渲染块。与 Rust 侧 Block 枚举一一对应。
 * Rust 负责 XHTML→Block 转换，Kotlin 负责测量与显示。
 */
sealed class Block : Serializable {
    /** 普通段落文本。 */
    data class Text(val text: String) : Block()

    /** 图片，path 为 EPUB 内的绝对路径，由 Rust loadImage 按需读取。 */
    data class Image(val path: String) : Block()

    /** 标题（h1-h6）。 */
    data class Title(val text: String) : Block()
}

/**
 * 章节。不再保存整段 HTML，改为保存 Rust 解析后的 Block 列表。
 */
data class Chapter(
    val title: String,
    val blocks: List<Block> = emptyList()
) : Serializable

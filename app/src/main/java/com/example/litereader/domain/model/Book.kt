package com.example.litereader.domain.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "books")
data class Book(
    @PrimaryKey val id: String,
    val title: String,
    val author: String,
    val coverUrl: String?,
    val downloadUrl: String,
    val fileFormat: String, // epub / txt / mobi / pdf
    val fileSize: String? = null,
    val source: String,
    val localPath: String? = null,
    val isDownloaded: Boolean = false,
    val lastReadChapter: Int = 0,
    val lastReadOffset: Int = 0,
    val addedAt: Long = System.currentTimeMillis()
)

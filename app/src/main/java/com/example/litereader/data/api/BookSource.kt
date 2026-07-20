package com.example.litereader.data.api

import com.example.litereader.domain.model.Book

interface BookSource {
    val name: String
    suspend fun search(query: String, page: Int): List<Book>
    suspend fun resolveDownloadUrl(book: Book): String
}

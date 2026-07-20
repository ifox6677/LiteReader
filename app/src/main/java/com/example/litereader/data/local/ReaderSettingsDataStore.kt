package com.example.litereader.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.readerDataStore by preferencesDataStore(name = "reader_settings")

class ReaderSettingsDataStore(private val context: Context) {
    companion object {
        val FONT_SIZE = floatPreferencesKey("font_size")
        val LINE_SPACING = floatPreferencesKey("line_spacing")
        val THEME_MODE = intPreferencesKey("theme_mode")
        val BRIGHTNESS = floatPreferencesKey("brightness")
        val LIBGEN_URL = stringPreferencesKey("libgen_url")
        val AUTO_SCROLL_SPEED = floatPreferencesKey("auto_scroll_speed")
        val PDF_ZOOM = floatPreferencesKey("pdf_zoom")
        val PDF_ORIENTATION = intPreferencesKey("pdf_orientation")
    }

    val fontSize: Flow<Float> = context.readerDataStore.data.map { it[FONT_SIZE] ?: 18f }
    val lineSpacing: Flow<Float> = context.readerDataStore.data.map { it[LINE_SPACING] ?: 1.5f }
    val themeMode: Flow<Int> = context.readerDataStore.data.map { it[THEME_MODE] ?: 2 }
    val brightness: Flow<Float> = context.readerDataStore.data.map { it[BRIGHTNESS] ?: 1f }
    val libgenUrl: Flow<String> = context.readerDataStore.data.map { it[LIBGEN_URL] ?: "https://libgen.la" }
    val autoScrollSpeed: Flow<Float> = context.readerDataStore.data.map { it[AUTO_SCROLL_SPEED] ?: 0f }
    val pdfZoom: Flow<Float> = context.readerDataStore.data.map { it[PDF_ZOOM] ?: 0.8f }
    val pdfOrientation: Flow<Int> = context.readerDataStore.data.map { it[PDF_ORIENTATION] ?: 0 }

    suspend fun saveFontSize(size: Float) {
        context.readerDataStore.edit { it[FONT_SIZE] = size.coerceIn(12f, 36f) }
    }

    suspend fun saveLineSpacing(spacing: Float) {
        context.readerDataStore.edit { it[LINE_SPACING] = spacing.coerceIn(1f, 2.5f) }
    }

    suspend fun saveThemeMode(mode: Int) {
        context.readerDataStore.edit { it[THEME_MODE] = mode }
    }

    suspend fun saveBrightness(brightness: Float) {
        context.readerDataStore.edit { it[BRIGHTNESS] = brightness.coerceIn(0.1f, 1f) }
    }

    suspend fun saveLibgenUrl(url: String) {
        context.readerDataStore.edit { it[LIBGEN_URL] = url }
    }

    suspend fun saveAutoScrollSpeed(speed: Float) {
        context.readerDataStore.edit { it[AUTO_SCROLL_SPEED] = speed.coerceIn(0f, 10f) }
    }

    suspend fun savePdfZoom(zoom: Float) {
        context.readerDataStore.edit { it[PDF_ZOOM] = zoom.coerceIn(0.5f, 3f) }
    }

    suspend fun savePdfOrientation(orientation: Int) {
        context.readerDataStore.edit { it[PDF_ORIENTATION] = orientation.coerceIn(0, 2) }
    }
}

package com.example.litereader.ui.screen

import android.app.Activity
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.litereader.ui.viewmodel.BookViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: BookViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val fontSize by viewModel.fontSize.collectAsState(initial = 18f)
    val lineSpacing by viewModel.lineSpacing.collectAsState(initial = 1.5f)
    val themeMode by viewModel.themeMode.collectAsState(initial = 2)
    val brightness by viewModel.brightness.collectAsState(initial = 1f)
    val autoScrollSpeed by viewModel.autoScrollSpeed.collectAsState(initial = 0f)

    DisposableEffect(brightness) {
        val window = (context as? Activity)?.window
        window?.attributes = window?.attributes?.apply {
            screenBrightness = brightness.coerceIn(0.1f, 1f)
        }
        onDispose { }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("个性化设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Text("字号 (${fontSize.toInt()} sp)")
            Slider(
                value = fontSize,
                onValueChange = { viewModel.saveFontSize(it) },
                valueRange = 12f..36f
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { viewModel.saveFontSize(14f) }) { Text("小") }
                TextButton(onClick = { viewModel.saveFontSize(18f) }) { Text("中") }
                TextButton(onClick = { viewModel.saveFontSize(22f) }) { Text("大") }
            }
            Spacer(modifier = Modifier.height(24.dp))

            Text("行距 (${String.format("%.1f", lineSpacing)})")
            Slider(
                value = lineSpacing,
                onValueChange = { viewModel.saveLineSpacing(it) },
                valueRange = 1f..2.5f
            )
            Spacer(modifier = Modifier.height(24.dp))

            Text("主题")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = themeMode == 0,
                    onClick = { viewModel.saveThemeMode(0) },
                    label = { Text("白天") }
                )
                FilterChip(
                    selected = themeMode == 1,
                    onClick = { viewModel.saveThemeMode(1) },
                    label = { Text("护眼") }
                )
                FilterChip(
                    selected = themeMode == 2,
                    onClick = { viewModel.saveThemeMode(2) },
                    label = { Text("夜间") }
                )
            }
            Spacer(modifier = Modifier.height(24.dp))

            Text("自动滚动速度 (${if (autoScrollSpeed <= 0f) "关" else String.format("%.1f", autoScrollSpeed)})")
            Slider(
                value = autoScrollSpeed,
                onValueChange = { viewModel.saveAutoScrollSpeed(it) },
                valueRange = 0f..10f
            )
            Spacer(modifier = Modifier.height(24.dp))

            Text("亮度 (${(brightness * 100).toInt()}%)")
            Slider(
                value = brightness,
                onValueChange = { viewModel.saveBrightness(it) },
                valueRange = 0.1f..1f
            )
        }
    }
}

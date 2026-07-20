package com.example.litereader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.litereader.reader.ReaderScreen
import com.example.litereader.ui.screen.HomeScreen
import com.example.litereader.ui.screen.SearchScreen
import com.example.litereader.ui.screen.SettingsScreen
import com.example.litereader.ui.theme.LiteReaderTheme
import com.example.litereader.ui.viewmodel.BookViewModel
import com.example.litereader.ui.viewmodel.ReaderViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: BookViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val themeMode by viewModel.themeMode.collectAsState(initial = 2)
            val brightness by viewModel.brightness.collectAsState(initial = 1f)

            window.attributes = window.attributes.apply {
                screenBrightness = brightness.coerceIn(0.1f, 1f)
            }

            LiteReaderTheme(themeMode = themeMode) {
                val navController = rememberNavController()

                NavHost(navController = navController, startDestination = "home") {
                    composable("home") {
                        HomeScreen(
                            viewModel = viewModel,
                            onNavigateToSearch = { query ->
                                if (query.isNotBlank()) {
                                    viewModel.search(query, refresh = true)
                                }
                                navController.navigate("search")
                            },
                            onNavigateToSettings = { navController.navigate("settings") },
                            onNavigateToReader = { id -> navController.navigate("reader/$id") }
                        )
                    }
                    composable("search") {
                        SearchScreen(
                            viewModel = viewModel,
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable("settings") {
                        SettingsScreen(
                            viewModel = viewModel,
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable("reader/{bookId}") { backStackEntry ->
                        val bookId = backStackEntry.arguments?.getString("bookId") ?: ""
                        // ReaderViewModel 作用域绑定到当前 NavBackStackEntry，返回时自动 onCleared
                        val readerViewModel: ReaderViewModel = viewModel()
                        ReaderScreen(
                            bookId = bookId,
                            readerViewModel = readerViewModel,
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }
}

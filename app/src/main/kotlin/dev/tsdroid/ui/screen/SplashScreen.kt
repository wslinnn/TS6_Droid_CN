package dev.tsdroid.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.tsdroid.ui.component.AnimeWallpaperState
import kotlinx.coroutines.delay

@Composable
fun SplashScreen(onReady: () -> Unit) {
    val context = LocalContext.current
    var showContent by remember { mutableStateOf(false) }
    var timedOut by remember { mutableStateOf(false) }
    val url by AnimeWallpaperState.currentUrl
    val dominantColor by AnimeWallpaperState.dominantColor

    LaunchedEffect(Unit) {
        AnimeWallpaperState.ensureFetched(context)
    }

    LaunchedEffect(url) {
        if (url != null) {
            delay(800)
            showContent = true
        }
    }

    // Safety timeout: if no URL after 3s, proceed immediately — the cached
    // fallback covers it and waiting longer just pads a dead screen
    LaunchedEffect(Unit) {
        delay(3000)
        if (!showContent) {
            showContent = true
            timedOut = true
        }
    }

    LaunchedEffect(showContent, dominantColor) {
        if (showContent && dominantColor != null) {
            delay(600)
            onReady()
        } else if (showContent && dominantColor == null) {
            // Late wallpaper still gets the fade-in grace period; a timeout
            // exit skips it entirely (worst case 3s instead of 4.5s)
            delay(if (timedOut) 0 else 1500)
            onReady()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Text(
                text = "TS6 Droid",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )

            if (!showContent) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

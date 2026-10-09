package dev.tsdroid.ui.screen

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

@Composable
fun AppNavigation() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "connection") {
        composable("connection") {
            ConnectionScreen(
                onConnected = { navController.navigate("server") {
                    popUpTo("connection") { inclusive = true }
                }},
                onNavigateToAbout = { navController.navigate("about") },
                onNavigateToLogs = { navController.navigate("logs") }
            )
        }
        composable("server") {
            ServerScreen(
                onDisconnected = { navController.navigate("connection") {
                    popUpTo("server") { inclusive = true }
                }},
                onNavigateToAbout = { navController.navigate("about") }
            )
        }
        composable("about") {
            AboutScreen(
                onBack = { navController.popBackStack() }
            )
        }
        composable("logs") {
            LogScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}

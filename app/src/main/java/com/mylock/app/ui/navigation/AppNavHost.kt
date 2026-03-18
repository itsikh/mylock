package com.mylock.app.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mylock.app.bugreport.ScreenshotHolder
import com.mylock.app.ui.components.DebugOverlayViewModel
import com.mylock.app.ui.components.FloatingBugButton
import com.mylock.app.ui.permission.LocationPermissionFlow
import com.mylock.app.ui.screens.bugreport.BugReportScreen
import com.mylock.app.ui.screens.bugreport.ReportMode
import com.mylock.app.ui.screens.home.HomeScreen
import com.mylock.app.ui.screens.settings.SettingsScreen

/**
 * Root navigation graph for the app.
 *
 * Uses Jetpack Compose Navigation with a [NavHost] and string-based route identifiers.
 * [MainActivity] calls this composable as the sole content of [setContent].
 *
 * ## Routes
 * | Route | Screen | Notes |
 * |-------|--------|-------|
 * | `home` | [HomeScreen] | Start destination — replace with your main screen |
 * | `settings` | [SettingsScreen] | App settings, debug tools, backup/restore |
 * | `bug_report/{mode}` | [BugReportScreen] | Bug or feedback form; `mode` is a [ReportMode] name |
 *
 * ## Floating bug button
 * When admin mode is on and "Bug Report Button" is enabled in Settings → Debug, a draggable
 * [FloatingBugButton] overlays every screen. Tapping it captures the current screen, stores
 * the screenshot in [ScreenshotHolder], and navigates directly to the bug report form.
 *
 * ## Adding new screens
 * 1. Create your composable screen in `ui/screens/your_feature/YourScreen.kt`.
 * 2. Add a `composable("your_route") { YourScreen(...) }` entry below.
 * 3. Navigate to it from any other screen using `navController.navigate("your_route")`.
 *    Pass the `navController` down as a lambda parameter (e.g. `onNavigate = { navController.navigate(...) }`)
 *    rather than passing `navController` itself, to keep screens decoupled from navigation.
 *
 * ## Adding arguments
 * For routes with parameters (e.g. `"detail/{id}"`), use the
 * [NavHost] argument DSL or typed navigation with the `navigation-compose` serialization APIs.
 */
@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    val overlayVm: DebugOverlayViewModel = hiltViewModel()
    val showBugButton by overlayVm.showBugButton.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        LocationPermissionFlow {
        NavHost(navController = navController, startDestination = "home") {
            composable("home") {
                HomeScreen(
                    onOpenSettings = { navController.navigate("settings") }
                )
            }
            composable("settings") {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenBugReport = { mode ->
                        navController.navigate("bug_report/${mode.name}")
                    }
                )
            }
            composable("bug_report/{mode}") { backStackEntry ->
                val modeName = backStackEntry.arguments?.getString("mode")
                val mode = modeName?.let { runCatching { ReportMode.valueOf(it) }.getOrNull() }
                    ?: ReportMode.BUG_REPORT
                BugReportScreen(
                    mode = mode,
                    onBack = { navController.popBackStack() }
                )
            }
        }

        FloatingBugButton(
            visible = showBugButton,
            onScreenshotCaptured = { bitmap ->
                ScreenshotHolder.store(bitmap)
                navController.navigate("bug_report/BUG_REPORT")
            }
        )
        } // end LocationPermissionFlow
    }
}

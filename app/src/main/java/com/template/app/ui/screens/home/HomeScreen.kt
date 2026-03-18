package com.template.app.ui.screens.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.template.app.R

/**
 * Template placeholder for the main/home screen.
 *
 * This is the `startDestination` in [ui.navigation.AppNavHost] and the first screen
 * users see when they open the app. It is intentionally minimal — replace the content
 * inside the [Scaffold] with whatever your app's main UI should be.
 *
 * ## What's here by default
 * - A [TopAppBar] showing the app name from `strings.xml` and a settings icon button.
 * - A centered "Hello, World!" text placeholder.
 *
 * ## How to replace
 * 1. Replace the [Box] / [Text] content with your actual screen layout.
 * 2. Add ViewModel injection if needed: `val vm: MyViewModel = hiltViewModel()`.
 * 3. Add navigation callbacks as parameters if this screen needs to navigate elsewhere,
 *    and wire them up in [ui.navigation.AppNavHost].
 *
 * @param onOpenSettings Called when the user taps the settings icon in the top bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpenSettings: () -> Unit = {}) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.Center
        ) {
            Text("Hello, World!")
        }
    }
}

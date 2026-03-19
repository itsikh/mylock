package com.mylock.app.ui.screens.portal

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.mylock.app.security.SecureKeyManager
import com.mylock.app.ttlock.TtlockRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import org.json.JSONObject
import javax.inject.Inject

@HiltViewModel
class PortalViewModel @Inject constructor(
    private val secureKeyManager: SecureKeyManager
) : ViewModel() {
    val username: String get() = secureKeyManager.getKey(TtlockRepository.KEY_USERNAME) ?: ""
    val password: String get() = secureKeyManager.getKey(TtlockRepository.KEY_PASSWORD) ?: ""
}

/**
 * Embedded WebView that opens the TTLock developer portal and automatically logs in
 * using the stored TTLock account credentials.
 *
 * On each page load the screen checks whether the page contains a login form and, if so,
 * injects JavaScript that fills the username/password fields and submits the form.
 * Using the native HTMLInputElement value setter ensures React/Vue-based forms pick up
 * the programmatic change just like a real key-press.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PortalWebViewScreen(
    onBack: () -> Unit,
    viewModel: PortalViewModel = hiltViewModel()
) {
    var isLoading by remember { mutableStateOf(true) }

    // Safely JSON-encode credentials so special chars in passwords don't break the JS
    val jsUsername = remember { JSONObject.quote(viewModel.username) }
    val jsPassword = remember { JSONObject.quote(viewModel.password) }

    val injectionScript = remember {
        """
        (function() {
            function setReactValue(el, value) {
                var nativeSetter = Object.getOwnPropertyDescriptor(
                    window.HTMLInputElement.prototype, 'value'
                ).set;
                nativeSetter.call(el, value);
                el.dispatchEvent(new Event('input',  { bubbles: true }));
                el.dispatchEvent(new Event('change', { bubbles: true }));
            }
            var userField = document.querySelector('input[type="text"], input[type="email"]');
            var passField = document.querySelector('input[type="password"]');
            if (userField && passField) {
                setReactValue(userField, $jsUsername);
                setReactValue(passField, $jsPassword);
                setTimeout(function() {
                    var btn = document.querySelector('button[type="submit"]')
                           || document.querySelector('form button');
                    if (btn) btn.click();
                }, 300);
            }
        })();
        """.trimIndent()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("API Usage Dashboard") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true

                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String) {
                                isLoading = false
                                // Inject auto-login on any page that has a password field
                                // (login page). The script is a no-op on other pages.
                                view.evaluateJavascript(injectionScript, null)
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest
                            ): Boolean {
                                // Stay within the WebView for all portal navigation
                                isLoading = true
                                return false
                            }
                        }

                        loadUrl("https://euopen.ttlock.com/login")
                    }
                }
            )

            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}

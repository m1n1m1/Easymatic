package io.github.m1n1m1.easymatic.feature.help

import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.m1n1m1.easymatic.R
import io.github.m1n1m1.easymatic.feature.grapheditor.EditorColors

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun DocumentationScreen(
    webView: WebView?,
    page: DocumentationPageState,
    onClose: () -> Unit,
    onBrowser: () -> Unit,
    onRetry: () -> Unit,
) {
    BackHandler {
        if (webView?.canGoBack() == true) webView.goBack() else onClose()
    }
    Scaffold(
        containerColor = EditorColors.canvasBackground,
        topBar = { DocumentationTopBar(onClose, onBrowser) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (page.progress < COMPLETE && !page.failed && webView != null) {
                LinearProgressIndicator(
                    progress = { page.progress / COMPLETE.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (webView != null) {
                    AndroidView(
                        factory = { webView },
                        modifier = Modifier.fillMaxSize(),
                        update = {
                            it.visibility = if (page.failed) android.view.View.INVISIBLE else android.view.View.VISIBLE
                        },
                    )
                }
                if (page.failed || webView == null) {
                    DocumentationFailure(webView != null, onRetry, onBrowser)
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun DocumentationTopBar(onClose: () -> Unit, onBrowser: () -> Unit) {
    TopAppBar(
        title = { Text(stringResource(R.string.help_title)) },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, stringResource(R.string.help_close))
            }
        },
        actions = {
            IconButton(onClick = onBrowser) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, stringResource(R.string.help_browser))
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = EditorColors.chrome,
            titleContentColor = EditorColors.textPrimary,
            navigationIconContentColor = EditorColors.textPrimary,
            actionIconContentColor = EditorColors.textPrimary,
        ),
    )
}

@Composable
private fun DocumentationFailure(canRetry: Boolean, onRetry: () -> Unit, onBrowser: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().background(EditorColors.canvasBackground).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            stringResource(if (canRetry) R.string.help_load_failed else R.string.help_unavailable),
            color = EditorColors.textPrimary,
        )
        if (canRetry) {
            TextButton(onClick = onRetry) { Text(stringResource(R.string.help_retry)) }
        }
        TextButton(onClick = onBrowser) { Text(stringResource(R.string.help_browser)) }
    }
}

private const val COMPLETE = 100

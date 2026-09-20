package io.github.m1n1m1.easymatic.feature.help

import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal class DocumentationPageState {
    var url by mutableStateOf(DOCUMENTATION_URL)
    var progress by mutableIntStateOf(0)
    var failed by mutableStateOf(false)
}

// The hosted documentation's search and menu require JavaScript; no native bridge.
@Suppress("SetJavaScriptEnabled")
internal fun configureDocumentationView(
    view: WebView,
    page: DocumentationPageState,
    openExternal: (String) -> Unit,
) {
    view.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        // User-activated target=_blank links navigate this view and pass through the same client.
        setSupportMultipleWindows(false)
        javaScriptCanOpenWindowsAutomatically = false
    }
    view.webViewClient = DocumentationClient(page, openExternal)
    view.webChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            page.progress = newProgress
        }
    }
}

private class DocumentationClient(
    private val page: DocumentationPageState,
    private val openExternal: (String) -> Unit,
) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        when (documentationLink(request.url.toString())) {
            DocumentationLink.INTERNAL -> false
            DocumentationLink.EXTERNAL -> {
                if (request.isForMainFrame) openExternal(request.url.toString())
                true
            }
            DocumentationLink.BLOCKED -> true
        }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        if (documentationLink(url) != DocumentationLink.INTERNAL) {
            view.stopLoading()
            page.failed = true
            return
        }
        page.url = url
        page.failed = false
        page.progress = 0
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (request.isForMainFrame) page.failed = true
    }

    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
        if (request.isForMainFrame) page.failed = true
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        handler.cancel()
        page.failed = true
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
        if (documentationLink(url) == DocumentationLink.INTERNAL) page.url = url
    }
}

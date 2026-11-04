package com.macroresearch.ui.news

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.macroresearch.R
import com.macroresearch.data.remote.ArticlePreviewClient
import java.io.ByteArrayInputStream
import java.net.InetAddress

/** No JS interface, injected headers, DOM extraction, app tokens or external-application navigation. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun NewsWebPage(url: String, modifier: Modifier = Modifier) {
    var scripts by remember(url) { mutableStateOf(false) }
    var confirmScripts by remember { mutableStateOf(false) }
    var failed by remember(url) { mutableStateOf(false) }
    var loading by remember(url) { mutableStateOf(true) }
    var retry by remember(url) { mutableIntStateOf(0) }
    Column(modifier.fillMaxWidth()) {
        Text(stringResource(R.string.news_web_notice), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
        if (!scripts) TextButton(onClick = { confirmScripts = true }) { Text(stringResource(R.string.news_web_scripts)) }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (failed) {
            Text(stringResource(R.string.news_web_failed), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { failed = false; loading = true; retry++ }) { Text(stringResource(R.string.news_refresh)) }
        }
        key(url, retry) {
            AndroidView(modifier = Modifier.fillMaxWidth().weight(1f), factory = { context ->
                WebView(context).apply {
                    settings.apply {
                        javaScriptEnabled = scripts
                        domStorageEnabled = scripts
                        allowFileAccess = false
                        allowContentAccess = false
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        setSupportMultipleWindows(false)
                        javaScriptCanOpenWindowsAutomatically = false
                        mediaPlaybackRequiresUserGesture = true
                        safeBrowsingEnabled = true
                    }
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                    webChromeClient = object : WebChromeClient() {
                        override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
                    }
                    val hosts = mutableMapOf<String, Boolean>()
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            // Private hosts are also resolved/blocked off the UI thread below.
                            return ArticlePreviewClient.safeArticleUrl(request.url.toString()) == null
                        }
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                            val target = ArticlePreviewClient.safeArticleUrl(request.url.toString())
                            val allowed = target != null && synchronized(hosts) {
                                hosts[target.host] ?: runCatching {
                                    val addresses = InetAddress.getAllByName(target.host)
                                    addresses.isNotEmpty() && addresses.all(ArticlePreviewClient::isPublicAddress)
                                }.getOrDefault(false).also { if (hosts.size < 256) hosts[target.host] = it }
                            }
                            if (allowed) return null
                            if (request.isForMainFrame) view.post { failed = true; loading = false }
                            return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                        }
                        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) { loading = true }
                        override fun onPageFinished(view: WebView, url: String?) { loading = false }
                        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                            if (request.isForMainFrame) { failed = true; loading = false }
                        }
                        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                            if (request.isForMainFrame) { failed = true; loading = false }
                        }
                    }
                    setDownloadListener { _, _, _, _, _ -> failed = true }
                    if (ArticlePreviewClient.safeArticleUrl(url) != null) loadUrl(url) else { failed = true; loading = false }
                }
            }, update = { view ->
                if (view.settings.javaScriptEnabled != scripts) {
                    view.settings.javaScriptEnabled = scripts
                    view.settings.domStorageEnabled = scripts
                    failed = false
                    view.reload()
                }
            }, onRelease = { view ->
                view.stopLoading()
                view.webChromeClient = null
                view.destroy()
            })
        }
    }
    if (confirmScripts) AlertDialog(onDismissRequest = { confirmScripts = false },
        title = { Text(stringResource(R.string.news_web_scripts)) },
        text = { Text(stringResource(R.string.news_web_script_warning)) },
        confirmButton = { TextButton(onClick = { scripts = true; confirmScripts = false }) { Text(stringResource(R.string.news_confirm)) } },
        dismissButton = { TextButton(onClick = { confirmScripts = false }) { Text(stringResource(R.string.news_close)) } })
}

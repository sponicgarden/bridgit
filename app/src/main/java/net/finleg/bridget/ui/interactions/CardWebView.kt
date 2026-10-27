package net.finleg.bridget.ui.interactions

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import net.finleg.bridget.interact.CardRules
import net.finleg.bridget.interact.RespondGate
import java.io.ByteArrayInputStream

/**
 * CAP-34: the WebView an agent's HTML card runs in. Configured here in code, never by the page; the page is
 * untrusted. Checklist (each line is enforced below):
 * - JavaScript on; no file or content access, no file-URL access flags; no DOM storage, database, cookies,
 *   geolocation, camera or mic (every permission request denied); Safe Browsing on; mixed content never;
 *   network loads blocked.
 * - No new windows (setSupportMultipleWindows(false), onCreateWindow false); every navigation blocked;
 *   every request answered with an empty 403 (the card itself comes from loadDataWithBaseURL, so it is never
 *   fetched); Bridget's forced CSP is the first thing in the page ([CardRules.prepare]).
 * - No JavaScript dialogs, no fullscreen, no file chooser, no downloads, no autofill, no console logging
 *   (LOG-02): nothing the page does can draw over Bridget's own chrome around the card.
 * - One @JavascriptInterface method, Bridget.respond(json), accepted once ([RespondGate]); the answer is
 *   handed to [onRespond] on the main thread.
 *
 * Call [load] once, and [release] when the screen goes away.
 */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
class CardWebView(context: Context, private val onRespond: (Any) -> Unit, private val onCrash: () -> Unit) : WebView(context) {
    private val gate = RespondGate()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var accepting = true

    init {
        configure(settings)
        // Process-wide: Bridget has no other WebView, and a card must never get or set cookies.
        CookieManager.getInstance().setAcceptCookie(false)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        webViewClient = Client()
        webChromeClient = Chrome()
        setDownloadListener { _, _, _, _, _ -> } // blob: and data: downloads go nowhere
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS // Rahul's saved data is never offered to the page
        isLongClickable = false
        setOnLongClickListener { true }
        isHapticFeedbackEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        addJavascriptInterface(Bridge(), "Bridget")
    }

    /** Loads the agent's HTML with Bridget's head (CSP) in front. Never from a URL. */
    fun load(html: String) = loadDataWithBaseURL(null, CardRules.prepare(html), "text/html", "utf-8", null)

    /** Answers are no longer taken (the card was answered or closed elsewhere). */
    fun stopAccepting() { accepting = false }

    fun release() {
        accepting = false
        main.removeCallbacksAndMessages(null) // an answer queued during teardown is dropped
        stopLoading()
        removeJavascriptInterface("Bridget")
        webChromeClient = null
        (parent as? android.view.ViewGroup)?.removeView(this)
        destroy()
    }

    /** The single method the page can call. Runs on the JavaBridge thread. */
    private inner class Bridge {
        @JavascriptInterface
        fun respond(json: String?) {
            if (!accepting) return
            val v = gate.offer(json) ?: return
            main.post { if (accepting) onRespond(v) }
        }
    }

    private class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
        @Deprecated("Deprecated in Java")
        override fun shouldOverrideUrlLoading(view: WebView, url: String?) = true
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse = blocked()
        @Deprecated("Deprecated in Java")
        override fun shouldInterceptRequest(view: WebView, url: String?): WebResourceResponse = blocked()
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) = handler.cancel()
        override fun onFormResubmission(view: WebView, dontResend: Message, resend: Message) { dontResend.sendToTarget() }
        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {}
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // Never take Bridget down with a card; the screen drops the view and shows a note.
            (view as? CardWebView)?.onCrash?.invoke()
            return true
        }
    }

    private class Chrome : WebChromeClient() {
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message) = false
        override fun onPermissionRequest(request: PermissionRequest) = request.deny()
        override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback) = callback.invoke(origin, false, false)
        override fun onShowCustomView(view: View?, callback: CustomViewCallback) = callback.onCustomViewHidden() // never fullscreen
        override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean { result.cancel(); return true }
        override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean { result.cancel(); return true }
        override fun onJsPrompt(view: WebView, url: String?, message: String?, defaultValue: String?, result: JsPromptResult): Boolean { result.cancel(); return true }
        override fun onJsBeforeUnload(view: WebView, url: String?, message: String?, result: JsResult): Boolean { result.confirm(); return true }
        override fun onShowFileChooser(view: WebView, cb: ValueCallback<Array<android.net.Uri>>, params: FileChooserParams): Boolean { cb.onReceiveValue(null); return true }
        override fun onConsoleMessage(consoleMessage: ConsoleMessage) = true // LOG-02: page output never reaches logcat
    }

    companion object {
        /** Every setting CAP-34 names, applied to [s]. */
        @Suppress("DEPRECATION")
        fun configure(s: WebSettings) {
            s.javaScriptEnabled = true
            s.javaScriptCanOpenWindowsAutomatically = false
            s.setSupportMultipleWindows(false)
            s.allowFileAccess = false
            s.allowContentAccess = false
            s.allowFileAccessFromFileURLs = false
            s.allowUniversalAccessFromFileURLs = false
            s.domStorageEnabled = false
            s.databaseEnabled = false
            s.setGeolocationEnabled(false)
            s.safeBrowsingEnabled = true
            s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            s.blockNetworkLoads = true
            s.blockNetworkImage = true
            s.loadsImagesAutomatically = true // data: images only (CSP img-src data:)
            s.mediaPlaybackRequiresUserGesture = true
            s.saveFormData = false
            s.cacheMode = WebSettings.LOAD_NO_CACHE
            s.setSupportZoom(false)
            s.builtInZoomControls = false
            s.displayZoomControls = false
        }

        /** The fail-closed answer to every request a card makes. */
        fun blocked() = WebResourceResponse("text/plain", "utf-8", 403, "Blocked by Bridget", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    }
}

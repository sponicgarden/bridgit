package cc.bridgit.app.ui.interactions

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
import cc.bridgit.app.interact.CardRules
import cc.bridgit.app.interact.RespondGate
import java.io.ByteArrayInputStream

/**
 * CAP-34: the WebView an agent's HTML card runs in. Configured here in code, never by the page; the page is
 * untrusted. Checklist (each line is enforced below):
 * - JavaScript on; no file or content access, no file-URL access flags; no DOM storage, database, cookies,
 *   geolocation, camera or mic (every permission request denied); Safe Browsing on; mixed content never;
 *   network loads blocked.
 * - No new windows (setSupportMultipleWindows(false), onCreateWindow false); every navigation blocked;
 *   every request answered with an empty 403 (the card itself comes from loadDataWithBaseURL, so it is never
 *   fetched); Bridgit's forced CSP is the first thing in the page ([CardRules.prepare]).
 * - No JavaScript dialogs, no fullscreen, no file chooser, no downloads, no autofill, no console logging
 *   (LOG-02): nothing the page does can draw over Bridgit's own chrome around the card.
 * - One @JavascriptInterface method, Bridget.respond(json), accepted once ([RespondGate]); the answer is
 *   handed to [onRespond] on the main thread.
 *
 * Call [load] once, and [release] when the screen goes away.
 */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
class CardWebView(context: Context, private val onRespond: (Any) -> Unit, private val onCrash: () -> Unit) : WebView(context) {
    @Volatile private var gate = RespondGate()
    private var pageReady = false
    private data class Confirmation(val answer: org.json.JSONObject?, val title: String, val message: String, val delay: Long?)
    private var confirmation: Confirmation? = null
    private var confirmationShown = false
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var accepting = true

    init {
        configure(settings)
        // Process-wide: Bridgit has no other WebView, and a card must never get or set cookies.
        CookieManager.getInstance().setAcceptCookie(false)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        webViewClient = Client()
        webChromeClient = Chrome()
        setDownloadListener { _, _, _, _, _ -> } // blob: and data: downloads go nowhere
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS // The owner's saved data is never offered to the page
        isLongClickable = false
        setOnLongClickListener { true }
        isHapticFeedbackEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        addJavascriptInterface(Bridge(), "Bridget")
    }

    /** The prepared page, served exactly once to the main-frame request for [CARD_URL]. */
    @Volatile private var page: ByteArray? = null

    /**
     * Loads the agent's HTML with Bridgit's head (CSP) in front. The page is answered from memory by
     * shouldInterceptRequest for [CARD_URL] (an .invalid name, so it can never be a real host), once,
     * with the CSP also sent as a response header. Every other request still gets the empty 403.
     * (A data: URL load is itself intercepted on current WebViews, which showed every card blank.)
     */
    fun load(html: String) {
        page = CardRules.prepare(html).toByteArray(Charsets.UTF_8)
        loadUrl(CARD_URL)
    }

    /** The one response the card's WebView ever gets: the page, for its own main-frame load, the first time. */
    private fun serve(request: WebResourceRequest): WebResourceResponse {
        if (!request.isForMainFrame || request.method != "GET" || request.url.toString() != CARD_URL) return blocked()
        val bytes = synchronized(this) { page.also { page = null } } ?: return blocked()
        return WebResourceResponse("text/html", "utf-8", 200, "OK",
            mapOf("Content-Security-Policy" to HEADER_CSP, "Cache-Control" to "no-store"), ByteArrayInputStream(bytes))
    }

    /** Answers are no longer taken (the card was answered or closed elsewhere). */
    fun stopAccepting() { accepting = false }

    /** Confirm only after persistence; replay when reopening history or after the page finishes loading. */
    fun showConfirmation(answer: org.json.JSONObject?, title: String, message: String, closeAfterMs: Long? = null) {
        accepting = false
        if (confirmationShown) return
        confirmation = Confirmation(answer, title, message, closeAfterMs)
        if (!pageReady) return
        confirmationShown = true
        evaluateJavascript(cc.bridgit.app.interact.CardConfirmation.script(answer, title, message, closeAfterMs), null)
    }

    fun retryAnswer() {
        gate = RespondGate()
        accepting = true
    }

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

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
        @Deprecated("Deprecated in Java")
        override fun shouldOverrideUrlLoading(view: WebView, url: String?) = true
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse = serve(request)
        @Deprecated("Deprecated in Java")
        override fun shouldInterceptRequest(view: WebView, url: String?): WebResourceResponse = blocked()
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) = handler.cancel()
        override fun onFormResubmission(view: WebView, dontResend: Message, resend: Message) { dontResend.sendToTarget() }
        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) { pageReady = false }
        override fun onPageFinished(view: WebView, url: String?) {
            pageReady = true
            confirmation?.let { showConfirmation(it.answer, it.title, it.message, it.delay) }
        }
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // Never take Bridgit down with a card; the screen drops the view and shows a note.
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
        const val CARD_URL = "https://card.bridget.invalid/"
        /**
         * The CSP again as a header, plus `sandbox allow-scripts` (headers only; a meta tag can't sandbox).
         * Without allow-same-origin the card and every frame it makes get fresh opaque origins: no IndexedDB or
         * Cache Storage shared between cards, and frames can't reach unguarded WebRTC constructors (Codex review).
         */
        val HEADER_CSP = CardRules.CSP.trimEnd().trimEnd(';') + "; sandbox allow-scripts"
        fun blocked() = WebResourceResponse("text/plain", "utf-8", 403, "Blocked by Bridgit", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    }
}

package com.example.gmscreentv

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.Executors

class TvWebActivity : Activity() {
    private lateinit var webView: WebView
    private val io = Executors.newSingleThreadExecutor()
    private val http = OkHttpClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            webChromeClient = WebChromeClient()
            addJavascriptInterface(NativeBridge(this@TvWebActivity), "NativeBridge")
            loadUrl("file:///android_asset/www/index.html")
        }
        setContentView(webView)

        val prefs = getSharedPreferences("gmscreen", Context.MODE_PRIVATE)
        if (prefs.getString("stb_ip", null).isNullOrEmpty()) {
            webView.postDelayed({ promptForIp() }, 400)
        }
    }

    private fun promptForIp() {
        webView.evaluateJavascript("""
            (function(){
              var ip = prompt("Enter STB IP (e.g., 192.168.1.50)");
              if(ip){ NativeBridge.setStbIp(ip); alert("Saved: "+ip); }
            })();
        """.trimIndent(), null)
    }

    inner class NativeBridge(private val ctx: Context) {
        private val prefs get() = ctx.getSharedPreferences("gmscreen", Context.MODE_PRIVATE)

        @JavascriptInterface
        fun setStbIp(ip: String) {
            prefs.edit().putString("stb_ip", ip.trim()).apply()
        }

        @JavascriptInterface
        fun getStbIp(): String = prefs.getString("stb_ip", "") ?: ""

        @JavascriptInterface
        fun sendRcuKey(key: String) {
            val ip = getStbIp()
            if (ip.isEmpty()) { promptForIp(); return }
            val url = "http://$ip/?RcuKey=$key"
            io.execute {
                try {
                    val req = Request.Builder().url(url).build()
                    http.newCall(req).execute().use { }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
}

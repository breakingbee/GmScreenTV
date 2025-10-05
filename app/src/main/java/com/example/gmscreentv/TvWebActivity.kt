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
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.min

class TvWebActivity : Activity() {
    private lateinit var webView: WebView
    private val io = Executors.newFixedThreadPool(16)
    private val http = OkHttpClient.Builder()
        .connectTimeout(1000, TimeUnit.MILLISECONDS)
        .readTimeout(1500, TimeUnit.MILLISECONDS)
        .build()

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
        webView.evaluateJavascript(
            """
            (function(){
              var ip = prompt("Enter STB IP (e.g., 192.168.1.206)");
              if(ip){ NativeBridge.setStbIp(ip); alert("Saved: "+ip); }
            })();
            """.trimIndent(), null
        )
    }

    inner class NativeBridge(private val ctx: Context) {
        private val prefs get() = ctx.getSharedPreferences("gmscreen", Context.MODE_PRIVATE)

        // --- basics: save/get IP + send RCU key ---
        @JavascriptInterface fun setStbIp(ip: String) { prefs.edit().putString("stb_ip", ip.trim()).apply() }
        @JavascriptInterface fun getStbIp(): String = prefs.getString("stb_ip", "") ?: ""
        @JavascriptInterface fun sendRcuKey(key: String) {
            val ip = getStbIp()
            if (ip.isEmpty()) { promptForIp(); return }
            val url = "http://$ip/?RcuKey=$key"
            io.execute {
                try { http.newCall(Request.Builder().url(url).build()).execute().use { } } catch (_: Exception) {}
            }
        }

        // --- open streams in VLC (or default player) ---
        @JavascriptInterface fun openInVlc(url: String) {
            try {
                val i = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(android.net.Uri.parse(url), "video/*")
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    setPackage("org.videolan.vlc") // prefer VLC if installed
                }
                ctx.startActivity(i)
            } catch (_: Exception) {
                try {
                    val i2 = android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(url)
                    ).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
                    ctx.startActivity(i2)
                } catch (_: Exception) {}
            }
        }

        // --- play by channel ID using your STB’s pattern http://<ip>:8085/player.<ID> ---
        @JavascriptInterface fun openChannelById(channelId: String) {
            val ip = getStbIp()
            val id = channelId.trim()
            if (ip.isEmpty() || id.isEmpty()) return
            openInVlc("http://$ip:8085/player.$id")
        }

        // --- playlist helpers ---
        @JavascriptInterface fun defaultPlaylistUrl(): String {
            val ip = getStbIp()
            return if (ip.isEmpty()) "" else "http://$ip:8085/playlist.m3u"
        }

        /** Download and parse simple M3U/M3U8 into JSON array: [{name,url,id}] */
        @JavascriptInterface fun fetchM3U(url: String): String {
            val items = mutableListOf<Map<String,String>>()
            try {
                val req = Request.Builder().url(url.trim()).build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return "[]"
                    val body = resp.body?.string() ?: return "[]"
                    var pendingName: String? = null
                    body.lineSequence().forEach { raw ->
                        val line = raw.trim()
                        if (line.startsWith("#EXTINF", true)) {
                            val idx = line.indexOf(',')
                            pendingName = if (idx >= 0 && idx+1 < line.length)
                                line.substring(idx+1).trim() else "Channel"
                        } else if (line.isNotEmpty() && !line.startsWith("#")) {
                            val urlLine = line
                            val id = Regex("""player\.([0-9]+)""").find(urlLine)?.groupValues?.getOrNull(1) ?: ""
                            val name = pendingName ?: urlLine
                            items += mapOf("name" to name, "url" to urlLine, "id" to id)
                            pendingName = null
                        }
                    }
                }
            } catch (_: Exception) { /* ignore */ }

            val sb = StringBuilder("[")
            items.forEachIndexed { i, m ->
                if (i > 0) sb.append(',')
                sb.append("{\"name\":").append(JSONObject.quote(m["name"] ?: ""))
                    .append(",\"url\":").append(JSONObject.quote(m["url"] ?: ""))
                    .append(",\"id\":").append(JSONObject.quote(m["id"] ?: ""))
                    .append("}")
            }
            sb.append("]")
            return sb.toString()
        }

        // --- AUTO-DETECT: scan LAN for hosts that expose :8085/playlist.m3u ---
        @JavascriptInterface fun discoverStb(maxHosts: Int): String {
            // returns JSON array: [{ip:"192.168.1.206", name:"STB"}]
            val results = mutableListOf<Map<String,String>>()
            val prefix = localSubnetPrefix() ?: return "[]"

            // probe first maxHosts addresses in the /24 (skip .0 and .255)
            val total = min(maxHosts.coerceAtLeast(1), 254)
            val tasks = (1..254).take(total).map { host ->
                val ip = "$prefix.$host"
                Runnable {
                    // Quick GET for playlist; if 200/OK, accept
                    try {
                        val url = "http://$ip:8085/playlist.m3u"
                        http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                            if (resp.isSuccessful) {
                                synchronized(results) {
                                    results += mapOf("ip" to ip, "name" to "STB :8085")
                                }
                            }
                        }
                    } catch (_: Exception) { /* ignore */ }
                }
            }
            tasks.forEach { io.execute(it) }

            // Wait a short fixed time for probes to finish
            try { Thread.sleep(1800) } catch (_: Exception) {}

            val sb = StringBuilder("[")
            results.forEachIndexed { i, m ->
                if (i > 0) sb.append(',')
                sb.append("{\"ip\":").append(JSONObject.quote(m["ip"] ?: ""))
                    .append(",\"name\":").append(JSONObject.quote(m["name"] ?: ""))
                    .append("}")
            }
            sb.append("]")
            return sb.toString()
        }

        private fun localSubnetPrefix(): String? {
            // returns "192.168.1" for 192.168.1.x ; simple /24 assumption
            try {
                val ifaces = NetworkInterface.getNetworkInterfaces()
                for (ni in ifaces) {
                    val addrs = ni.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val a = addrs.nextElement()
                        if (a is Inet4Address && !a.isLoopbackAddress) {
                            val parts = a.hostAddress.split(".")
                            if (parts.size == 4) return "${parts[0]}.${parts[1]}.${parts[2]}"
                        }
                    }
                }
            } catch (_: Exception) {}
            return null
        }
    }
}

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
import java.net.DatagramPacket
import java.net.DatagramSocket
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

        // ============ BASICS ============
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

        // ============ PLAYBACK ============
        @JavascriptInterface fun openInVlc(url: String) {
            try {
                val i = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(android.net.Uri.parse(url), "video/*")
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    setPackage("org.videolan.vlc") // prefer VLC
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

        // your box pattern: http://<ip>:8085/player.<ID>
        @JavascriptInterface fun openChannelById(channelId: String) {
            val ip = getStbIp()
            val id = channelId.trim()
            if (ip.isEmpty() || id.isEmpty()) return
            openInVlc("http://$ip:8085/player.$id")
        }

        // ============ PLAYLIST HELPERS ============
        @JavascriptInterface fun defaultPlaylistUrl(): String {
            val ip = getStbIp()
            return if (ip.isEmpty()) "" else "http://$ip:8085/playlist.m3u"
        }

        // Try several likely playlist paths on :8085
        @JavascriptInterface
        fun findPlaylistForIp(ip: String): String {
            val candidates = listOf(
                "http://$ip:8085/playlist.m3u",
                "http://$ip:8085/playlist.m3u8",
                "http://$ip:8085/channels.m3u",
                "http://$ip:8085/all.m3u",
                "http://$ip:8085/index.m3u",
                "http://$ip:8085/player.m3u"
            )
            for (u in candidates) {
                try {
                    http.newCall(Request.Builder().url(u).head().build()).execute().use { if (it.isSuccessful) return u }
                    http.newCall(Request.Builder().url(u).build()).execute().use { if (it.isSuccessful) return u }
                } catch (_: Exception) {}
            }
            return ""
        }

        @JavascriptInterface fun findPlaylistForSavedIp(): String {
            val ip = getStbIp()
            if (ip.isEmpty()) return ""
            return findPlaylistForIp(ip)
        }

        // Download+parse M3U/M3U8 to JSON array: [{name,url,id}]
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

        // ============ AUTO-DETECT ============

        // 1) UDP listener for the beacon your STB sends (UDP/25860)
        @JavascriptInterface
        fun discoverUdp25860(timeoutMs: Int): String {
            val found = linkedSetOf<String>()
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket(25860).apply {
                    broadcast = true
                    soTimeout = 500
                    reuseAddress = true
                }
                val end = System.currentTimeMillis() + timeoutMs.coerceAtLeast(1000)
                val buf = ByteArray(1024)
                while (System.currentTimeMillis() < end) {
                    try {
                        val pkt = DatagramPacket(buf, buf.size)
                        socket.receive(pkt)
                        val ip = pkt.address?.hostAddress ?: continue
                        found += ip
                    } catch (_: java.net.SocketTimeoutException) {
                        // continue until timeout
                    } catch (_: Exception) { break }
                }
            } catch (_: Exception) {
            } finally {
                try { socket?.close() } catch (_: Exception) {}
            }

            val sb = StringBuilder("[")
            var first = true
            for (ip in found) {
                val plist = try { findPlaylistForIp(ip) } catch (_: Exception) { "" }
                if (!first) sb.append(',')
                first = false
                sb.append("{\"ip\":").append(JSONObject.quote(ip))
                    .append(",\"name\":\"STB broadcast\"")
                    .append(",\"playlist\":").append(JSONObject.quote(plist))
                    .append("}")
            }
            sb.append("]")
            return sb.toString()
        }

        // 2) HTTP sweep fallback for :8085 (in case UDP is blocked)
        @JavascriptInterface
        fun discoverStb(maxHosts: Int): String {
            val results = mutableListOf<Map<String,String>>()
            val prefix = localSubnetPrefix() ?: return "[]"
            val total = min(maxHosts.coerceAtLeast(1), 254)

            fun probe(ip: String): Map<String,String>? {
                // root
                try {
                    http.newCall(Request.Builder().url("http://$ip:8085/").head().build())
                        .execute().use { if (it.isSuccessful) return mapOf("ip" to ip, "name" to "STB :8085", "playlist" to "") }
                } catch (_: Exception) {}
                // playlist guesses
                val p = findPlaylistForIp(ip)
                return if (p.isNotEmpty()) mapOf("ip" to ip, "name" to "STB :8085", "playlist" to p) else null
            }

            val tasks = (1..254).take(total).map { host ->
                val ip = "$prefix.$host"
                Runnable {
                    val hit = probe(ip)
                    if (hit != null) synchronized(results) { results += hit }
                }
            }
            tasks.forEach { io.execute(it) }
            try { Thread.sleep(2500) } catch (_: Exception) {}

            val sb = StringBuilder("[")
            results.forEachIndexed { i, m ->
                if (i > 0) sb.append(',')
                sb.append("{\"ip\":").append(JSONObject.quote(m["ip"] ?: ""))
                  .append(",\"name\":").append(JSONObject.quote(m["name"] ?: ""))
                  .append(",\"playlist\":").append(JSONObject.quote(m["playlist"] ?: ""))
                  .append("}")
            }
            sb.append("]")
            return sb.toString()
        }

        private fun localSubnetPrefix(): String? {
            // returns "192.168.1" for 192.168.1.x
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

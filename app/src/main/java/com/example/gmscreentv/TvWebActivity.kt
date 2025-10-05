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
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.Inflater

class TvWebActivity : Activity() {
    private lateinit var webView: WebView
    private val io = Executors.newFixedThreadPool(16)
    private val http = OkHttpClient.Builder()
        .connectTimeout(2000, TimeUnit.MILLISECONDS)
        .readTimeout(3000, TimeUnit.MILLISECONDS)
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
    }

    inner class NativeBridge(private val ctx: Context) {
        private val prefs get() = ctx.getSharedPreferences("gmscreen", Context.MODE_PRIVATE)

        @JavascriptInterface fun setStbIp(ip: String) {
            prefs.edit().putString("stb_ip", ip.trim()).apply()
        }

        @JavascriptInterface fun getStbIp(): String =
            prefs.getString("stb_ip", "") ?: ""

        @JavascriptInterface fun openInVlc(url: String) {
            try {
                val i = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(android.net.Uri.parse(url), "video/*")
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    setPackage("org.videolan.vlc")
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

        @JavascriptInterface
        fun fetchChannelsFromStb(): String {
            val ip = getStbIp().trim()
            if (ip.isEmpty()) return "[]"

            val debug = StringBuilder()
            val result = JSONArray()

            try {
                debug.append("Connecting to $ip:4113...\n")
                val sock = Socket(ip, 4113).apply { soTimeout = 4000 }
                val out: OutputStream = sock.getOutputStream()
                val `in`: InputStream = sock.getInputStream()

                val xml = """<Command request="998"><uuid>${UUID.randomUUID()}</uuid><data>Future</data></Command>"""
                val send = xml.toByteArray(Charsets.UTF_8)
                out.write(send)
                out.flush()
                debug.append("Sent XML: $xml\n")

                val buf = ByteArray(1024 * 512)
                val n = `in`.read(buf)
                if (n > 0) {
                    debug.append("Received $n bytes\n")
                    val data = buf.copyOf(n)
                    val inflated = tryInflate(data)
                    val text = String(inflated ?: data, Charsets.UTF_8)
                    debug.append("Decoded text length=${text.length}\n")

                    val chans = parseChannels(text, ip)
                    for (i in 0 until chans.length()) result.put(chans.getJSONObject(i))
                } else {
                    debug.append("No data received\n")
                }

                sock.close()
            } catch (e: Exception) {
                debug.append("Error: ${e.message}\n")
            }

            prefs.edit().putString("last_debug", debug.toString()).apply()
            return result.toString()
        }

        private fun tryInflate(data: ByteArray): ByteArray? {
            return try {
                val inf = Inflater()
                inf.setInput(data)
                val out = ByteArray(2 * 1024 * 1024)
                val n = inf.inflate(out)
                inf.end()
                if (n > 0) out.copyOf(n) else null
            } catch (_: Exception) {
                null
            }
        }

        private fun parseChannels(text: String, ip: String): JSONArray {
            val out = JSONArray()
            val regex = Regex("""(\[[0-9]{14,20}\])""") // detect IDs
            val lines = text.split("\n", "\r", ";")
            var idx = 0
            for (line in lines) {
                val clean = line.trim()
                if (clean.isNotEmpty() && clean.length > 3) {
                    val id = regex.find(clean)?.value?.replace("[", "")?.replace("]", "") ?: "id_$idx"
                    out.put(
                        JSONObject()
                            .put("name", clean.take(40))
                            .put("id", id)
                            .put("url", "http://$ip:8085/player.$id")
                    )
                    idx++
                }
            }
            return out
        }

        @JavascriptInterface
        fun getLastDebug(): String = prefs.getString("last_debug", "") ?: ""
    }
}

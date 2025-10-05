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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.Inflater
import kotlin.math.min

class TvWebActivity : Activity() {
    private lateinit var webView: WebView
    private val io = Executors.newFixedThreadPool(16)
    private val http = OkHttpClient.Builder()
        .connectTimeout(1200, TimeUnit.MILLISECONDS)
        .readTimeout(2000, TimeUnit.MILLISECONDS)
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

        // ===== BASICS =====
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

        // ===== PLAYBACK =====
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

        // your box pattern: http://<ip>:8085/player.<ID>
        @JavascriptInterface fun openChannelById(channelId: String) {
            val ip = getStbIp()
            val id = channelId.trim()
            if (ip.isEmpty() || id.isEmpty()) return
            openInVlc("http://$ip:8085/player.$id")
        }

        // ===== “MY CHANNELS” (optional, saved locally) =====
        @JavascriptInterface fun saveMyChannels(json: String) { prefs.edit().putString("my_channels", json).apply() }
        @JavascriptInterface fun loadMyChannels(): String = prefs.getString("my_channels", "[]") ?: "[]"

        // ===== AUTO-DETECT (UDP 25860) =====
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
                    } catch (_: Exception) { break }
                }
            } catch (_: Exception) {
            } finally {
                try { socket?.close() } catch (_: Exception) {}
            }

            val sb = StringBuilder("[")
            var first = true
            for (ip in found) {
                if (!first) sb.append(',')
                first = false
                sb.append("{\"ip\":").append(JSONObject.quote(ip))
                    .append(",\"name\":\"STB broadcast\"")
                    .append(",\"playlist\":\"\"}")
            }
            sb.append("]")
            return sb.toString()
        }

        // ===== CONTROL PROTOCOL (experimental pull from STB) =====
        // We probe common control ports and send framed JSON requests:
        //   Start00000LENEnd + UTF-8(JSON)
        // Then try to zlib-inflate the reply and extract channel entries.
        @JavascriptInterface
        fun fetchChannelsFromStb(): String {
            val ip = getStbIp().trim()
            if (ip.isEmpty()) return "[]"

            // candidate ports seen in practice (from your captures/logs patterns)
            val ports = intArrayOf(20000, 4113, 8888)

            // candidate requests (match PC app flow)
            val frames = listOf(
                jsonFrame("""{"request":"1012"}"""),                            // handshake/info
                jsonFrame("""{"FromIndex":"0","ToIndex":"199","request":"0"}""") // ask list
            )

            // try each port until one yields channels
            for (port in ports) {
                try {
                    val chans = talkToControl(ip, port, frames)
                    if (chans.length() > 0) return chans.toString()
                } catch (_: Exception) {
                    // try next port
                }
            }
            return "[]"
        }

        private fun jsonFrame(json: String): ByteArray {
            val body = json.trim().toByteArray(Charsets.UTF_8)
            val len = body.size
            val header = "Start" + String.format("%07d", len) + "End"
            val prefix = header.toByteArray(Charsets.UTF_8)
            return prefix + body
        }

        private fun readAllAvailable(`in`: InputStream, waitMs: Long = 600L): ByteArray {
            val buf = ByteArray(8192)
            val out = ArrayList<Byte>()
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < waitMs) {
                val n = try { if (`in`.available() > 0) `in`.read(buf) else -1 } catch (e: Exception) { -1 }
                if (n != null && n > 0) {
                    for (i in 0 until n) out.add(buf[i])
                } else {
                    try { Thread.sleep(40) } catch (_: Exception) {}
                }
            }
            return out.toByteArray()
        }

        private fun tryInflate(data: ByteArray): ByteArray? {
            return try {
                val inf = Inflater()
                inf.setInput(data)
                val out = ByteArray(1024 * 1024)
                val n = inf.inflate(out)
                inf.end()
                if (n > 0) out.copyOf(n) else null
            } catch (e: Exception) { null }
        }

        private fun parseChannelsFromText(text: String, ip: String): JSONArray {
            // Try JSON first (array or object with list)
            try {
                if (text.trim().startsWith("[")) {
                    val arr = JSONArray(text)
                    val out = JSONArray()
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val name = o.optString("name", "")
                        val id = o.optString("id", o.optString("chan_id",""))
                        if (id.isNotEmpty()) {
                            val url = "http://$ip:8085/player.$id"
                            out.put(JSONObject().put("name", if (name.isNotEmpty()) name else "Channel $id")
                                                 .put("id", id)
                                                 .put("url", url))
                        }
                    }
                    if (out.length() > 0) return out
                } else if (text.trim().startsWith("{")) {
                    val obj = JSONObject(text)
                    val lists = arrayOf("channels","list","items","programs")
                    for (key in lists) {
                        if (obj.has(key)) {
                            val arr = obj.optJSONArray(key) ?: continue
                            val out = JSONArray()
                            for (i in 0 until arr.length()) {
                                val o = arr.optJSONObject(i) ?: continue
                                val name = o.optString("name", "")
                                val id = o.optString("id", o.optString("chan_id",""))
                                if (id.isNotEmpty()) {
                                    val url = "http://$ip:8085/player.$id"
                                    out.put(JSONObject().put("name", if (name.isNotEmpty()) name else "Channel $id")
                                                         .put("id", id)
                                                         .put("url", url))
                                }
                            }
                            if (out.length() > 0) return out
                        }
                    }
                }
            } catch (_: Exception) {}

            // Fallback: pull IDs from any text
            val out = JSONArray()
            val seen = HashSet<String>()
            val re = Regex("""player\.([0-9]+)""")
            re.findAll(text).forEach { m ->
                val id = m.groupValues[1]
                if (seen.add(id)) {
                    out.put(JSONObject().put("name", "Channel $id")
                                        .put("id", id)
                                        .put("url", "http://$ip:8085/player.$id"))
                }
            }
            return out
        }

        private fun talkToControl(ip: String, port: Int, frames: List<ByteArray>): JSONArray {
            var sock: Socket? = null
            return try {
                sock = Socket(ip, port).apply { soTimeout = 1500 }
                val out: OutputStream = sock.getOutputStream()
                val `in`: InputStream = sock.getInputStream()

                // send frames
                frames.forEach { out.write(it); out.flush(); try { Thread.sleep(40) } catch (_: Exception) {} }

                // read reply burst
                val raw = readAllAvailable(`in`, 900)
                if (raw.isEmpty()) return JSONArray()

                // try zlib inflate straight
                val inflated = tryInflate(raw) ?: raw

                // if still opaque, try to locate a zlib stream inside by scanning for 0x78 0x9C or 0x78 0xDA
                val maybe = if (inflated === raw) {
                    val idx = raw.indexOfSubsequence(byteArrayOf(0x78.toByte(), 0x9C.toByte()))
                              .takeIf { it >= 0 } ?: raw.indexOfSubsequence(byteArrayOf(0x78.toByte(), 0xDA.toByte()))
                    if (idx >= 0) tryInflate(raw.copyOfRange(idx, raw.size)) else null
                } else null

                val text = String((maybe ?: inflated), Charsets.UTF_8)

                // parse channels
                parseChannelsFromText(text, ip)
            } catch (_: Exception) {
                JSONArray()
            } finally {
                try { sock?.close() } catch (_: Exception) {}
            }
        }

        // ===== HTTP SWEEP FALLBACK (still available) =====
        @JavascriptInterface
        fun discoverStb(maxHosts: Int): String {
            val results = mutableListOf<Map<String,String>>()
            val prefix = localSubnetPrefix() ?: return "[]"
            val total = min(maxHosts.coerceAtLeast(1), 254)

            fun probe(ip: String): Map<String,String>? {
                try {
                    http.newCall(Request.Builder().url("http://$ip:8085/").build())
                        .execute().use { if (it.isSuccessful) return mapOf("ip" to ip, "name" to "STB :8085", "playlist" to "") }
                } catch (_: Exception) {}
                return null
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
                    .append(",\"playlist\":\"\"}")
            }
            sb.append("]")
            return sb.toString()
        }

        private fun localSubnetPrefix(): String? {
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

// small helper: find subsequence
private fun ByteArray.indexOfSubsequence(needle: ByteArray): Int {
    if (needle.isEmpty()) return 0
    outer@ for (i in 0..this.size - needle.size) {
        for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
        return i
    }
    return -1
}


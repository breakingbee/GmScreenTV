package com.example.gmscreentv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import kotlin.math.max
import kotlin.math.min

class TvWebActivity : Activity() {
    private lateinit var webView: WebView
    private val io = Executors.newFixedThreadPool(16)
    private val http = OkHttpClient.Builder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
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

        // prompt for IP on first run
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

    // ---------------------- JS bridge ----------------------
    inner class NativeBridge(private val ctx: Context) {
        private val prefs get() = ctx.getSharedPreferences("gmscreen", Context.MODE_PRIVATE)

        // basics
        @JavascriptInterface fun setStbIp(ip: String) { prefs.edit().putString("stb_ip", ip.trim()).apply() }
        @JavascriptInterface fun getStbIp(): String = prefs.getString("stb_ip", "") ?: ""

        // open URL in VLC only (fallback to generic VIEW if VLC missing)
        @JavascriptInterface fun openInVlc(url: String) {
            try {
                val i = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.parse(url), "video/*")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    setPackage("org.videolan.vlc")
                }
                ctx.startActivity(i)
            } catch (_: Exception) {
                try {
                    val i2 = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    ctx.startActivity(i2)
                } catch (_: Exception) { }
            }
        }

        // play directly by ServiceID (for manual testing)
        @JavascriptInterface fun openChannelById(channelId: String) {
            val ip = getStbIp().trim()
            val id = channelId.trim()
            if (ip.isEmpty() || id.isEmpty()) return
            openInVlc("http://$ip:8085/player.$id")
        }

        // ---------------- UDP autodetect (25860) ----------------
        @JavascriptInterface fun discoverUdp25860(timeoutMs: Int): String {
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
            } finally { try { socket?.close() } catch (_: Exception) {} }

            val sb = StringBuilder("[")
            var first = true
            for (ip in found) {
                if (!first) sb.append(',')
                first = false
                sb.append("{\"ip\":").append(JSONObject.quote(ip))
                    .append(",\"name\":\"STB (UDP 25860)\"")
                    .append(",\"playlist\":\"\"}")
            }
            sb.append("]")
            return sb.toString()
        }

        // ---------------- control socket helpers ----------------
        private fun jsonFrame(json: String): ByteArray {
            val body = json.trim().toByteArray(Charsets.UTF_8)
            val header = "Start" + String.format("%07d", body.size) + "End"
            return header.toByteArray(Charsets.UTF_8) + body
        }

        private fun readExact(`in`: InputStream, n: Int): ByteArray? {
            var remaining = n
            val out = ByteArray(n)
            var off = 0
            while (remaining > 0) {
                val r = try { `in`.read(out, off, remaining) } catch (_: Exception) { -1 }
                if (r <= 0) return null
                off += r
                remaining -= r
            }
            return out
        }

        private fun beInt(b: ByteArray, o: Int): Int {
            return ((b[o+3].toInt() and 0xFF) shl 24) or
                   ((b[o+2].toInt() and 0xFF) shl 16) or
                   ((b[o+1].toInt() and 0xFF) shl 8)  or
                   ( b[o+0].toInt() and 0xFF)
        }

        private fun tryInflate(data: ByteArray): ByteArray? {
            return try {
                val inf = Inflater()
                inf.setInput(data)
                val out = ByteArray(2 * 1024 * 1024)
                val n = inf.inflate(out)
                inf.end()
                if (n > 0) out.copyOf(n) else null
            } catch (_: Exception) { null }
        }

        private fun readGcdhFrames(`in`: InputStream, totalWaitMs: Long, debug: StringBuilder)
            : List<Pair<Int,ByteArray>> {
            val chunks = ArrayList<Pair<Int,ByteArray>>()
            val end = System.currentTimeMillis() + max(1500L, totalWaitMs)
            while (System.currentTimeMillis() < end) {
                if (`in`.available() < 16) { try { Thread.sleep(20) } catch (_: Exception) {}; continue }
                val hdr = readExact(`in`, 16) ?: break
                if (!(hdr[0]=='G'.code.toByte() && hdr[1]=='C'.code.toByte() && hdr[2]=='D'.code.toByte() && hdr[3]=='H'.code.toByte())) {
                    debug.append("Non-GCDH header seen\n"); break
                }
                val payloadLen = beInt(hdr, 4)
                val msgType   = beInt(hdr, 8)
                val extra     = beInt(hdr,12)
                debug.append("GCDH frame: len=$payloadLen type=$msgType extra=$extra\n")
                if (payloadLen <= 0 || payloadLen > 8*1024*1024) {
                    debug.append("Aborting: bad len\n"); break
                }
                val comp = readExact(`in`, payloadLen) ?: break
                val inflated = tryInflate(comp) ?: comp
                chunks += msgType to inflated
            }
            return chunks
        }

        // store last debug
        @JavascriptInterface fun getLastDebug(): String = prefs.getString("last_debug", "") ?: ""

        private fun saveDebug(s: String) {
            prefs.edit().putString("last_debug", s.take(20000)).apply()
        }

        // ---------------- channel list fetch (same as before) ----------------
        @JavascriptInterface
        fun fetchChannelsFromStb(): String {
            val ip = getStbIp().trim()
            if (ip.isEmpty()) return "[]"
            val debug = StringBuilder()
            val frames = mutableListOf<ByteArray>()

            frames += jsonFrame("""{"request":"1012"}""") // handshake
            frames += jsonFrame("""{"request":"1007","IsFavList":"0","SelectListType":"0"}""") // TV list
            arrayOf(0 to 199, 200 to 399, 400 to 799).forEach { (a,b) ->
                frames += jsonFrame("""{"request":"0","FromIndex":"$a","ToIndex":"$b"}""")
            }
            frames += jsonFrame("""{"request":"26"}""") // heartbeat

            val ports = intArrayOf(20000, 4113, 8888)
            for (port in ports) {
                try {
                    debug.append("Connecting $ip:$port\n")
                    val sock = Socket(ip, port).apply { soTimeout = 2500 }
                    val out: OutputStream = sock.getOutputStream()
                    val `in`: InputStream = sock.getInputStream()
                    for (f in frames) { out.write(f); out.flush(); try { Thread.sleep(50) } catch (_: Exception) {} }
                    val framesIn = readGcdhFrames(`in`, 2500L, debug)
                    sock.close()
                    val arr = parseChannelsFromTexts(framesIn, ip, debug)
                    if (arr.length() > 0) {
                        saveDebug(debug.toString())
                        return arr.toString()
                    }
                } catch (e: Exception) { debug.append("Error $ip:$port -> ${e.message}\n") }
            }
            saveDebug(debug.toString())
            return "[]"
        }

        private fun parseChannelsFromTexts(all: List<Pair<Int,ByteArray>>, ip: String, debug: StringBuilder): JSONArray {
            val out = JSONArray()
            val seen = HashSet<String>()
            var idxCounter = 0
            for ((type, bytes) in all) {
                val text = String(bytes, Charsets.UTF_8)
                debug.append("-- type=$type textLen=${text.length}\n")
                try {
                    if (text.trim().startsWith("[")) {
                        val arr = JSONArray(text)
                        for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i) ?: continue
                            val name = o.optString("ServiceName", o.optString("name",""))
                            val sid = o.optString("ServiceID", o.optString("id", ""))
                            val idx = o.optInt("ServiceIndex", o.optInt("index", -1))
                            val showIdx = if (idx >= 0) idx else idxCounter
                            idxCounter++

                            val label = if (name.isNotEmpty()) name else "Channel $showIdx"
                            if (seen.add("$label#$showIdx")) {
                                out.put(JSONObject()
                                    .put("name", label)
                                    .put("id", sid) // may be empty/wrong; we will tune by index
                                    .put("index", showIdx)
                                    .put("url", "http://$ip:8085/player.$sid"))
                            }
                        }
                    }
                } catch (_: Exception) { /* ignore */ }
            }
            return out
        }

        // ---------------- tune + get current URL (1000 -> 1009) ----------------
        @JavascriptInterface
        fun tuneAndPlay(index: Int) {
            val ip = getStbIp().trim()
            if (ip.isEmpty()) return
            io.execute {
                val debug = StringBuilder()
                try {
                    // 1) send tune-by-index
                    val tune = jsonFrame("""{"request":"1000","ChannelIndex":"$index"}""")
                    // 2) ask for current player url
                    val askUrl = jsonFrame("""{"request":"1009"}""")

                    val sock = Socket(ip, 20000).apply { soTimeout = 4000 }
                    val out = sock.getOutputStream()
                    val `in` = sock.getInputStream()

                    out.write(tune); out.flush(); Thread.sleep(120)
                    out.write(askUrl); out.flush()

                    val framesIn = readGcdhFrames(`in`, 3500L, debug)
                    sock.close()

                    var playUrl: String? = null
                    for ((t, b) in framesIn) {
                        val s = String(b, Charsets.UTF_8)
                        if (t == 1009 || s.contains("\"url\"")) {
                            try {
                                val arr = if (s.trim().startsWith("[")) JSONArray(s) else JSONArray("[$s]")
                                for (i in 0 until arr.length()) {
                                    val o = arr.optJSONObject(i) ?: continue
                                    val u = o.optString("url", "")
                                    if (u.contains("/player.")) { playUrl = u; break }
                                }
                            } catch (_: Exception) { /* ignore */ }
                        }
                    }
                    if (playUrl == null) {
                        // fallback: just open current player endpoint directly if box exposes it
                        playUrl = "http://$ip:8085/player"
                    }

                    saveDebug(debug.append("\nResolved playUrl=$playUrl\n").toString())

                    if (playUrl != null) openInVlc(playUrl!!)
                } catch (e: Exception) {
                    saveDebug(debug.append("Tune error: ${e.message}\n").toString())
                }
            }
        }

        // ---------------- HTTP sweep autodiscovery (optional) ----------------
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
                Runnable { probe(ip)?.let { synchronized(results) { results += it } } }
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

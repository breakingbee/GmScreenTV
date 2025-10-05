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

        // ===== VLC =====
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
        @JavascriptInterface fun openChannelById(channelId: String) {
            val ip = getStbIp()
            val id = channelId.trim()
            if (ip.isEmpty() || id.isEmpty()) return
            openInVlc("http://$ip:8085/player.$id")
        }

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
                    .append(",\"name\":\"STB broadcast\",\"playlist\":\"\"}")
            }
            sb.append("]")
            return sb.toString()
        }

        // ========= CONTROL: PORT 20000 (legacy list) =========
        @JavascriptInterface
        fun fetchChannelsFromStb(): String {
            val ip = getStbIp().trim()
            if (ip.isEmpty()) return "[]"
            val port = 20000
            val seq = mutableListOf<ByteArray>().apply {
                add(jsonFrame("""{"request":"1012"}"""))
                add(jsonFrame("""{"request":"1007","IsFavList":"0","SelectListType":"0"}"""))
                listOf(0 to 199, 200 to 399, 400 to 799).forEach { (a,b) ->
                    add(jsonFrame("""{"request":"0","FromIndex":"$a","ToIndex":"$b"}"""))
                }
                add(jsonFrame("""{"request":"26"}"""))
            }
            val dbg = StringBuilder()
            val arr = talkToControlParse(ip, port, seq, dbg)
            prefs.edit().putString("last_debug", dbg.toString().take(12000)).apply()
            return arr.toString()
        }

        // ========= CONTROL: 4113 (XML login) -> 8888 (GCDH control) =========
        @JavascriptInterface
        fun getCurrentPlayUrl(): String {
            val ip = getStbIp().trim()
            if (ip.isEmpty()) return ""
            val dbg = StringBuilder()

            // 1) XML login to 4113
            val logged = loginOver4113(ip, 4113, dbg)
            if (!logged) {
                prefs.edit().putString("last_debug", dbg.toString().take(12000)).apply()
                return ""
            }

            // 2) Open 8888 and request current play URL (1009)
            val frames = listOf(
                jsonFrame("""{"request":"1012"}"""),
                jsonFrame("""{"request":"1009"}""")
            )
            val chunks = talkToControlAll(ip, 8888, frames, dbg)

            prefs.edit().putString("last_debug", dbg.toString().take(12000)).apply()

            for ((type, bytes) in chunks) {
                val t = String(bytes, Charsets.UTF_8).trim()
                if (type == 1009 || type == 0 || type == 12) {
                    if (t.startsWith("[")) {
                        try {
                            val arr = JSONArray(t)
                            val obj = arr.optJSONObject(0)
                            val ok = obj?.optString("success") == "1"
                            val url = obj?.optString("url") ?: ""
                            if (ok && url.startsWith("http")) return url
                        } catch (_: Exception) {}
                    }
                }
            }
            return ""
        }

        @JavascriptInterface fun getLastDebug(): String = prefs.getString("last_debug", "") ?: ""

        // ---------- helpers ----------
        private fun jsonFrame(json: String): ByteArray {
            val body = json.trim().toByteArray(Charsets.UTF_8)
            val len = body.size
            val header = "Start" + String.format("%07d", len) + "End"
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

        private fun readGcdhFrames(`in`: InputStream, totalWaitMs: Long, debug: StringBuilder): List<Pair<Int,ByteArray>> {
            val chunks = ArrayList<Pair<Int,ByteArray>>()
            val end = System.currentTimeMillis() + max(2500L, totalWaitMs)
            while (System.currentTimeMillis() < end) {
                if (`in`.available() < 16) { try { Thread.sleep(20) } catch (_: Exception) {}; continue }
                val hdr = readExact(`in`, 16) ?: break
                if (!(hdr[0]=='G'.code.toByte() && hdr[1]=='C'.code.toByte() && hdr[2]=='D'.code.toByte() && hdr[3]=='H'.code.toByte())) {
                    debug.append("Non-GCDH header\n")
                    break
                }
                val payloadLen = beInt(hdr, 4)
                val msgType   = beInt(hdr, 8)
                val extra     = beInt(hdr,12)
                debug.append("GCDH frame: len=$payloadLen type=$msgType extra=$extra\n")

                if (payloadLen < 0 || payloadLen > 8*1024*1024) {
                    debug.append("Aborting: bad len\n"); break
                }
                val comp = if (payloadLen == 0) ByteArray(0) else readExact(`in`, payloadLen) ?: break
                val inflated = tryInflate(comp) ?: comp
                chunks += msgType to inflated
                // quick peek
                debug.append("-- type=$msgType textLen=${inflated.size}\n")
            }
            return chunks
        }

        private fun tryInflate(data: ByteArray): ByteArray? {
            return try {
                if (data.isEmpty()) return ByteArray(0)
                val inf = Inflater()
                inf.setInput(data)
                val out = ByteArray(2 * 1024 * 1024)
                val n = inf.inflate(out)
                inf.end()
                if (n >= 0) out.copyOf(n) else null
            } catch (_: Exception) { null }
        }

        private fun parseChannelsFromTexts(all: List<Pair<Int,ByteArray>>, ip: String, debug: StringBuilder): JSONArray {
            val out = JSONArray()
            val seen = HashSet<String>()
            for ((type, bytes) in all) {
                val text = String(bytes, Charsets.UTF_8)
                debug.append("-- type=$type textLen=${text.length}\n")
                try {
                    if (text.trim().startsWith("[")) {
                        val arr = JSONArray(text)
                        for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i) ?: continue
                            val name = o.optString("ServiceName", o.optString("name",""))
                            val id = o.optString("ServiceID", o.optString("id", o.optString("chan_id","")))
                            val idx = o.optString("ServiceIndex", o.optString("index",""))
                            if (id.isNotEmpty() && seen.add(id)) {
                                out.put(JSONObject()
                                    .put("name", if (name.isNotEmpty()) name else "Channel $id")
                                    .put("id", id)
                                    .put("idx", idx)
                                    .put("url", "http://$ip:8085/player.$id"))
                            }
                        }
                    }
                } catch (_: Exception) {}

                Regex("""player\.([0-9]+)""").findAll(text).forEach { m ->
                    val id = m.groupValues[1]
                    if (seen.add(id)) {
                        out.put(JSONObject().put("name", "Channel $id").put("id", id).put("url", "http://$ip:8085/player.$id"))
                    }
                }
            }
            return out
        }

        // ---- sockets ----
        private fun talkToControlAll(
            ip: String,
            port: Int,
            frames: List<ByteArray>,
            debug: StringBuilder
        ): List<Pair<Int,ByteArray>> {
            var sock: Socket? = null
            return try {
                debug.append("Connecting $ip:$port\n")
                sock = Socket(ip, port).apply { soTimeout = 3500 }
                val out: OutputStream = sock.getOutputStream()
                val `in`: InputStream = sock.getInputStream()
                for (f in frames) { out.write(f); out.flush(); try { Thread.sleep(50) } catch (_: Exception) {} }
                readGcdhFrames(`in`, totalWaitMs = 3500L, debug)
            } catch (e: Exception) {
                debug.append("Error $ip:$port -> ${e.message}\n")
                emptyList()
            } finally {
                try { sock?.close() } catch (_: Exception) {}
            }
        }

        private fun talkToControlParse(
            ip: String,
            port: Int,
            frames: List<ByteArray>,
            debug: StringBuilder
        ): JSONArray {
            val framesIn = talkToControlAll(ip, port, frames, debug)
            if (framesIn.isEmpty()) {
                debug.append("No GCDH frames read on $port\n")
                return JSONArray()
            }
            val chans = parseChannelsFromTexts(framesIn, ip, debug)
            if (chans.length() == 0) debug.append("Parsed 0 channels on $port\n")
            return chans
        }

        private fun loginOver4113(ip: String, port: Int, debug: StringBuilder): Boolean {
            var sock: Socket? = null
            return try {
                debug.append("Connecting $ip:$port (XML login)\n")
                sock = Socket(ip, port).apply { soTimeout = 3000 }
                val out: OutputStream = sock.getOutputStream()
                val `in`: InputStream = sock.getInputStream()

                val uuid = UUID.randomUUID().toString()
                val xml = "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>" +
                        "<Command request=\"998\"><uuid>$uuid</uuid><data>Future</data></Command>"
                val payload = xml.toByteArray(Charsets.UTF_8)

                // PC log shows they send 108 bytes; device doesn’t need a length prefix,
                // just the plain XML seems enough (many firmwares accept both).
                out.write(payload); out.flush()
                Thread.sleep(80)

                // Read whatever comes back (we just want the magic banner/echo)
                val buf = ByteArray(512)
                val n = try { `in`.read(buf) } catch (_: Exception) { -1 }
                val s = if (n > 0) String(buf, 0, n, Charsets.UTF_8) else ""
                debug.append("XML login reply bytes=$n\n")
                if (n <= 0) false else true
            } catch (e: Exception) {
                debug.append("XML login error: ${e.message}\n")
                false
            } finally {
                try { sock?.close() } catch (_: Exception) {}
            }
        }

        // ===== HTTP SWEEP (optional) =====
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
                Runnable { probe(ip)?.let { synchronized(results){ results += it } } }
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

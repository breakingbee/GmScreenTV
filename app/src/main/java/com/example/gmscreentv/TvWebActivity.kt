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
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import kotlin.math.max
import kotlin.math.min

class TvWebActivity : Activity() {
    private lateinit var webView: WebView
    private val io = Executors.newFixedThreadPool(16)
    private val http = OkHttpClient.Builder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(4000, TimeUnit.MILLISECONDS)
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
            io.execute { try { http.newCall(Request.Builder().url(url).build()).execute().use { } } catch (_: Exception) {} }
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

        @JavascriptInterface fun openChannelById(channelId: String) {
            val ip = getStbIp()
            val id = channelId.trim()
            if (ip.isEmpty() || id.isEmpty()) return
            openInVlc("http://$ip:8085/player.$id")
        }

        // Optional best-effort zap then play
        @JavascriptInterface fun trySwitchChannel(channelId: String, serviceIndex: Int) {
            val ip = getStbIp().trim()
            if (ip.isEmpty()) return
            io.execute {
                val attempts = listOf(
                    """{"request":"12","ServiceID":"$channelId"}""",
                    """{"request":"12","ServiceIndex":"$serviceIndex"}""",
                    """{"request":"20","ServiceID":"$channelId"}""",
                    """{"request":"23","ServiceID":"$channelId"}"""
                ).map { jsonFrame(it) }
                val debug = StringBuilder()
                intArrayOf(20000, 4113, 8888).forEach { p -> runCatching { talkOnce(ip, p, attempts, debug) } }
                listOf(
                    "http://$ip:8085/?OpenChannel=$channelId",
                    "http://$ip:8085/?ServiceID=$channelId",
                    "http://$ip:8085/?Play=$channelId"
                ).forEach { u -> runCatching { http.newCall(Request.Builder().url(u).build()).execute().use { } } }
            }
        }

        // ===== AUTO-DETECT (UDP 25860) =====
        @JavascriptInterface
        fun discoverUdp25860(timeoutMs: Int): String {
            val found = linkedSetOf<String>()
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket(25860).apply {
                    broadcast = true; soTimeout = 500; reuseAddress = true
                }
                val end = System.currentTimeMillis() + timeoutMs.coerceAtLeast(1000)
                val buf = ByteArray(1024)
                while (System.currentTimeMillis() < end) {
                    try {
                        val pkt = DatagramPacket(buf, buf.size)
                        socket.receive(pkt)
                        pkt.address?.hostAddress?.let { found += it }
                    } catch (_: java.net.SocketTimeoutException) {} catch (_: Exception) { break }
                }
            } catch (_: Exception) {} finally { runCatching { socket?.close() } }

            val sb = StringBuilder("[")
            var first = true
            for (ip in found) {
                if (!first) sb.append(','); first = false
                sb.append("{\"ip\":").append(JSONObject.quote(ip))
                    .append(",\"name\":\"STB broadcast\",\"playlist\":\"\"}")
            }
            sb.append("]")
            return sb.toString()
        }

        // ========= CONTROL PROTOCOL =========

        @JavascriptInterface
        fun fetchChannelsFromStb(): String {
            val ip = getStbIp().trim()
            if (ip.isEmpty()) return "[]"

            val seq = mutableListOf<ByteArray>()
            seq += jsonFrame("""{"request":"1012"}""")                                   // hello
            seq += jsonFrame("""{"request":"1007","IsFavList":"0","SelectListType":"0"}""") // list mode
            arrayOf(0 to 199, 200 to 399, 400 to 799).forEach { (a,b) ->
                seq += jsonFrame("""{"request":"0","FromIndex":"$a","ToIndex":"$b"}""")
            }
            seq += jsonFrame("""{"request":"26"}""") // heartbeat

            val dbg = StringBuilder()
            for (port in intArrayOf(20000, 4113, 8888)) {
                val res = runCatching { talkToControl(ip, port, seq, dbg) }.getOrNull()
                if (res != null && res.length() > 0) {
                    prefs.edit().putString("last_debug", dbg.toString().take(12000)).apply()
                    return res.toString()
                }
            }
            prefs.edit().putString("last_debug", dbg.toString().take(12000)).apply()
            return "[]"
        }

        @JavascriptInterface fun getLastDebug(): String = prefs.getString("last_debug", "") ?: ""

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
                off += r; remaining -= r
            }
            return out
        }

        private fun beInt(b: ByteArray, o: Int): Int {
            return ((b[o+3].toInt() and 0xFF) shl 24) or
                   ((b[o+2].toInt() and 0xFF) shl 16) or
                   ((b[o+1].toInt() and 0xFF) shl 8)  or
                   ( b[o+0].toInt() and 0xFF)
        }

        /**
         * IMPORTANT FIX:
         * - treat payloadLen == 0 as a valid keep-alive (do NOT abort);
         * - only abort on clearly bad (negative or absurdly large) lengths;
         * - keep listening up to ~12s so the filtered (per-satellite) list can arrive.
         */
        private fun readGcdhFrames(`in`: InputStream, totalWaitMs: Long, debug: StringBuilder): List<Pair<Int,ByteArray>> {
            val chunks = ArrayList<Pair<Int,ByteArray>>()
            val end = System.currentTimeMillis() + max(12000L, totalWaitMs)
            while (System.currentTimeMillis() < end) {
                if (`in`.available() < 16) { try { Thread.sleep(60) } catch (_: Exception) {}; continue }
                val hdr = readExact(`in`, 16) ?: break
                if (!(hdr[0]=='G'.code.toByte() && hdr[1]=='C'.code.toByte() && hdr[2]=='D'.code.toByte() && hdr[3]=='H'.code.toByte())) {
                    debug.append("Non-GCDH header seen: ${hdr.copyOf(4).toString(Charsets.US_ASCII)}\n")
                    continue
                }
                val payloadLen = beInt(hdr, 4)
                val msgType   = beInt(hdr, 8)
                val extra     = beInt(hdr,12)
                debug.append("GCDH frame: len=$payloadLen type=$msgType extra=$extra\n")

                // abort only if negative or crazy big
                if (payloadLen < 0 || payloadLen > 8*1024*1024) {
                    debug.append("Aborting: bad len ($payloadLen)\n")
                    break
                }

                if (payloadLen == 0) {
                    // keep-alive / header-only: record empty payload and continue waiting
                    chunks += msgType to ByteArray(0)
                    continue
                }

                val comp = readExact(`in`, payloadLen) ?: break
                val inflated = inflateSmart(comp, debug) ?: comp
                chunks += msgType to inflated
            }
            return chunks
        }

        private fun inflateSmart(data: ByteArray, debug: StringBuilder): ByteArray? {
            // 1) direct zlib
            try {
                val out = ByteArray(2 * 1024 * 1024)
                val inf = Inflater()
                inf.setInput(data)
                val n = inf.inflate(out)
                inf.end()
                if (n > 0) return out.copyOf(n)
            } catch (_: DataFormatException) { } catch (_: Exception) { }

            // 2) scan for zlib header (0x78 0x01/0x9C/0xDA)
            var i = 0
            while (i < data.size - 2) {
                if (data[i] == 0x78.toByte()) {
                    val flg = data[i+1]
                    if (flg == 0x01.toByte() || flg == 0x9C.toByte() || flg == 0xDA.toByte()) {
                        try {
                            val out = ByteArray(2 * 1024 * 1024)
                            val inf = Inflater()
                            inf.setInput(data, i, data.size - i)
                            val n = inf.inflate(out)
                            inf.end()
                            if (n > 0) {
                                debug.append("inflated from offset 0x${i.toString(16)} -> $n bytes\n")
                                return out.copyOf(n)
                            }
                        } catch (_: Exception) {}
                    }
                }
                i++
            }
            return null
        }

        private fun parseChannelsFromTexts(all: List<Pair<Int,ByteArray>>, ip: String, debug: StringBuilder): JSONArray {
            val out = JSONArray()
            val seen = HashSet<String>()
            for ((type, bytes) in all) {
                if (bytes.isEmpty()) continue // keep-alive frames
                val text = String(bytes, Charsets.UTF_8)
                debug.append("-- type=$type textLen=${text.length}\n")
                try {
                    if (text.trim().startsWith("[")) {
                        val arr = JSONArray(text)
                        for (i in 0 until arr.length()) pushIfChannel(arr.optJSONObject(i), out, seen, ip)
                    } else if (text.trim().startsWith("{")) {
                        val obj = JSONObject(text)
                        var matched = false
                        for (k in arrayOf("channels","list","items","programs","array")) {
                            val arr = obj.optJSONArray(k)
                            if (arr != null) {
                                for (i in 0 until arr.length()) pushIfChannel(arr.optJSONObject(i), out, seen, ip)
                                matched = true
                            }
                        }
                        if (!matched) pushIfChannel(obj, out, seen, ip)
                    }
                } catch (_: Exception) { }

                Regex("""player\.([0-9]+)""").findAll(text).forEach { m ->
                    val id = m.groupValues[1]
                    if (seen.add(id)) {
                        out.put(JSONObject().put("name", "Channel $id").put("id", id).put("serviceIndex", -1)
                            .put("url", "http://$ip:8085/player.$id"))
                    }
                }
            }
            return out
        }

        private fun pushIfChannel(o: JSONObject?, out: JSONArray, seen: HashSet<String>, ip: String) {
            if (o == null) return
            val name = o.optString("ServiceName", o.optString("name",""))
            val id = o.optString("ServiceID", o.optString("id", o.optString("chan_id","")))
            if (id.isNotEmpty() && seen.add(id)) {
                val svcIdx = o.optInt("ServiceIndex", -1)
                out.put(JSONObject()
                    .put("name", if (name.isNotEmpty()) name else "Channel $id")
                    .put("id", id)
                    .put("serviceIndex", svcIdx)
                    .put("url", "http://$ip:8085/player.$id"))
            }
        }

        private fun talkToControl(ip: String, port: Int, frames: List<ByteArray>, debug: StringBuilder): JSONArray {
            var sock: Socket? = null
            return try {
                debug.append("Connecting $ip:$port\n")
                sock = Socket(ip, port).apply { soTimeout = 4000 }
                val out: OutputStream = sock.getOutputStream()
                val `in`: InputStream = sock.getInputStream()
                for (f in frames) { out.write(f); out.flush(); try { Thread.sleep(60) } catch (_: Exception) {} }
                val framesIn = readGcdhFrames(`in`, totalWaitMs = 12000L, debug)
                if (framesIn.isEmpty()) { debug.append("No GCDH frames read on $port\n"); return JSONArray() }
                val chans = parseChannelsFromTexts(framesIn, ip, debug)
                if (chans.length() == 0) debug.append("Parsed 0 channels on $port\n")
                chans
            } catch (e: Exception) {
                debug.append("Error $ip:$port -> ${e.message}\n"); JSONArray()
            } finally { runCatching { sock?.close() } }
        }

        private fun talkOnce(ip: String, port: Int, frames: List<ByteArray>, debug: StringBuilder) {
            var sock: Socket? = null
            try {
                sock = Socket(ip, port).apply { soTimeout = 2500 }
                val out: OutputStream = sock.getOutputStream()
                val `in`: InputStream = sock.getInputStream()
                for (f in frames) { out.write(f); out.flush(); try { Thread.sleep(40) } catch (_: Exception) {} }
                try { Thread.sleep(200) } catch (_: Exception) {}
                if (`in`.available() >= 16) { /* ignore */ }
            } catch (_: Exception) { } finally { runCatching { sock?.close() } }
        }

        // ===== HTTP SWEEP FALLBACK =====
        @JavascriptInterface
        fun discoverStb(maxHosts: Int): String {
            val results = mutableListOf<Map<String,String>>()
            val prefix = localSubnetPrefix() ?: return "[]"
            val total = min(maxHosts.coerceAtLeast(1), 254)
            fun probe(ip: String): Map<String,String>? {
                return try {
                    http.newCall(Request.Builder().url("http://$ip:8085/").build())
                        .execute().use { if (it.isSuccessful) mapOf("ip" to ip, "name" to "STB :8085", "playlist" to "") else null }
                } catch (_: Exception) { null }
            }
            (1..254).take(total).map { host ->
                val ip = "$prefix.$host"
                Runnable { probe(ip)?.let { synchronized(results) { results += it } } }
            }.forEach { io.execute(it) }
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
                            val p = a.hostAddress.split(".")
                            if (p.size == 4) return "${p[0]}.${p[1]}.${p[2]}"
                        }
                    }
                }
            } catch (_: Exception) {}
            return null
        }
    }
}

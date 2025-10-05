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

        // ===== PLAY IN VLC =====
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

        // Open directly by ID (VLC)
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
                    .append(",\"name\":\"STB broadcast\"")
                    .append(",\"playlist\":\"\"}")
            }
            sb.append("]")
            return sb.toString()
        }

        // ========= CONTROL PROTOCOL =========
        // We capture: satellites, TP map, and services.
        data class TpInfo(val tpIndex: Int, val satIndex: Int)
        data class SatInfo(val index: Int, val name: String)

        @JavascriptInterface
        fun fetchChannelsFromStb(): String {
            val ip = getStbIp().trim()
            if (ip.isEmpty()) return "[]"

            val ports = intArrayOf(20000, 4113, 8888)
            val seq = mutableListOf<ByteArray>()

            // Handshake + list type + ranges (wide)
            seq += jsonFrame("""{"request":"1012"}""")
            seq += jsonFrame("""{"request":"1007","IsFavList":"0","SelectListType":"0"}""")
            val windows = arrayOf(0 to 199, 200 to 399, 400 to 799)
            windows.forEach { (a,b) ->
                seq += jsonFrame("""{"request":"0","FromIndex":"$a","ToIndex":"$b"}""")
            }
            seq += jsonFrame("""{"request":"26"}""")

            val combinedDebug = StringBuilder()
            for (port in ports) {
                try {
                    val res = talkToControl(ip, port, seq, combinedDebug)
                    if (res.length() > 0) return res.toString()
                } catch (_: Exception) { }
            }
            prefs.edit().putString("last_debug", combinedDebug.toString().take(12000)).apply()
            return "[]"
        }

        @JavascriptInterface
        fun getLastDebug(): String = prefs.getString("last_debug", "") ?: ""

        // Switch/tune best-effort by ServiceID (used by UI “Switch”)
        @JavascriptInterface
        fun switchToServiceId(serviceId: String): String {
            val ip = getStbIp().trim()
            if (ip.isEmpty() || serviceId.isEmpty()) return "no-ip-or-id"

            val debug = StringBuilder()
            val seq = mutableListOf<ByteArray>()
            // try a few likely commands; harmless if unknown
            seq += jsonFrame("""{"request":"23","ServiceID":"$serviceId"}""")
            seq += jsonFrame("""{"request":"20","ServiceID":"$serviceId"}""")
            seq += jsonFrame("""{"request":"26"}""")

            val ports = intArrayOf(20000, 4113, 8888)
            for (p in ports) {
                try {
                    talkToControl(ip, p, seq, debug) // we don't need the result list here
                    prefs.edit().putString("last_debug", debug.toString().take(12000)).apply()
                    return "ok"
                } catch (_: Exception) {}
            }

            // HTTP fallbacks (just in case)
            try { http.newCall(Request.Builder().url("http://$ip/?Play=$serviceId").build()).execute().use { } } catch (_: Exception) {}
            try { http.newCall(Request.Builder().url("http://$ip/?ServiceID=$serviceId").build()).execute().use { } } catch (_: Exception) {}

            prefs.edit().putString("last_debug", debug.toString().take(12000)).apply()
            return "sent"
        }

        // ===== helpers =====
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
            val end = System.currentTimeMillis() + max(1500L, totalWaitMs)
            while (System.currentTimeMillis() < end) {
                if (`in`.available() < 16) { try { Thread.sleep(20) } catch (_: Exception) {}; continue }
                val hdr = readExact(`in`, 16) ?: break
                if (!(hdr[0]=='G'.code.toByte() && hdr[1]=='C'.code.toByte() && hdr[2]=='D'.code.toByte() && hdr[3]=='H'.code.toByte())) {
                    debug.append("Non-GCDH header\n"); break
                }
                val payloadLen = beInt(hdr, 4)
                val msgType   = beInt(hdr, 8)
                val extra     = beInt(hdr,12)
                debug.append("GCDH hdr @0x${Integer.toHexString(chunks.sumOf { it.second.size } + 16)} len=$payloadLen type=$msgType extra=$extra\n")
                if (payloadLen <= 0 || payloadLen > 8*1024*1024) { debug.append("Bad len\n"); break }
                val comp = readExact(`in`, payloadLen) ?: break
                val inflated = tryInflate(comp) ?: comp
                chunks += msgType to inflated
            }
            return chunks
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

        private fun talkToControl(ip: String, port: Int, frames: List<ByteArray>, debug: StringBuilder): JSONArray {
            var sock: Socket? = null
            return try {
                debug.append("Connecting $ip:$port\n")
                sock = Socket(ip, port).apply { soTimeout = 2500 }
                val out: OutputStream = sock.getOutputStream()
                val `in`: InputStream = sock.getInputStream()
                for (f in frames) { out.write(f); out.flush(); try { Thread.sleep(50) } catch (_: Exception) {} }
                val framesIn = readGcdhFrames(`in`, totalWaitMs = 2500L, debug)
                if (framesIn.isEmpty()) return JSONArray()
                val chans = parseEverything(framesIn, ip, debug)
                if (chans.length() == 0) debug.append("Parsed 0 channels on $port\n")
                prefs.edit().putString("last_debug", debug.toString().take(12000)).apply()
                chans
            } catch (e: Exception) {
                debug.append("Error $ip:$port -> ${e.message}\n")
                JSONArray()
            } finally {
                try { sock?.close() } catch (_: Exception) {}
            }
        }

        // Parse satellites + TP map + services -> enrich each channel with satIndex/satName when possible
        private fun parseEverything(all: List<Pair<Int,ByteArray>>, ip: String, debug: StringBuilder): JSONArray {
            val sats = mutableMapOf<Int, String>()       // satIndex -> name
            val tp2sat = mutableMapOf<Int, Int>()        // TPIndex -> satIndex
            val channels = JSONArray()
            val seen = HashSet<String>()

            // pass 1: satellites + TP tables
            for ((_, bytes) in all) {
                val text = String(bytes, Charsets.UTF_8)
                if (text.contains("\"SatName\"")) {
                    try {
                        val arr = JSONArray(text)
                        for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i) ?: continue
                            val idx = o.optInt("SatIndex", -1)
                            val name = o.optString("SatName","").replace("\u0001","").trim()
                            if (idx >= 0 && name.isNotEmpty()) sats[idx] = name
                        }
                    } catch (_: Exception) {}
                }
                if (text.contains("\"TPIndex\"") && text.contains("\"SatIndex\"")) {
                    try {
                        val arr = JSONArray(text)
                        for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i) ?: continue
                            val tpi = o.optInt("TPIndex",-1)
                            val si  = o.optInt("SatIndex",-1)
                            if (tpi >= 0 && si >= 0) tp2sat[tpi] = si
                        }
                    } catch (_: Exception) {}
                }
            }

            // pass 2: services
            for ((_, bytes) in all) {
                val text = String(bytes, Charsets.UTF_8)

                // JSON array of services
                if (text.trim().startsWith("[")) {
                    try {
                        val arr = JSONArray(text)
                        for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i) ?: continue
                            val id = o.optString("ServiceID","")
                            if (id.isEmpty() || !seen.add(id)) continue
                            val name = o.optString("ServiceName", "Channel $id")
                            val tpIndex = o.optInt("TPIndex", -1) // some firmwares include this
                            val satIndex = if (tpIndex >= 0) (tp2sat[tpIndex] ?: -1) else -1
                            val satName = if (satIndex >= 0) (sats[satIndex] ?: "") else ""
                            channels.put(
                                JSONObject()
                                    .put("name", name)
                                    .put("id", id)
                                    .put("url", "http://$ip:8085/player.$id")
                                    .put("tpIndex", if (tpIndex>=0) tpIndex else JSONObject.NULL)
                                    .put("satIndex", if (satIndex>=0) satIndex else JSONObject.NULL)
                                    .put("satName", if (satName.isNotEmpty()) satName else JSONObject.NULL)
                            )
                        }
                    } catch (_: Exception) {}
                }
            }
            // stick satellites list for UI filter
            val meta = JSONObject()
            val satArr = JSONArray()
            sats.toSortedMap().forEach { (k,v) -> satArr.put(JSONObject().put("index",k).put("name",v)) }
            meta.put("satellites", satArr)
            if (channels.length() > 0) {
                (channels as JSONArray).put(JSONObject().put("__meta", meta)) // append a meta marker at end
            }
            debug.append("-- sats=${sats.size} tpMap=${tp2sat.size} channels=${channels.length()}\n")
            return channels
        }

        // ===== HTTP SWEEP FALLBACK =====
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

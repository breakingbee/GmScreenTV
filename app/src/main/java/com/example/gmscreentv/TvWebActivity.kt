package com.example.gmscreentv

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Toast
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

        // ===== PLAYBACK =====
        @JavascriptInterface
        fun openInVlc(url: String) {
            // STRICT VLC only. If VLC missing, just show a toast.
            try {
                val i = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(android.net.Uri.parse(url), "video/*")
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    setPackage("org.videolan.vlc")
                }
                ctx.startActivity(i)
            } catch (_: Exception) {
                Toast.makeText(ctx, "Install VLC to play streams.", Toast.LENGTH_LONG).show()
            }
        }

        @JavascriptInterface
        fun openChannelById(channelId: String) {
            val ip = getStbIp()
            val id = channelId.trim()
            if (ip.isEmpty() || id.isEmpty()) return
            openInVlc("http://$ip:8085/player.$id")
        }

        // New: tune STB to ServiceID first, then open in VLC
        @JavascriptInterface
        fun tuneAndPlay(serviceId: String, serviceIndex: Int) {
            val ip = getStbIp().trim()
            if (ip.isEmpty() || serviceId.isEmpty()) return

            io.execute {
                // 1) Try control-socket “zap by ServiceID” (we’ll send several likely verbs; STB ignores unknown ones)
                val tries = listOf(
                    """{"request":"1001","ServiceID":"$serviceId"}""",
                    """{"request":"1010","ServiceID":"$serviceId"}""",
                    """{"request":"1011","ServiceID":"$serviceId"}"""
                ).map { jsonFrame(it) }

                var tuned = false
                val debug = StringBuilder()
                for (port in intArrayOf(20000, 4113, 8888)) {
                    try {
                        tuned = sendControlSequence(ip, port, tries, debug)
                        if (tuned) break
                    } catch (_: Exception) { }
                }

                // 2) Fallback: send RC digits for ServiceIndex (if provided)
                if (!tuned && serviceIndex >= 0) {
                    try { sendNumberAsRcu(serviceIndex) } catch (_: Exception) {}
                }

                // 3) Small wait, then VLC
                try { Thread.sleep(700) } catch (_: Exception) {}
                val url = "http://$ip:8085/player.$serviceId"
                webView.post { openInVlc(url) }
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
                        val ip = pkt.address?.hostAddress ?: continue
                        found += ip
                    } catch (_: java.net.SocketTimeoutException) {} catch (_: Exception) { break }
                }
            } catch (_: Exception) {
            } finally { try { socket?.close() } catch (_: Exception) {} }

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

        // ========= CONTROL PROTOCOL (fetch) =========
        @JavascriptInterface
        fun fetchChannelsFromStb(): String {
            val ip = getStbIp().trim()
            if (ip.isEmpty()) return "[]"

            val ports = intArrayOf(20000, 4113, 8888)

            val seq = mutableListOf<ByteArray>()
            // handshake/info
            seq += jsonFrame("""{"request":"1012"}""")
            // list type: TV, non-fav
            seq += jsonFrame("""{"request":"1007","IsFavList":"0","SelectListType":"0"}""")
            // wide windows (we dedupe by ServiceID)
            val windows = arrayOf(
                0 to 199, 200 to 399, 400 to 799,
                800 to 1199, 1200 to 1599, 1600 to 2199
            )
            windows.forEach { (a,b) -> seq += jsonFrame("""{"request":"0","FromIndex":"$a","ToIndex":"$b"}""") }
            // heartbeat
            seq += jsonFrame("""{"request":"26"}""")

            val combinedDebug = StringBuilder()
            for (port in ports) {
                try {
                    val res = talkToControl(ip, port, seq, combinedDebug, listenMillis = 12000L)
                    if (res.length() > 0) return res.toString()
                } catch (_: Exception) { }
            }
            prefs.edit().putString("last_debug", combinedDebug.toString().take(16000)).apply()
            return "[]"
        }

        @JavascriptInterface fun getLastDebug(): String = prefs.getString("last_debug", "") ?: ""

        // ==== helpers ====
        private fun sendNumberAsRcu(num: Int) {
            val map = mapOf('1' to 13, '2' to 14, '3' to 15, '4' to 16, '5' to 17,
                            '6' to 18, '7' to 19, '8' to 20, '9' to 21, '0' to 22)
            val s = num.toString()
            for (ch in s) {
                val code = map[ch] ?: continue
                val url = "http://${getStbIp()}/?RcuKey=$code"
                try { http.newCall(Request.Builder().url(url).build()).execute().use { } } catch (_: Exception) {}
                try { Thread.sleep(120) } catch (_: Exception) {}
            }
            // optional: OK/ENTER (adjust if your STB needs a different keycode)
            val enterCode = 11
            val enterUrl = "http://${getStbIp()}/?RcuKey=$enterCode"
            try { http.newCall(Request.Builder().url(enterUrl).build()).execute().use { } } catch (_: Exception) {}
        }

        private fun sendControlSequence(ip: String, port: Int, frames: List<ByteArray>, debug: StringBuilder): Boolean {
            var sock: Socket? = null
            return try {
                debug.append("Tune attempt $ip:$port\n")
                sock = Socket(ip, port).apply { soTimeout = 2200 }
                val out: OutputStream = sock.getOutputStream()
                val `in`: InputStream = sock.getInputStream()
                for (f in frames) { out.write(f); out.flush(); try { Thread.sleep(40) } catch (_: Exception) {} }
                // read a little so STB processes; success not guaranteed, but this gives it time
                readGcdhFrames(`in`, totalWaitMs = 600L, debug)
                true
            } catch (_: Exception) {
                false
            } finally { try { sock?.close() } catch (_: Exception) {} }
        }

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

        private fun readGcdhFrames(`in`: InputStream, totalWaitMs: Long, debug: StringBuilder): List<Pair<Int,ByteArray>> {
            val chunks = ArrayList<Pair<Int,ByteArray>>()
            val end = System.currentTimeMillis() + max(300L, totalWaitMs)
            while (System.currentTimeMillis() < end) {
                try {
                    if (`in`.available() < 16) { Thread.sleep(20); continue }
                    val hdr = readExact(`in`, 16) ?: break
                    if (!(hdr[0]=='G'.code.toByte() && hdr[1]=='C'.code.toByte() && hdr[2]=='D'.code.toByte() && hdr[3]=='H'.code.toByte())) break
                    val payloadLen = beInt(hdr, 4)
                    val msgType   = beInt(hdr, 8)
                    val extra     = beInt(hdr,12)
                    debug.append("GCDH frame len=$payloadLen type=$msgType extra=$extra\n")
                    if (payloadLen < 0 || payloadLen > 8*1024*1024) break
                    val comp = readExact(`in`, payloadLen) ?: break
                    val inflated = tryInflate(comp) ?: comp
                    chunks += msgType to inflated
                } catch (_: Exception) { break }
            }
            return chunks
        }

        private fun tryInflate(data: ByteArray): ByteArray? = try {
            val inf = Inflater(); inf.setInput(data)
            val out = ByteArray(2*1024*1024)
            val n = inf.inflate(out); inf.end()
            if (n > 0) out.copyOf(n) else null
        } catch (_: Exception) { null }

        private fun parseChannelsFromTexts(all: List<Pair<Int,ByteArray>>, ip: String, debug: StringBuilder): JSONArray {
            val out = JSONArray()
            val seen = HashSet<String>()
            fun addObj(name: String?, id: String?, idx: Int?, playing: Boolean){
                val sid = (id ?: "").trim()
                if (sid.isEmpty() || !seen.add(sid)) return
                val o = JSONObject()
                    .put("name", if (!name.isNullOrEmpty()) name else "Channel $sid")
                    .put("id", sid)
                    .put("url", "http://$ip:8085/player.$sid")
                if (idx != null) o.put("idx", idx)
                if (playing) o.put("playing", true)
                out.put(o)
            }

            for ((type, bytes) in all) {
                val text = String(bytes, Charsets.UTF_8)
                try {
                    if (text.trim().startsWith("[")) {
                        val arr = JSONArray(text)
                        for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i) ?: continue
                            val name = o.optString("ServiceName", o.optString("name",""))
                            val id   = o.optString("ServiceID", o.optString("id", o.optString("chan_id","")))
                            val idx  = if (o.has("ServiceIndex")) o.optInt("ServiceIndex") else null
                            val playing = o.optInt("Playing", 0) == 1
                            addObj(name, id, idx, playing)
                        }
                    } else if (text.trim().startsWith("{")) {
                        val obj = JSONObject(text)
                        val keys = arrayOf("channels","list","items","programs","array")
                        for (k in keys) {
                            if (obj.has(k)) {
                                val arr = obj.optJSONArray(k) ?: continue
                                for (i in 0 until arr.length()) {
                                    val o = arr.optJSONObject(i) ?: continue
                                    val name = o.optString("ServiceName", o.optString("name",""))
                                    val id   = o.optString("ServiceID", o.optString("id", o.optString("chan_id","")))
                                    val idx  = if (o.has("ServiceIndex")) o.optInt("ServiceIndex") else null
                                    val playing = o.optInt("Playing", 0) == 1
                                    addObj(name, id, idx, playing)
                                }
                            }
                        }
                    }
                } catch (_: Exception) { /* ignore */ }

                // Fallback: player.<ID>
                Regex("""player\.([0-9]+)""").findAll(text).forEach { m ->
                    addObj(null, m.groupValues[1], null, false)
                }
            }
            return out
        }

        private fun talkToControl(
            ip: String,
            port: Int,
            frames: List<ByteArray>,
            debug: StringBuilder,
            listenMillis: Long
        ): JSONArray {
            var sock: Socket? = null
            return try {
                debug.append("Connecting $ip:$port\n")
                sock = Socket(ip, port).apply { soTimeout = 2500 }
                val out: OutputStream = sock.getOutputStream()
                val `in`: InputStream = sock.getInputStream()
                for (f in frames) { out.write(f); out.flush(); try { Thread.sleep(40) } catch (_: Exception) {} }
                val framesIn = readGcdhFrames(`in`, totalWaitMs = listenMillis, debug)
                val chans = parseChannelsFromTexts(framesIn, ip, debug)
                prefs.edit().putString("last_debug", debug.toString().take(16000)).apply()
                chans
            } catch (e: Exception) {
                debug.append("Error $ip:$port -> ${e.message}\n")
                JSONArray()
            } finally { try { sock?.close() } catch (_: Exception) {} }
        }

        // ===== HTTP sweep (optional) =====
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

package com.example.gmscreentv

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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

        // ===== PLAYBACK (VLC only) =====
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

        // ===== MY CHANNELS (optional, local) =====
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

        // ========= CONTROL PROTOCOL =========
        // Exact sequence you captured:
        // 1) XML login (request=998) inside Start/End envelope
        // 2) JSON requests (all Start/End):
        //    23, 16, 20, 12, 24
        //    many windows: {"request":"0","FromIndex":"A","ToIndex":"B"}
        //    22, 1012, 20
        // We keep the socket open and read GCDH frames; inflate and parse JSON arrays.

        @JavascriptInterface
        fun fetchChannelsFromStb(): String {
            val ip = getStbIp().trim()
            if (ip.isEmpty()) return "[]"

            // PC logs show server on 8888 for this phase
            val port = 8888

            val debug = StringBuilder()
            try {
                val frames = mutableListOf<ByteArray>()

                // (1) XML login
                frames += xmlLoginFrame()

                // (2) Fixed JSON control requests in same order
                frames += jsonFrame("""{"request":"23"}""")
                frames += jsonFrame("""{"request":"16"}""")
                frames += jsonFrame("""{"request":"20"}""")
                frames += jsonFrame("""{"request":"12"}""")
                frames += jsonFrame("""{"request":"24"}""")

                // windows 0..1999 step 100 (0-99, 100-199, ...)
                for (a in 0..1999 step 100) {
                    val b = a + 99
                    frames += jsonFrame("""{"FromIndex":"$a","ToIndex":"$b","request":"0"}""")
                }

                frames += jsonFrame("""{"request":"22"}""")
                frames += jsonFrame("""{"request":"1012"}""")
                frames += jsonFrame("""{"request":"20"}""")

                val res = talkToControl(ip, port, frames, debug)
                prefs.edit().putString("last_debug", debug.toString().take(15000)).apply()
                return res.toString()
            } catch (e: Exception) {
                debug.append("fetch error: ${e.message}\n")
                prefs.edit().putString("last_debug", debug.toString().take(15000)).apply()
                return "[]"
            }
        }

        @JavascriptInterface
        fun getLastDebug(): String = prefs.getString("last_debug", "") ?: ""

        // ===== Helpers =====

        private fun deviceLabel(): String {
            val model = Build.MODEL ?: "Android"
            return model.trim().ifEmpty { "Android" }
        }

        private fun stableUuid(ctx: Context): String {
            // Stable per device install (best effort)
            val androidId = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
            val base = (androidId ?: "00000000") + "-" + Build.SERIAL
            return UUID.nameUUIDFromBytes(base.toByteArray(Charsets.UTF_8)).toString()
        }

        private fun xmlLoginFrame(): ByteArray {
            // Matches your capture: request="998" with <data>DeviceName</data> and <uuid>...-02:00:00:00:00:00</uuid>
            val uuid = stableUuid(ctx) + "-02:00:00:00:00:00"
            val xml = "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>" +
                    "<Command request=\"998\"><data>${escapeXml(deviceLabel())}</data><uuid>$uuid</uuid></Command>"
            return startEndFrame(xml.toByteArray(Charsets.UTF_8))
        }

        private fun escapeXml(s: String): String =
            s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

        private fun jsonFrame(json: String): ByteArray {
            val body = json.trim().toByteArray(Charsets.UTF_8)
            return startEndFrame(body)
        }

        private fun startEndFrame(body: ByteArray): ByteArray {
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

        private fun leInt(b: ByteArray, o: Int): Int {
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
                    debug.append("Non-GCDH header: ${hdr.copyOf(4).toString(Charsets.US_ASCII)}\n")
                    break
                }
                val payloadLen = leInt(hdr, 4)
                val msgType   = leInt(hdr, 8)
                val extra     = leInt(hdr,12)
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

        private fun parseChannelsFromTexts(all: List<Pair<Int,ByteArray>>, ip: String, debug: StringBuilder): JSONArray {
            val out = JSONArray()
            val seen = HashSet<String>()
            for ((type, bytes) in all) {
                val text = String(bytes, Charsets.UTF_8)
                debug.append("-- type=$type textLen=${text.length}\n")
                // Try JSON array of service objects
                try {
                    val t = text.trim()
                    if (t.startsWith("[")) {
                        val arr = JSONArray(t)
                        for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i) ?: continue
                            val name = o.optString("ServiceName", o.optString("name",""))
                            val id = o.optString("ServiceID", o.optString("id", o.optString("chan_id","")))
                            if (id.isNotEmpty() && seen.add(id)) {
                                out.put(JSONObject()
                                    .put("name", if (name.isNotEmpty()) name else "Channel $id")
                                    .put("id", id)
                                    .put("url", "http://$ip:8085/player.$id"))
                            }
                        }
                        continue
                    } else if (t.startsWith("{")) {
                        val obj = JSONObject(t)
                        val keys = arrayOf("channels","list","items","programs","array")
                        for (k in keys) if (obj.has(k)) {
                            val arr = obj.optJSONArray(k) ?: continue
                            for (i in 0 until arr.length()) {
                                val o = arr.optJSONObject(i) ?: continue
                                val name = o.optString("ServiceName", o.optString("name",""))
                                val id = o.optString("ServiceID", o.optString("id", o.optString("chan_id","")))
                                if (id.isNotEmpty() && seen.add(id)) {
                                    out.put(JSONObject()
                                        .put("name", if (name.isNotEmpty()) name else "Channel $id")
                                        .put("id", id)
                                        .put("url", "http://$ip:8085/player.$id"))
                                }
                            }
                        }
                    }
                } catch (_: Exception) { /* fall through */ }

                // Fallback: grep for player.<ID>
                Regex("""player\.([0-9]+)""").findAll(text).forEach { m ->
                    val id = m.groupValues[1]
                    if (seen.add(id)) {
                        out.put(JSONObject().put("name", "Channel $id")
                            .put("id", id)
                            .put("url", "http://$ip:8085/player.$id"))
                    }
                }
            }
            return out
        }

        private fun talkToControl(ip: String, port: Int, frames: List<ByteArray>, debug: StringBuilder): JSONArray {
            var sock: Socket? = null
            return try {
                debug.append("Connecting $ip:$port\n")
                sock = Socket(ip, port).apply { soTimeout = 4000 }
                val out: OutputStream = sock.getOutputStream()
                val `in`: InputStream = sock.getInputStream()

                // send frames spaced out like the PC app
                for (f in frames) {
                    out.write(f)
                    out.flush()
                    try { Thread.sleep(40) } catch (_: Exception) {}
                }

                // read for a while to accumulate multiple lists
                val framesIn = readGcdhFrames(`in`, totalWaitMs = 4500L, debug)
                if (framesIn.isEmpty()) {
                    debug.append("No GCDH frames read on $port\n")
                    return JSONArray()
                }
                val chans = parseChannelsFromTexts(framesIn, ip, debug)
                if (chans.length() == 0) debug.append("Parsed 0 channels on $port\n")
                chans
            } catch (e: Exception) {
                debug.append("Error $ip:$port -> ${e.message}\n")
                JSONArray()
            } finally {
                try { sock?.close() } catch (_: Exception) {}
            }
        }

        // ===== HTTP SWEEP FALLBACK (unchanged) =====
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

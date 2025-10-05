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
import java.io.ByteArrayOutputStream
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
    }

    inner class NativeBridge(private val ctx: Context) {
        private val prefs get() = ctx.getSharedPreferences("gmscreen", Context.MODE_PRIVATE)

        // --- prefs/debug ---
        @JavascriptInterface fun setStbIp(ip: String) { prefs.edit().putString("stb_ip", ip.trim()).apply() }
        @JavascriptInterface fun getStbIp(): String = prefs.getString("stb_ip", "") ?: ""
        @JavascriptInterface fun getLastDebug(): String = prefs.getString("last_debug", "") ?: ""
        private fun putDebug(s: String) { prefs.edit().putString("last_debug", s.take(30000)).apply() }

        // --- remote key (optional) ---
        @JavascriptInterface fun sendRcuKey(key: String) {
            val ip = getStbIp().trim(); if (ip.isEmpty()) return
            val url = "http://$ip/?RcuKey=$key"
            io.execute { try { http.newCall(Request.Builder().url(url).build()).execute().use {} } catch (_: Exception) {} }
        }

        // --- playback ---
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
        @JavascriptInterface fun openChannelById(id: String) {
            val ip = getStbIp().trim(); if (ip.isEmpty() || id.isEmpty()) return
            openInVlc("http://$ip:8085/player.$id")
        }

        // --- autodetect (UDP + quick HTTP sweep) ---
        @JavascriptInterface
        fun discoverUdp25860(timeoutMs: Int): String {
            val arr = JSONArray()
            // UDP listen
            var s: DatagramSocket? = null
            try {
                s = DatagramSocket(25860).apply { broadcast = true; soTimeout = 500; reuseAddress = true }
                val end = System.currentTimeMillis() + timeoutMs.coerceAtLeast(1500)
                val buf = ByteArray(1024)
                while (System.currentTimeMillis() < end) {
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        s.receive(p)
                        val ip = p.address?.hostAddress ?: continue
                        arr.put(JSONObject().put("ip", ip).put("name", "STB (UDP 25860)").put("playlist",""))
                    } catch (_: java.net.SocketTimeoutException) {}
                }
            } catch (_: Exception) {} finally { try { s?.close() } catch (_: Exception) {} }
            // quick :8085 sweep  (best-effort)
            val prefix = localSubnetPrefix()
            if (prefix != null) {
                val tasks = (1..80).map { h ->
                    Runnable {
                        val ip = "$prefix.$h"
                        try {
                            http.newCall(Request.Builder().url("http://$ip:8085/").build()).execute().use {
                                if (it.isSuccessful) synchronized(arr) {
                                    arr.put(JSONObject().put("ip", ip).put("name", "STB :8085").put("playlist",""))
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }
                tasks.forEach { io.execute(it) }
                try { Thread.sleep(1200) } catch (_: Exception) {}
            }
            return arr.toString()
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

        // --- Control protocol (GCDH + zlib, with raw sweep fallback) ---
        @JavascriptInterface
        fun fetchChannelsFromStb(): String {
            val ip = getStbIp().trim()
            if (ip.isEmpty()) return "[]"

            val debug = StringBuilder()
            val ports = intArrayOf(20000) // only port open per your screenshot

            // Request sequence seen in your pcap (plus windows)
            val seq = mutableListOf<ByteArray>()
            seq += jsonFrame("""{"request":"23"}""")
            seq += jsonFrame("""{"request":"16"}""")
            seq += jsonFrame("""{"request":"20"}""")
            seq += jsonFrame("""{"request":"22"}""")
            seq += jsonFrame("""{"request":"24"}""")
            seq += jsonFrame("""{"request":"1012"}""")
            seq += jsonFrame("""{"request":"1007","IsFavList":"0","SelectListType":"0"}""")

            val windows = arrayOf(
                0 to 199, 200 to 399, 400 to 599, 600 to 799, 800 to 999,
                1000 to 1299, 1300 to 1599, 1600 to 1899, 1900 to 2199, 2200 to 2499
            )
            windows.forEach { (a,b) -> seq += jsonFrame("""{"request":"0","FromIndex":"$a","ToIndex":"$b"}""") }

            seq += jsonFrame("""{"request":"26"}""")

            var result = JSONArray()
            for (port in ports) {
                val r = talk(ip, port, seq, debug)
                if (r.length() > 0) { result = r; break }
            }
            putDebug(debug.toString())
            return result.toString()
        }

        private fun jsonFrame(json: String): ByteArray {
            val body = json.trim().toByteArray(Charsets.UTF_8)
            val header = "Start" + String.format("%07d", body.size) + "End"
            return header.toByteArray(Charsets.UTF_8) + body
        }

        private fun talk(ip: String, port: Int, frames: List<ByteArray>, debug: StringBuilder): JSONArray {
            var sock: Socket? = null
            return try {
                debug.append("Connecting $ip:$port\n")
                sock = Socket(ip, port).apply { soTimeout = 2500 }
                val out: OutputStream = sock.getOutputStream()
                val inn: InputStream = sock.getInputStream()

                // send frames
                for (f in frames) { out.write(f); out.flush(); try { Thread.sleep(40) } catch (_: Exception) {} }

                // read everything for ~3.5s (raw capture)
                val buf = ByteArray(8192)
                val bos = ByteArrayOutputStream()
                val until = System.currentTimeMillis() + 3500
                while (System.currentTimeMillis() < until) {
                    val n = try { inn.read(buf) } catch (_: Exception) { -1 }
                    if (n <= 0) { try { Thread.sleep(25) } catch (_: Exception) {} ; continue }
                    bos.write(buf, 0, n)
                }
                val raw = bos.toByteArray()
                debug.append("Captured ${raw.size} bytes\n")

                // 1) parse as GCDH frames (if any)
                val framesIn = parseGcdhFromRaw(raw, debug)
                var channels = parseChannels(framesIn, ip, debug)

                // 2) if none, sweep zlib in the raw stream (like Python)
                if (channels.length() == 0) {
                    val texts = inflateAllZlib(raw, debug)
                    channels = parseChannelsFromTexts(texts, ip, debug)
                }

                if (channels.length() == 0) debug.append("Parsed 0 channels on $port\n")
                channels
            } catch (e: Exception) {
                debug.append("Error on $ip:$port -> ${e.message}\n")
                JSONArray()
            } finally { try { sock?.close() } catch (_: Exception) {} }
        }

        // ---- helpers: GCDH parsing from raw stream ----
        private fun beInt(b: ByteArray, o: Int): Int {
            // little-endian to int
            return ((b[o+3].toInt() and 0xFF) shl 24) or
                   ((b[o+2].toInt() and 0xFF) shl 16) or
                   ((b[o+1].toInt() and 0xFF) shl 8)  or
                   ( b[o+0].toInt() and 0xFF)
        }

        private fun parseGcdhFromRaw(raw: ByteArray, debug: StringBuilder): List<Pair<Int,ByteArray>> {
            val out = ArrayList<Pair<Int,ByteArray>>()
            var i = 0
            val tag = byteArrayOf('G'.code.toByte(),'C'.code.toByte(),'D'.code.toByte(),'H'.code.toByte())
            while (i + 16 <= raw.size) {
                if (raw[i]==tag[0] && raw[i+1]==tag[1] && raw[i+2]==tag[2] && raw[i+3]==tag[3]) {
                    val len = beInt(raw, i+4)
                    val typ = beInt(raw, i+8)
                    val extra = beInt(raw, i+12)
                    debug.append("GCDH hdr @0x${i.toString(16)} len=$len type=$typ extra=$extra\n")
                    val start = i + 16
                    if (len in 1..(raw.size - start)) {
                        val comp = raw.copyOfRange(start, start + len)
                        val inf = tryInflate(comp) ?: comp
                        out += typ to inf
                        i = start + len
                        continue
                    } else {
                        // len==0 or nonsense: skip 1 byte and keep scanning
                        i += 1
                        continue
                    }
                }
                i += 1
            }
            return out
        }

        private fun tryInflate(data: ByteArray): ByteArray? = try {
            val inf = Inflater()
            inf.setInput(data)
            val buf = ByteArray(2 * 1024 * 1024)
            val n = inf.inflate(buf)
            inf.end()
            if (n > 0) buf.copyOf(n) else null
        } catch (_: Exception) { null }

        // ---- sweep for zlib streams inside raw ----
        private fun inflateAllZlib(raw: ByteArray, debug: StringBuilder): List<String> {
            val outs = ArrayList<String>()
            var i = 0
            while (i + 2 < raw.size) {
                val b0 = raw[i].toInt() and 0xFF
                val b1 = raw[i+1].toInt() and 0xFF
                val cmf = b0
                val flg = b1
                val cm = cmf and 0x0F
                val check = ((cmf shl 8) + flg) % 31
                val compMethodOK = (cm == 8) && (check == 0)
                if (compMethodOK) {
                    // try inflate from here
                    val slice = raw.copyOfRange(i, raw.size)
                    val inf = tryInflate(slice)
                    if (inf != null && inf.isNotEmpty()) {
                        val text = try { String(inf, Charsets.UTF_8) } catch (_: Exception) { "" }
                        if (text.isNotEmpty()) {
                            outs += text
                            debug.append("zlib OK @0x${i.toString(16)} -> textLen=${text.length}\n")
                            // move ahead a bit to avoid duplicates
                            i += 32
                            continue
                        }
                    }
                }
                i += 1
            }
            return outs
        }

        // ---- parse channels from GCDH texts or inflated texts ----
        private fun parseChannels(frames: List<Pair<Int,ByteArray>>, ip: String, debug: StringBuilder): JSONArray {
            val texts = frames.map { (_, b) -> try { String(b, Charsets.UTF_8) } catch (_: Exception) { "" } }
            return parseChannelsFromTexts(texts, ip, debug)
        }

        private fun parseChannelsFromTexts(texts: List<String>, ip: String, debug: StringBuilder): JSONArray {
            val out = JSONArray()
            val seen = HashSet<String>()
            for (text in texts) {
                if (text.isEmpty()) continue
                debug.append("-- textLen=${text.length}\n")
                try {
                    if (text.trim().startsWith("[")) {
                        val arr = JSONArray(text)
                        for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i) ?: continue
                            val name = o.optString("ServiceName", o.optString("name",""))
                            val id = o.optString("ServiceID", o.optString("id",""))
                            if (id.isNotEmpty() && seen.add(id)) {
                                out.put(JSONObject().put("name", if (name.isNotEmpty()) name else "Channel $id")
                                    .put("id", id)
                                    .put("url", "http://$ip:8085/player.$id"))
                            }
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
                                    val id = o.optString("ServiceID", o.optString("id",""))
                                    if (id.isNotEmpty() && seen.add(id)) {
                                        out.put(JSONObject().put("name", if (name.isNotEmpty()) name else "Channel $id")
                                            .put("id", id)
                                            .put("url", "http://$ip:8085/player.$id"))
                                    }
                                }
                            }
                        }
                    }
                } catch (_: Exception) { /* ignore */ }

                // Fallback: scrape player.<ID>
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
    }
}

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
    }

    inner class NativeBridge(private val ctx: Context) {
        private val prefs get() = ctx.getSharedPreferences("gmscreen", Context.MODE_PRIVATE)

        // ─── Settings ────────────────────────────────────────────────────────────
        @JavascriptInterface fun setStbIp(ip: String) { prefs.edit().putString("stb_ip", ip.trim()).apply() }
        @JavascriptInterface fun getStbIp(): String = prefs.getString("stb_ip", "") ?: ""
        @JavascriptInterface fun getLastDebug(): String = prefs.getString("last_debug", "") ?: ""
        private fun putDebug(s: String) { prefs.edit().putString("last_debug", s.take(20000)).apply() }

        // ─── Remote keys (optional) ─────────────────────────────────────────────
        @JavascriptInterface fun sendRcuKey(key: String) {
            val ip = getStbIp().trim(); if (ip.isEmpty()) return
            val url = "http://$ip/?RcuKey=$key"
            io.execute { try { http.newCall(Request.Builder().url(url).build()).execute().use {} } catch (_: Exception) {} }
        }

        // ─── Play a channel ─────────────────────────────────────────────────────
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

        // ─── Auto-detect STB ────────────────────────────────────────────────────
        @JavascriptInterface
        fun discoverUdp25860(timeoutMs: Int): String {
            val out = linkedSetOf<String>()
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
                        out += ip
                    } catch (_: java.net.SocketTimeoutException) {}
                }
            } catch (_: Exception) {} finally { try { s?.close() } catch (_: Exception) {} }

            val arr = JSONArray()
            out.forEach { ip -> arr.put(JSONObject().put("ip", ip).put("name", "STB (UDP 25860)").put("playlist","")) }

            val prefix = localSubnetPrefix()
            if (prefix != null) {
                val tasks = (1..254).map { h ->
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
                tasks.take(100).forEach { io.execute(it) }
                try { Thread.sleep(1500) } catch (_: Exception) {}
            }
            return arr.toString()
        }

        // FIXED: block body (no expression body with returns)
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

        // ─── Control protocol (GCDH + zlib) ─────────────────────────────────────
        @JavascriptInterface
        fun fetchChannelsFromStb(): String {
            val ip = getStbIp().trim()
            if (ip.isEmpty()) return "[]"

            val debug = StringBuilder()
            val ports = intArrayOf(20000, 4113, 8888)

            val seq = mutableListOf<ByteArray>()
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

                for (f in frames) { out.write(f); out.flush(); try { Thread.sleep(50) } catch (_: Exception) {} }

                val framesIn = readGcdhFrames(inn, 3000, debug)
                if (framesIn.isEmpty()) { debug.append("No GCDH on $port\n"); return JSONArray() }

                val channels = parseChannels(framesIn, ip, debug)
                if (channels.length() == 0) debug.append("Parsed 0 channels on $port\n")
                channels
            } catch (e: Exception) {
                debug.append("Error on $ip:$port -> ${e.message}\n")
                JSONArray()
            } finally { try { sock?.close() } catch (_: Exception) {} }
        }

        private fun beInt(b: ByteArray, o: Int): Int {
            return ((b[o+3].toInt() and 0xFF) shl 24) or
                   ((b[o+2].toInt() and 0xFF) shl 16) or
                   ((b[o+1].toInt() and 0xFF) shl 8)  or
                   ( b[o+0].toInt() and 0xFF)
        }

        private fun readExact(inn: InputStream, n: Int): ByteArray? {
            val out = ByteArray(n)
            var off = 0
            var rem = n
            while (rem > 0) {
                val r = try { inn.read(out, off, rem) } catch (_: Exception) { -1 }
                if (r <= 0) return null
                off += r; rem -= r
            }
            return out
        }

        private fun tryInflate(data: ByteArray): ByteArray? = try {
            val inf = Inflater()
            inf.setInput(data)
            val out = ByteArray(2 * 1024 * 1024)
            val n = inf.inflate(out)
            inf.end()
            if (n > 0) out.copyOf(n) else null
        } catch (_: Exception) { null }

        private fun readGcdhFrames(inn: InputStream, totalWaitMs: Long, debug: StringBuilder): List<Pair<Int,ByteArray>> {
            val list = ArrayList<Pair<Int,ByteArray>>()
            val deadline = System.currentTimeMillis() + max(1500L, totalWaitMs)
            while (System.currentTimeMillis() < deadline) {
                if (inn.available() < 16) { try { Thread.sleep(20) } catch (_: Exception) {} ; continue }
                val hdr = readExact(inn, 16) ?: break
                if (!(hdr[0].toInt()==71 && hdr[1].toInt()==67 && hdr[2].toInt()==68 && hdr[3].toInt()==72)) {
                    debug.append("Non-GCDH preface seen\n"); break
                }
                val payloadLen = beInt(hdr, 4)
                val msgType    = beInt(hdr, 8)
                val extra      = beInt(hdr,12)
                debug.append("GCDH len=$payloadLen type=$msgType extra=$extra\n")
                if (payloadLen <= 0 || payloadLen > 8*1024*1024) break
                val comp = readExact(inn, payloadLen) ?: break
                val inflated = tryInflate(comp) ?: comp
                list += msgType to inflated
            }
            return list
        }

        private fun parseChannels(all: List<Pair<Int,ByteArray>>, ip: String, debug: StringBuilder): JSONArray {
            val out = JSONArray()
            val seen = HashSet<String>()
            for ((type, bytes) in all) {
                val text = try { String(bytes, Charsets.UTF_8) } catch (_: Exception) { "" }
                debug.append("-- type=$type len=${text.length}\n")
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
                    }
                } catch (_: Exception) {}
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

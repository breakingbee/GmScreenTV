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
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.Inflater
import kotlin.math.min
import kotlin.math.max

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

        // ========= NEW: Start/End framed control session on port 20000 =========

        private fun jsonFrame(json: String): ByteArray {
            val body = json.trim().toByteArray(Charsets.UTF_8)
            val header = "Start" + String.format("%07d", body.size) + "End"
            return header.toByteArray(Charsets.UTF_8) + body
        }

        private fun xmlFrame(deviceName: String, uuid: String): ByteArray {
            val xml = """
                <?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
                <Command request="998"><data>$deviceName</data><uuid>$uuid</uuid></Command>
            """.trimIndent()
            val body = xml.toByteArray(Charsets.UTF_8)
            val header = "Start" + String.format("%07d", body.size) + "End"
            return header.toByteArray(Charsets.UTF_8) + body
        }

        @JavascriptInterface
        fun fetchChannelsFromStb(): String {
            val ip = getStbIp().trim()
            if (ip.isEmpty()) return "[]"

            val port = 20000
            val debug = StringBuilder()
            var sock: Socket? = null
            val outArr = JSONArray()
            val seen = HashSet<String>()

            fun putChan(name: String, id: String) {
                if (id.isNotEmpty() && seen.add(id)) {
                    outArr.put(
                        JSONObject()
                            .put("name", if (name.isNotEmpty()) name else "Channel $id")
                            .put("id", id)
                            .put("url", "http://$ip:8085/player.$id")
                    )
                }
            }

            try {
                debug.append("Connecting $ip:$port\n")
                sock = Socket()
                sock!!.tcpNoDelay = true
                sock!!.soTimeout = 12000
                // Avoid overload ambiguity by using explicit java.net.InetSocketAddress
                sock!!.connect(InetSocketAddress(ip, port), 2500)
                val os: OutputStream = sock!!.getOutputStream()
                val ins: InputStream = sock!!.getInputStream()

                // 1) XML login
                val uuid = java.util.UUID.randomUUID().toString() + "-02:00:00:00:00:00"
                os.write(xmlFrame("AndroidTV", uuid)); os.flush(); Thread.sleep(50)

                // 2) Burst of requests (from your capture)
                val burst = arrayOf("23","16","20","12","24")
                for (r in burst) { os.write(jsonFrame("""{"request":"$r"}""")); os.flush(); Thread.sleep(20) }

                // 3) Windows 0..1999 step 100 (adjust later if needed)
                for (from in 0..1900 step 100) {
                    val to = from + 99
                    os.write(jsonFrame("""{"FromIndex":"$from","ToIndex":"$to","request":"0"}"""))
                    os.flush()
                    Thread.sleep(15)
                }

                // 4) Finalizer burst you captured
                os.write(jsonFrame("""{"request":"22"}""")); os.flush(); Thread.sleep(20)
                os.write(jsonFrame("""{"request":"1012"}""")); os.flush(); Thread.sleep(20)
                os.write(jsonFrame("""{"request":"20"}""")); os.flush()

                // 5) Read GCDH frames ~12s and parse
                val endAt = System.currentTimeMillis() + 12000
                val hdr = ByteArray(16)

                fun be(b: ByteArray, o: Int): Int {
                    return ((b[o+3].toInt() and 0xFF) shl 24) or
                           ((b[o+2].toInt() and 0xFF) shl 16) or
                           ((b[o+1].toInt() and 0xFF) shl 8) or
                           (b[o].toInt() and 0xFF)
                }

                while (System.currentTimeMillis() < endAt) {
                    if (ins.available() < 16) { Thread.sleep(15); continue }
                    val n = ins.read(hdr)
                    if (n != 16) break
                    if (!(hdr[0]=='G'.code.toByte() && hdr[1]=='C'.code.toByte() && hdr[2]=='D'.code.toByte() && hdr[3]=='H'.code.toByte())) {
                        debug.append("Non-GCDH header\n")
                        continue
                    }
                    val plen = be(hdr,4); val typ = be(hdr,8); val extra = be(hdr,12)
                    debug.append("GCDH frame: len=$plen type=$typ extra=$extra\n")
                    if (plen <= 0 || plen > 8*1024*1024) { debug.append("bad len\n"); break }

                    val comp = ByteArray(plen)
                    var off = 0
                    while (off < plen) {
                        val r = ins.read(comp, off, plen - off)
                        if (r <= 0) { off = plen; break }
                        off += r
                    }

                    // Inflate if possible
                    val text = try {
                        val inf = Inflater()
                        inf.setInput(comp)
                        val out = ByteArray(2 * 1024 * 1024)
                        val got = inf.inflate(out)
                        inf.end()
                        if (got > 0) String(out, 0, got, Charsets.UTF_8) else String(comp, Charsets.UTF_8)
                    } catch (_: Exception) {
                        String(comp, Charsets.UTF_8)
                    }
                    debug.append("-- type=$typ textLen=${text.length}\n")

                    // Parse arrays/objects for ServiceID/ServiceName
                    try {
                        val t = text.trim()
                        if (t.startsWith("[")) {
                            val arr = JSONArray(t)
                            for (i in 0 until arr.length()) {
                                val o = arr.optJSONObject(i) ?: continue
                                val name = o.optString("ServiceName", o.optString("name",""))
                                val id = o.optString("ServiceID", o.optString("id",""))
                                if (id.isNotEmpty()) putChan(name, id)
                            }
                        } else if (t.startsWith("{")) {
                            val obj = JSONObject(t)
                            val keys = arrayOf("channels","list","items","programs","array")
                            for (k in keys) if (obj.has(k)) {
                                val arr = obj.optJSONArray(k) ?: continue
                                for (i in 0 until arr.length()) {
                                    val o = arr.optJSONObject(i) ?: continue
                                    val name = o.optString("ServiceName", o.optString("name",""))
                                    val id = o.optString("ServiceID", o.optString("id",""))
                                    if (id.isNotEmpty()) putChan(name, id)
                                }
                            }
                        }
                    } catch (_: Exception) {
                        // Fallback: player.<ID>
                        Regex("""player\.([0-9]+)""").findAll(text).forEach { m ->
                            putChan("", m.groupValues[1])
                        }
                    }
                }
            } catch (e: Exception) {
                debug.append("Error $ip:$port -> ${e.message}\n")
            } finally {
                try { sock?.close() } catch (_: Exception) {}
            }

            prefs.edit().putString("last_debug", debug.toString().take(12000)).apply()
            return outArr.toString()
        }

        @JavascriptInterface
        fun getLastDebug(): String = prefs.getString("last_debug", "") ?: ""

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

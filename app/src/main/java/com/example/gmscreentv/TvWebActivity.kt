// ---- put these inside NativeBridge ----

// Build the Start%07dEnd framing for JSON
private fun jsonFrame(json: String): ByteArray {
    val body = json.trim().toByteArray(Charsets.UTF_8)
    val header = "Start" + String.format("%07d", body.size) + "End"
    return header.toByteArray(Charsets.UTF_8) + body
}

// Build the Start%07dEnd framing for XML login (request 998)
private fun xmlFrame(deviceName: String, uuid: String): ByteArray {
    val xml = """
        <?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
        <Command request="998"><data>$deviceName</data><uuid>$uuid</uuid></Command>
    """.trimIndent()
    val body = xml.toByteArray(Charsets.UTF_8)
    val header = "Start" + String.format("%07d", body.size) + "End"
    return header.toByteArray(Charsets.UTF_8) + body
}

// REPLACE your existing fetchChannelsFromStb() with this:
@JavascriptInterface
fun fetchChannelsFromStb(): String {
    val ip = getStbIp().trim()
    if (ip.isEmpty()) return "[]"

    // Use only 20000 first, since that’s where we see GCDH + Start/End frames.
    val port = 20000
    val debug = StringBuilder()
    var sock: java.net.Socket? = null
    val outArr = org.json.JSONArray()
    val seen = HashSet<String>()

    fun putChan(name: String, id: String) {
        if (id.isNotEmpty() && seen.add(id)) {
            outArr.put(
                org.json.JSONObject()
                    .put("name", if (name.isNotEmpty()) name else "Channel $id")
                    .put("id", id)
                    .put("url", "http://$ip:8085/player.$id")
            )
        }
    }

    try {
        debug.append("Connecting $ip:$port\n")
        sock = java.net.Socket()
        sock!!.tcpNoDelay = true
        sock!!.soTimeout = 12000
        sock!!.connect(java.net.InetSocketAddress(ip, port), 2500)
        val os = sock!!.getOutputStream()
        val ins = sock!!.getInputStream()

        // 1) XML login (use stable device name + uuid; any uuid is fine)
        val uuid = java.util.UUID.randomUUID().toString() + "-02:00:00:00:00:00"
        os.write(xmlFrame("AndroidTV", uuid)); os.flush()
        Thread.sleep(50)

        // 2) Control burst (exact order from your trace)
        val burst = arrayOf("23","16","20","12","24")
        for (r in burst) { os.write(jsonFrame("""{"request":"$r"}""")); os.flush(); Thread.sleep(20) }

        // 3) Fetch windows (0..1999 step 100). Adjust end if you want smaller.
        for (from in 0..1900 step 100) {
            val to = from + 99
            os.write(jsonFrame("""{"FromIndex":"$from","ToIndex":"$to","request":"0"}"""))
            os.flush()
            Thread.sleep(20)
        }

        // 4) Finalizer burst from your capture
        os.write(jsonFrame("""{"request":"22"}""")); os.flush(); Thread.sleep(20)
        os.write(jsonFrame("""{"request":"1012"}""")); os.flush(); Thread.sleep(20)
        os.write(jsonFrame("""{"request":"20"}""")); os.flush()

        // 5) Read replies ~12s, parse GCDH payloads for JSON/text
        val endAt = System.currentTimeMillis() + 12000
        val hdrBuf = ByteArray(16)
        while (System.currentTimeMillis() < endAt) {
            // read GCDH header (16 bytes) if available
            if (ins.available() < 16) { Thread.sleep(15); continue }
            val n = ins.read(hdrBuf)
            if (n != 16) break
            if (!(hdrBuf[0]=='G'.code.toByte() && hdrBuf[1]=='C'.code.toByte() && hdrBuf[2]=='D'.code.toByte() && hdrBuf[3]=='H'.code.toByte())) {
                debug.append("Non-GCDH header\n"); continue
            }
            // big-endian ints at 4,8,12
            fun be(o:Int): Int {
                return ((hdrBuf[o+3].toInt() and 0xFF) shl 24) or
                       ((hdrBuf[o+2].toInt() and 0xFF) shl 16) or
                       ((hdrBuf[o+1].toInt() and 0xFF) shl 8)  or
                       ( hdrBuf[o+0].toInt() and 0xFF)
            }
            val plen = be(4); val typ = be(8); val extra = be(12)
            debug.append("GCDH frame: len=$plen type=$typ extra=$extra\n")
            if (plen <= 0 || plen > 8*1024*1024) { debug.append("bad len\n"); break }

            val comp = ByteArray(plen)
            var off = 0
            while (off < plen) {
                val r = ins.read(comp, off, plen - off)
                if (r <= 0) { off = plen; break }
                off += r
            }

            // Try to inflate (zlib). If fails, treat as plain text.
            val text = try {
                val inf = java.util.zip.Inflater()
                inf.setInput(comp)
                val out = ByteArray(2 * 1024 * 1024)
                val got = inf.inflate(out)
                inf.end()
                if (got > 0) String(out, 0, got, Charsets.UTF_8) else String(comp, Charsets.UTF_8)
            } catch (_: Exception) {
                String(comp, Charsets.UTF_8)
            }
            debug.append("-- type=$typ textLen=${text.length}\n")

            // Parse JSON-like text for ServiceName/ServiceID
            try {
                if (text.trim().startsWith("[")) {
                    val arr = org.json.JSONArray(text)
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val name = o.optString("ServiceName", o.optString("name",""))
                        val id = o.optString("ServiceID", o.optString("id",""))
                        if (id.isNotEmpty()) putChan(name, id)
                    }
                } else if (text.trim().startsWith("{")) {
                    val obj = org.json.JSONObject(text)
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
                // also try regex fallback for player.<ID>
                val m = Regex("""player\.([0-9]+)""").findAll(text)
                m.forEach { putChan("", it.groupValues[1]) }
            }
        }

    } catch (e: Exception) {
        debug.append("Error $ip:$port -> ${e.message}\n")
    } finally {
        try { sock?.close() } catch (_: Exception) {}
    }

    // Save debug so you can open it with “Show debug”
    prefs.edit().putString("last_debug", debug.toString().take(12000)).apply()
    return outArr.toString()
}

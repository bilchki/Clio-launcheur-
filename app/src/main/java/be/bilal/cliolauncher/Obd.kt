package be.bilal.cliolauncher

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

/**
 * Lecture OBD2 via un adaptateur ELM327 (Bluetooth classique ou Wi-Fi).
 * Tourne dans un thread à part et se reconnecte tout seul.
 */
@SuppressLint("MissingPermission")
class Obd(
    private val onData: (String) -> Unit,
    private val onStatus: (String) -> Unit
) {
    @Volatile private var running = false
    @Volatile var pendingCmd: String? = null
    private var thread: Thread? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var closer: Closeable? = null
    private var mode = "bt"
    private var target = ""
    private val fails = HashMap<String, Int>()

    fun start(mode: String, target: String) {
        stop()
        this.mode = mode
        this.target = target
        running = true
        thread = Thread { loop() }.also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running = false
        close()
        thread?.interrupt()
        thread = null
    }

    private fun close() {
        try { closer?.close() } catch (e: Exception) { }
        closer = null; input = null; output = null
    }

    private fun connect(): Boolean {
        if (mode == "wifi") {
            val parts = (if (target.isBlank()) "192.168.0.10:35000" else target).split(":")
            val host = parts[0]
            val port = if (parts.size > 1) parts[1].toIntOrNull() ?: 35000 else 35000
            val s = Socket()
            s.connect(InetSocketAddress(host, port), 4000)
            s.soTimeout = 4000
            input = s.getInputStream(); output = s.getOutputStream(); closer = s
            return true
        }
        val ad = BluetoothAdapter.getDefaultAdapter()
        if (ad == null) { onStatus("bt_absent"); return false }
        if (!ad.isEnabled) { onStatus("bt_off"); return false }
        val bonded = ad.bondedDevices ?: emptySet()
        val dev = bonded.firstOrNull {
            target.isNotBlank() && ((it.name ?: "").equals(target, true) || it.address.equals(target, true))
        } ?: bonded.firstOrNull {
            val n = (it.name ?: "").uppercase()
            n.contains("OBD") || n.contains("ELM") || n.contains("LINK") || n.contains("VGATE") || n.contains("KONNWEI")
        }
        if (dev == null) { onStatus("bt_none"); return false }
        try { ad.cancelDiscovery() } catch (e: Exception) { }
        val uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        val sock = try {
            val s = dev.createRfcommSocketToServiceRecord(uuid); s.connect(); s
        } catch (e: Exception) {
            val s = dev.createInsecureRfcommSocketToServiceRecord(uuid); s.connect(); s
        }
        input = sock.inputStream; output = sock.outputStream; closer = sock
        return true
    }

    private fun cmd(c: String, timeoutMs: Long = 2000): String {
        val o = output ?: throw IOException("no output")
        val i = input ?: throw IOException("no input")
        o.write((c + "\r").toByteArray()); o.flush()
        val sb = StringBuilder()
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (i.available() > 0) {
                val b = i.read()
                if (b < 0) throw IOException("closed")
                val ch = b.toChar()
                if (ch == '>') break
                sb.append(ch)
            } else {
                Thread.sleep(10)
            }
        }
        return sb.toString().replace("SEARCHING...", "").trim()
    }

    /** Envoie un PID du mode 01 et renvoie les octets de données, ou null. */
    private fun pid(p: String): IntArray? {
        if ((fails[p] ?: 0) >= 3) return null
        val r = cmd("01$p").uppercase().replace(" ", "").replace("\r", "").replace("\n", "")
        val idx = r.indexOf("41$p")
        if (idx < 0) { fails[p] = (fails[p] ?: 0) + 1; return null }
        fails[p] = 0
        val hex = r.substring(idx + 4)
        val out = ArrayList<Int>()
        var k = 0
        while (k + 2 <= hex.length && out.size < 4) {
            val v = hex.substring(k, k + 2).toIntOrNull(16) ?: break
            out.add(v); k += 2
        }
        return if (out.isEmpty()) null else out.toIntArray()
    }

    private fun decodeDtc(a: Int, b: Int): String {
        val letter = "PCBU"[(a shr 6) and 3]
        val d1 = (a shr 4) and 3
        return letter.toString() + d1 + String.format("%01X%02X", a and 0x0F, b)
    }

    private fun handleCmd(c: String) {
        if (c == "clear") {
            cmd("04", 5000)
            onData(JSONObject().put("cleared", true).toString())
            return
        }
        val raw = cmd("03", 6000).uppercase()
        val codes = JSONArray()
        for (line in raw.split("\r", "\n")) {
            var l = line.replace(" ", "").trim()
            val i = l.indexOf("43")
            if (i < 0) continue
            l = l.substring(i + 2)
            val bytes = ArrayList<Int>()
            var k = 0
            while (k + 2 <= l.length) { l.substring(k, k + 2).toIntOrNull(16)?.let { bytes.add(it) }; k += 2 }
            var start = if (bytes.size % 2 == 1) 1 else 0 // octet de comptage en CAN
            while (start + 1 < bytes.size) {
                val a = bytes[start]; val b = bytes[start + 1]
                if (a != 0 || b != 0) codes.put(decodeDtc(a, b))
                start += 2
            }
        }
        onData(JSONObject().put("dtc", codes).toString())
    }

    private fun loop() {
        while (running) {
            try {
                onStatus("connecting")
                if (!connect()) { Thread.sleep(10000); continue }
                cmd("ATZ", 3000)
                for (c in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATSP0")) cmd(c, 1500)
                val sup = cmd("0100", 8000).replace(" ", "")
                if (!sup.contains("4100")) {
                    onStatus("no_ecu"); close(); Thread.sleep(8000); continue
                }
                onStatus("ok")
                fails.clear()
                var n = 0
                while (running) {
                    pendingCmd?.let { c -> pendingCmd = null; handleCmd(c) }
                    val o = JSONObject()
                    pid("0C")?.let { if (it.size >= 2) o.put("rpm", (it[0] * 256 + it[1]) / 4) }
                    pid("0D")?.let { o.put("speed", it[0]) }
                    pid("05")?.let { o.put("coolant", it[0] - 40) }
                    pid("04")?.let { o.put("load", it[0] * 100 / 255) }
                    pid("10")?.let { if (it.size >= 2) o.put("maf", (it[0] * 256 + it[1]) / 100.0) }
                    pid("0B")?.let { o.put("map", it[0]) }
                    pid("0F")?.let { o.put("iat", it[0] - 40) }
                    if (n % 5 == 0) {
                        pid("11")?.let { o.put("throttle", it[0] * 100 / 255) }
                        pid("2F")?.let { o.put("fuel", it[0] * 100 / 255) }
                        val v = cmd("ATRV").uppercase().replace("V", "").trim().toDoubleOrNull()
                        if (v != null) o.put("volt", v)
                    }
                    n++
                    onData(o.toString())
                    Thread.sleep(150)
                }
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                onStatus("lost")
                close()
                try { Thread.sleep(4000) } catch (ie: InterruptedException) { break }
            }
        }
        close()
    }
}

package com.example.animalocal.engine

/**
 * Turns the crash report Android keeps for a native crash (ApplicationExitInfo trace stream)
 * into a few readable lines: signal, fault address and the crashing thread's top frames.
 * Android 12+ delivers a protobuf tombstone, Android 11 a text one; both are handled.
 */
object Tombstone {
    private class Pb(val b: ByteArray) {
        fun fields(start: Int, end: Int, cb: (Int, Int, Long, Int, Int) -> Unit) {
            var p = start
            fun varint(): Long {
                var r = 0L
                var s = 0
                while (true) {
                    val x = b[p++].toInt() and 0xFF
                    r = r or ((x and 0x7F).toLong() shl s)
                    if (x < 0x80) return r
                    s += 7
                }
            }
            while (p < end) {
                val tag = varint().toInt()
                val f = tag ushr 3
                when (tag and 7) {
                    0 -> cb(f, 0, varint(), 0, 0)
                    1 -> {
                        cb(f, 1, 0L, p, p + 8)
                        p += 8
                    }
                    2 -> {
                        val n = varint().toInt()
                        cb(f, 2, 0L, p, p + n)
                        p += n
                    }
                    5 -> p += 4
                    else -> return
                }
            }
        }

        fun str(s: Int, e: Int): String = String(b, s, e - s, Charsets.UTF_8)
    }

    fun summarize(data: ByteArray): String {
        val text = String(data, Charsets.ISO_8859_1)
        if (text.contains("backtrace:")) {
            return text.lines()
                .filter { l -> l.trimStart().let { it.startsWith("#") || it.startsWith("signal") || it.startsWith("Abort") } }
                .take(24)
                .joinToString("\n")
        }
        return try {
            parse(data)
        } catch (e: Exception) {
            "tombstone parse failed (${e.message}); " + strings(data)
        }
    }

    private fun strings(d: ByteArray): String {
        val out = LinkedHashSet<String>()
        val sb = StringBuilder()
        fun flush() {
            if (sb.length >= 5) {
                val s = sb.toString()
                if (s.contains(".so") || s.startsWith("SIG")) out.add(s)
            }
            sb.setLength(0)
        }
        for (x in d) {
            val c = x.toInt() and 0xFF
            if (c in 32..126) sb.append(c.toChar()) else flush()
        }
        flush()
        return out.take(20).joinToString("\n")
    }

    private fun parse(data: ByteArray): String {
        val pb = Pb(data)
        var crashTid = -1L
        var sig = ""
        var abortMsg = ""
        val causes = ArrayList<String>()
        val threads = HashMap<Long, IntArray>()

        pb.fields(0, data.size) { f, w, v, s, e ->
            if (f == 6 && w == 0) crashTid = v
            if (f == 11 && w == 2) {
                var name = ""
                var cname = ""
                var addr = 0L
                pb.fields(s, e) { f2, w2, v2, s2, e2 ->
                    if (f2 == 2 && w2 == 2) name = pb.str(s2, e2)
                    if (f2 == 4 && w2 == 2) cname = pb.str(s2, e2)
                    if (f2 == 9 && w2 == 0) addr = v2
                }
                sig = "$name $cname fault addr 0x${addr.toString(16)}"
            }
            if (f == 14 && w == 2) abortMsg = pb.str(s, e)
            if (f == 15 && w == 2) {
                pb.fields(s, e) { f2, w2, _, s2, e2 ->
                    if (f2 == 1 && w2 == 2) causes.add(pb.str(s2, e2))
                }
            }
            if (f == 16 && w == 2) {
                var key = -1L
                var vs = -1
                var ve = -1
                pb.fields(s, e) { f2, w2, v2, s2, e2 ->
                    if (f2 == 1 && w2 == 0) key = v2
                    if (f2 == 2 && w2 == 2) {
                        vs = s2
                        ve = e2
                    }
                }
                if (vs >= 0) threads[key] = intArrayOf(vs, ve)
            }
        }

        val frames = StringBuilder()
        val t = threads[crashTid]
        if (t != null) {
            var n = 0
            pb.fields(t[0], t[1]) { f, w, _, s, e ->
                if (f == 4 && w == 2 && n < 14) {
                    var fn = ""
                    var off = 0L
                    var file = ""
                    var rel = 0L
                    pb.fields(s, e) { f2, w2, v2, s2, e2 ->
                        if (f2 == 1 && w2 == 0) rel = v2
                        if (f2 == 4 && w2 == 2) fn = pb.str(s2, e2)
                        if (f2 == 5 && w2 == 0) off = v2
                        if (f2 == 6 && w2 == 2) file = pb.str(s2, e2)
                    }
                    frames.append("\n  #").append(n).append(' ')
                        .append(file.substringAfterLast('/')).append("+0x").append(rel.toString(16))
                    if (fn.isNotEmpty()) frames.append(' ').append(fn).append("+0x").append(off.toString(16))
                    n++
                }
            }
        }

        val head = "$sig; crash tid $crashTid" +
            (if (abortMsg.isNotEmpty()) "; abort: $abortMsg" else "") +
            (if (causes.isNotEmpty()) "; " + causes.joinToString("; ") else "")
        return if (frames.isEmpty()) head + "\n" + strings(data) else head + frames.toString()
    }
}

package com.example.animalocal.models

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream

data class ModelInfo(
    val format: String,
    val arch: String?,
    val name: String?,
    val tensors: Int,
    val types: Map<String, Int>,
    val sizeBytes: Long,
    val roleGuess: Role?,
) {
    fun describe(): String = buildString {
        append(format)
        arch?.let { append(" · arch $it") }
        append(" · ${fmtBytes(sizeBytes)} · $tensors tensors")
        name?.let { append("\nname: $it") }
        if (types.isNotEmpty()) append("\nquant: " + types.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key}×${it.value}" })
        append("\nlooks like: ${roleGuess?.label ?: "unknown"}")
    }
}

fun fmtBytes(n: Long): String = when {
    n >= 1L shl 30 -> "%.2f GB".format(n / 1073741824.0)
    n >= 1L shl 20 -> "%.0f MB".format(n / 1048576.0)
    else -> "$n B"
}

/** Reads only headers (GGUF metadata / safetensors JSON header); never loads weights. */
object ModelInspector {
    private val ggmlTypes = mapOf(
        0 to "F32", 1 to "F16", 2 to "Q4_0", 3 to "Q4_1", 6 to "Q5_0", 7 to "Q5_1", 8 to "Q8_0", 9 to "Q8_1",
        10 to "Q2_K", 11 to "Q3_K", 12 to "Q4_K", 13 to "Q5_K", 14 to "Q6_K", 15 to "Q8_K", 30 to "BF16",
    )

    fun inspect(f: File): ModelInfo = try {
        when {
            f.name.endsWith(".gguf", true) -> gguf(f)
            f.name.endsWith(".safetensors", true) -> safetensors(f)
            else -> ModelInfo("unknown", null, null, 0, emptyMap(), f.length(), null)
        }
    } catch (e: Exception) {
        ModelInfo("unreadable (${e.message})", null, null, 0, emptyMap(), f.length(), null)
    }

    private class Le(val s: InputStream) {
        fun u8(): Int = s.read().also { if (it < 0) throw EOFException() }
        fun u32(): Long { var r = 0L; for (i in 0 until 4) r = r or (u8().toLong() shl (8 * i)); return r }
        fun u64(): Long { var r = 0L; for (i in 0 until 8) r = r or (u8().toLong() shl (8 * i)); return r }
        fun skip(n: Long) {
            var left = n
            while (left > 0) {
                val k = s.skip(left)
                if (k <= 0) { if (s.read() < 0) throw EOFException(); left-- } else left -= k
            }
        }
        fun str(): String {
            val n = u64().toInt()
            val b = ByteArray(n)
            var o = 0
            while (o < n) { val r = s.read(b, o, n - o); if (r < 0) throw EOFException(); o += r }
            return String(b, Charsets.UTF_8)
        }
        fun skipStr() { skip(u64()) }
    }

    private fun fixedSize(t: Int): Long = when (t) {
        0, 1, 7 -> 1; 2, 3 -> 2; 4, 5, 6 -> 4; 10, 11, 12 -> 8
        else -> throw IOException("unsupported gguf value type $t")
    }

    private fun gguf(f: File): ModelInfo {
        BufferedInputStream(f.inputStream(), 1 shl 16).use { ins ->
            val le = Le(ins)
            if (le.u32() != 0x46554747L) throw IOException("not a GGUF file")
            le.u32() // version
            val tensorCount = le.u64().toInt()
            val kvCount = le.u64().toInt()
            var arch: String? = null
            var name: String? = null
            repeat(kvCount) {
                val key = le.str()
                val t = le.u32().toInt()
                when (t) {
                    8 -> { val v = le.str(); if (key == "general.architecture") arch = v; if (key == "general.name") name = v }
                    9 -> {
                        val et = le.u32().toInt(); val n = le.u64()
                        if (et == 8) { var i = 0L; while (i < n) { le.skipStr(); i++ } } else le.skip(n * fixedSize(et))
                    }
                    else -> le.skip(fixedSize(t))
                }
            }
            val types = HashMap<String, Int>()
            var xEmbedder = false; var tokenEmbd = false; var decoder = false
            repeat(tensorCount) {
                val n = le.str()
                val nd = le.u32().toInt()
                le.skip(nd * 8L)
                val ty = le.u32().toInt()
                le.skip(8)
                val tn = ggmlTypes[ty] ?: "type$ty"
                types[tn] = (types[tn] ?: 0) + 1
                if (n.contains("x_embedder")) xEmbedder = true
                if (n == "token_embd.weight") tokenEmbd = true
                if (n.contains("decoder.")) decoder = true
            }
            val role = when { xEmbedder -> Role.DIFFUSION; tokenEmbd -> Role.TEXT; decoder -> Role.VAE; else -> null }
            return ModelInfo("GGUF", arch, name, tensorCount, types, f.length(), role)
        }
    }

    private fun safetensors(f: File): ModelInfo {
        f.inputStream().buffered().use { ins ->
            val le = Le(ins)
            val n = le.u64()
            if (n <= 0 || n > 64L * 1024 * 1024) throw IOException("bad safetensors header")
            val b = ByteArray(n.toInt())
            var o = 0
            while (o < b.size) { val r = ins.read(b, o, b.size - o); if (r < 0) throw EOFException(); o += r }
            val j = JSONObject(String(b, Charsets.UTF_8))
            val types = HashMap<String, Int>()
            var count = 0; var vae = false; var dit = false; var llm = false
            val keys = j.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (k == "__metadata__") continue
                count++
                val dt = j.getJSONObject(k).optString("dtype", "?")
                types[dt] = (types[dt] ?: 0) + 1
                if (k.contains("decoder") || k.contains("post_quant")) vae = true
                if (k.contains("diffusion_model") || k.contains("x_embedder")) dit = true
                if (k.contains("embed_tokens")) llm = true
            }
            val role = when { dit -> Role.DIFFUSION; llm -> Role.TEXT; vae -> Role.VAE; else -> null }
            return ModelInfo("safetensors", null, null, count, types, f.length(), role)
        }
    }
}

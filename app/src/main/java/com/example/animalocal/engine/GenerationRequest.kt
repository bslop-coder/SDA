package com.example.animalocal.engine

import android.os.Bundle

data class LoraRef(val path: String, val strength: Float)

/**
 * Everything the engine needs for one image. New feature = new field WITH A DEFAULT
 * (and one line in toBundle/fromBundle), so old call sites keep compiling.
 */
data class GenerationRequest(
    val diffusionPath: String,
    val llmPath: String,
    val vaePath: String,
    val prompt: String,
    val negative: String = "",
    val sampler: String = "euler",
    val width: Int = 512,
    val height: Int = 512,
    val steps: Int = 8,
    val cfg: Float = 1f,
    val seed: Long = 0,
    val vaeTiling: Boolean = true,
    val flashAttn: Boolean = true,
    val threads: Int = 2,
    val affinityMask: Int = 0xC0,
    val offloadMode: Int = 1,
    val backend: String = "",
    val env: String = "",
    val loras: List<LoraRef> = emptyList(),
) {
    fun toBundle() = Bundle().apply {
        putString("diff", diffusionPath); putString("llm", llmPath); putString("vae", vaePath)
        putString("prompt", prompt); putString("neg", negative); putString("sampler", sampler)
        putInt("w", width); putInt("h", height); putInt("steps", steps)
        putFloat("cfg", cfg); putLong("seed", seed)
        putBoolean("tiling", vaeTiling); putBoolean("fa", flashAttn)
        putInt("threads", threads); putInt("aff", affinityMask); putInt("offload", offloadMode); putString("backend", backend); putString("env", env)
        putStringArray("lp", loras.map { it.path }.toTypedArray())
        putFloatArray("ls", loras.map { it.strength }.toFloatArray())
    }

    companion object {
        fun fromBundle(b: Bundle): GenerationRequest {
            val lp = b.getStringArray("lp") ?: emptyArray()
            val ls = b.getFloatArray("ls") ?: FloatArray(0)
            return GenerationRequest(
                diffusionPath = b.getString("diff", ""), llmPath = b.getString("llm", ""),
                vaePath = b.getString("vae", ""), prompt = b.getString("prompt", ""),
                negative = b.getString("neg", ""), sampler = b.getString("sampler", "euler"),
                width = b.getInt("w", 512), height = b.getInt("h", 512), steps = b.getInt("steps", 8),
                cfg = b.getFloat("cfg", 1f), seed = b.getLong("seed", 0),
                vaeTiling = b.getBoolean("tiling", true), flashAttn = b.getBoolean("fa", true),
                threads = b.getInt("threads", 2), affinityMask = b.getInt("aff", 0),
                offloadMode = b.getInt("offload", 1), backend = b.getString("backend", ""), env = b.getString("env", ""),
                loras = lp.indices.map { LoraRef(lp[it], ls[it]) },
            )
        }
    }
}

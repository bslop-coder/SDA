package com.example.animalocal.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class AppSettings(
    val steps: Int = 8,
    val cfg: Float = 1f,
    val sampler: String = "euler",
    val width: Int = 512,
    val height: Int = 512,
    val seed: Long = -1,
    val negative: String = "",
    val vaeTiling: Boolean = true,
    val flashAttn: Boolean = true,
    val offloadMode: Int = 1,
    val threads: Int = 2,
    val affinityHex: String = "C0",
) {
    fun affinityMask(): Int = affinityHex.trim().toIntOrNull(16) ?: 0
}

data class LoraEntry(val name: String, val path: String, val strength: Float = 1f, val enabled: Boolean = true)

/** Stored as JSON with per-field defaults, so adding a setting never invalidates saved data. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("anima", Context.MODE_PRIVATE)

    var prompt: String
        get() = sp.getString("prompt", "") ?: ""
        set(v) { sp.edit().putString("prompt", v).apply() }

    fun loadSettings(): AppSettings {
        val d = AppSettings()
        val j = try { JSONObject(sp.getString("settings", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
        return AppSettings(
            steps = j.optInt("steps", d.steps),
            cfg = j.optDouble("cfg", d.cfg.toDouble()).toFloat(),
            sampler = j.optString("sampler", d.sampler),
            width = j.optInt("width", d.width),
            height = j.optInt("height", d.height),
            seed = j.optLong("seed", d.seed),
            negative = j.optString("negative", d.negative),
            vaeTiling = j.optBoolean("vaeTiling", d.vaeTiling),
            flashAttn = j.optBoolean("flashAttn", d.flashAttn),
            offloadMode = j.optInt("offloadMode", d.offloadMode),
            threads = j.optInt("threads", d.threads),
            affinityHex = j.optString("affinityHex", d.affinityHex),
        )
    }

    fun saveSettings(s: AppSettings) {
        val j = JSONObject()
            .put("steps", s.steps).put("cfg", s.cfg.toDouble()).put("sampler", s.sampler)
            .put("width", s.width).put("height", s.height).put("seed", s.seed)
            .put("negative", s.negative).put("vaeTiling", s.vaeTiling).put("flashAttn", s.flashAttn)
            .put("offloadMode", s.offloadMode).put("threads", s.threads).put("affinityHex", s.affinityHex)
        sp.edit().putString("settings", j.toString()).apply()
    }

    fun loadLoras(): List<LoraEntry> = try {
        val a = JSONArray(sp.getString("loras", "[]") ?: "[]")
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            LoraEntry(o.getString("name"), o.getString("path"), o.optDouble("strength", 1.0).toFloat(), o.optBoolean("enabled", true))
        }
    } catch (e: Exception) { emptyList() }

    fun saveLoras(list: List<LoraEntry>) {
        val a = JSONArray()
        list.forEach {
            a.put(JSONObject().put("name", it.name).put("path", it.path).put("strength", it.strength.toDouble()).put("enabled", it.enabled))
        }
        sp.edit().putString("loras", a.toString()).apply()
    }
}

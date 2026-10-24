package com.example.animalocal.models

import android.content.Context
import org.json.JSONObject
import java.io.File

enum class Role(val dir: String, val label: String) {
    DIFFUSION("diffusion", "Diffusion (DiT)"),
    TEXT("text_encoder", "Text encoder"),
    VAE("vae", "VAE"),
}

data class ModelEntry(
    val id: String, val role: Role, val label: String, val file: String,
    val urls: List<String>, val sha256: String?, val size: Long?, val imported: Boolean,
)

/**
 * Models are data: assets/models.json lists the known downloads; any other file dropped into a role
 * folder (via Import) shows up automatically. Adding a model never needs a code change.
 */
class ModelStore(private val ctx: Context) {
    private val root = File(ctx.filesDir, "models")
    private val sp = ctx.getSharedPreferences("models", Context.MODE_PRIVATE)
    val loraDir: File get() = File(ctx.filesDir, "loras").apply { mkdirs() }

    private val manifest: List<ModelEntry> by lazy {
        val txt = ctx.assets.open("models.json").bufferedReader().use { it.readText() }
        val arr = JSONObject(txt).getJSONArray("models")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val u = o.getJSONArray("urls")
            ModelEntry(
                id = o.getString("id"),
                role = Role.values().first { it.dir == o.getString("role") },
                label = o.getString("label"),
                file = o.getString("file"),
                urls = (0 until u.length()).map { u.getString(it) },
                sha256 = o.optString("sha256").ifEmpty { null },
                size = if (o.has("size")) o.getLong("size") else null,
                imported = false,
            )
        }
    }

    fun dir(role: Role): File = File(root, role.dir).apply { mkdirs() }
    fun file(e: ModelEntry): File = File(dir(e.role), e.file)
    fun present(e: ModelEntry): Boolean = file(e).exists()

    fun entries(role: Role): List<ModelEntry> {
        val known = manifest.filter { it.role == role }
        val names = known.map { it.file }.toSet()
        val extra = dir(role).listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".part") && it.name !in names }
            ?.sortedBy { it.name }
            ?.map { ModelEntry("local:${it.name}", role, it.name, it.name, emptyList(), null, it.length(), true) }
            ?: emptyList()
        return known + extra
    }

    fun selected(role: Role): String? = sp.getString(role.dir, null)
    fun select(role: Role, fileName: String) { sp.edit().putString(role.dir, fileName).apply() }

    /** Selected file if present, else the first model of that role that is on disk. */
    fun selectedFile(role: Role): File? {
        selected(role)?.let { n -> File(dir(role), n).takeIf { it.exists() }?.let { return it } }
        return entries(role).firstOrNull { present(it) }?.let { file(it) }
    }

    fun delete(e: ModelEntry) {
        file(e).delete()
        File(file(e).path + ".part").delete()
        if (selected(e.role) == e.file) sp.edit().remove(e.role.dir).apply()
    }
}

package com.example.animalocal

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.animalocal.data.AppSettings
import com.example.animalocal.data.LoraEntry
import com.example.animalocal.data.Prefs
import com.example.animalocal.engine.EngineCancelled
import com.example.animalocal.engine.EngineEvent
import com.example.animalocal.engine.GenerationRequest
import com.example.animalocal.engine.ImageEngine
import com.example.animalocal.engine.LoraRef
import com.example.animalocal.engine.RemoteEngine
import com.example.animalocal.models.Downloader
import com.example.animalocal.models.ModelEntry
import com.example.animalocal.models.ModelStore
import com.example.animalocal.models.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import kotlin.math.sqrt
import kotlin.random.Random

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx = app
    val prefs = Prefs(ctx)
    val store = ModelStore(ctx)
    private val engine: ImageEngine = RemoteEngine(ctx)

    var settings by mutableStateOf(prefs.loadSettings()); private set
    var loras by mutableStateOf(prefs.loadLoras()); private set
    var prompt by mutableStateOf(prefs.prompt)
    var busy by mutableStateOf(false); private set
    var status by mutableStateOf("Ready"); private set
    var progress by mutableStateOf(0f); private set
    var image by mutableStateOf<Bitmap?>(null); private set
    var currentFile by mutableStateOf<File?>(null); private set
    var gallery by mutableStateOf(listGallery()); private set
    var modelsVersion by mutableStateOf(0); private set
    var importing by mutableStateOf<String?>(null); private set
    val log = mutableStateListOf<String>()
    val downloads = mutableStateMapOf<String, Float>()
    val dlErrors = mutableStateMapOf<String, String>()
    private val dlJobs = HashMap<String, Job>()

    fun updatePrompt(s: String) { prompt = s; prefs.prompt = s }
    fun updateSettings(s: AppSettings) { settings = s; prefs.saveSettings(s) }

    fun addLog(t: String) {
        log.add(t)
        if (log.size > 400) log.subList(0, 150).clear()
    }

    // ---------------- generation ----------------
    fun generate(smoke: Boolean = false) {
        if (busy) return
        val diff = store.selectedFile(Role.DIFFUSION)
        val llm = store.selectedFile(Role.TEXT)
        val vae = store.selectedFile(Role.VAE)
        if (diff == null || llm == null || vae == null) {
            status = "Download or import a diffusion model, text encoder and VAE in Models first"
            return
        }
        val s = settings
        val req = GenerationRequest(
            diffusionPath = diff.path, llmPath = llm.path, vaePath = vae.path,
            prompt = if (smoke) "1girl, solo" else prompt,
            negative = s.negative, sampler = s.sampler,
            width = if (smoke) 256 else s.width, height = if (smoke) 256 else s.height,
            steps = if (smoke) 1 else s.steps, cfg = s.cfg,
            seed = if (smoke) 42 else if (s.seed < 0) Random.nextLong(0, 2_000_000_000L) else s.seed,
            vaeTiling = s.vaeTiling, flashAttn = s.flashAttn,
            threads = s.threads, affinityMask = s.affinityMask(), offloadMode = s.offloadMode, backend = s.backend,
            loras = if (smoke) emptyList()
            else loras.filter { it.enabled && File(it.path).exists() }.map { LoraRef(it.path, it.strength) },
        )
        busy = true; progress = 0f
        status = if (smoke) "Smoke test: starting engine…" else "Starting engine…"
        viewModelScope.launch {
            val t0 = SystemClock.elapsedRealtime()
            try {
                val f = engine.generate(req) { ev ->
                    when (ev) {
                        is EngineEvent.Progress -> {
                            progress = ev.step.toFloat() / ev.steps
                            status = "step ${ev.step}/${ev.steps} · %.1f s/step · RSS %d MB (peak %d)".format(ev.secs, ev.rssMb, ev.peakMb)
                        }
                        is EngineEvent.Log -> addLog(ev.text)
                    }
                }
                val secs = (SystemClock.elapsedRealtime() - t0) / 1000.0
                if (smoke) {
                    val bmp = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(f.path) }
                    val sd = bmp?.let { stddev(it) } ?: 0.0
                    val pass = bmp != null && sd > 3.0
                    addLog("SMOKE ${if (pass) "PASS" else "FAIL"}: ${bmp?.width}x${bmp?.height}, pixel stddev %.1f, %.1f s total".format(sd, secs))
                    status = "Smoke test ${if (pass) "passed" else "FAILED"} (%.1f s)".format(secs)
                    image = bmp
                } else {
                    val saved = withContext(Dispatchers.IO) { saveToGallery(f, req) }
                    image = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(saved.path) }
                    currentFile = saved
                    gallery = listGallery()
                    status = "Done in %.1f s · seed ${req.seed}".format(secs)
                }
            } catch (e: EngineCancelled) {
                status = "Cancelled"
            } catch (e: Exception) {
                status = "Error: ${e.message}"
                addLog("ERROR: ${e.message}")
            } finally {
                busy = false
            }
        }
    }

    fun cancel() { engine.cancel() }

    fun runSmokeTest() = generate(smoke = true)

    private fun stddev(b: Bitmap): Double {
        val w = b.width; val h = b.height
        var n = 0; var sum = 0.0; var sq = 0.0
        for (y in 0 until h step 4) for (x in 0 until w step 4) {
            val p = b.getPixel(x, y)
            val l = 0.3 * ((p shr 16) and 255) + 0.59 * ((p shr 8) and 255) + 0.11 * (p and 255)
            sum += l; sq += l * l; n++
        }
        if (n == 0) return 0.0
        val m = sum / n
        return sqrt((sq / n - m * m).coerceAtLeast(0.0))
    }

    // ---------------- gallery ----------------
    private fun galleryDir(): File = ctx.getExternalFilesDir("gallery") ?: File(ctx.filesDir, "gallery").apply { mkdirs() }

    fun listGallery(): List<File> =
        galleryDir().listFiles { f -> f.name.endsWith(".png") }?.sortedByDescending { it.lastModified() } ?: emptyList()

    private fun saveToGallery(src: File, req: GenerationRequest): File {
        val dest = File(galleryDir(), "anima_${System.currentTimeMillis()}.png")
        src.copyTo(dest, overwrite = true)
        src.delete()
        val meta = JSONObject()
            .put("schema", 1).put("prompt", req.prompt).put("negative", req.negative)
            .put("seed", req.seed).put("steps", req.steps).put("cfg", req.cfg.toDouble())
            .put("sampler", req.sampler).put("width", req.width).put("height", req.height)
            .put("diffusion", File(req.diffusionPath).name)
            .put("loras", req.loras.joinToString { "${File(it.path).name}:${it.strength}" })
        File(dest.path.removeSuffix(".png") + ".json").writeText(meta.toString(2))
        return dest
    }

    fun showFromGallery(f: File) {
        viewModelScope.launch {
            image = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(f.path) }
            currentFile = f
            val meta = File(f.path.removeSuffix(".png") + ".json")
            if (meta.exists()) try { updatePrompt(JSONObject(meta.readText()).optString("prompt", prompt)) } catch (_: Exception) { }
        }
    }

    fun saveToPictures(f: File) {
        viewModelScope.launch(Dispatchers.IO) {
            val v = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, f.name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AnimaLocal")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: return@launch
            ctx.contentResolver.openOutputStream(uri)?.use { o -> f.inputStream().use { it.copyTo(o) } }
            withContext(Dispatchers.Main) { status = "Saved to Pictures/AnimaLocal" }
        }
    }

    // ---------------- models ----------------
    fun select(role: Role, e: ModelEntry) { store.select(role, e.file); modelsVersion++ }

    fun download(e: ModelEntry) {
        if (downloads.containsKey(e.id)) return
        val dest = store.file(e)
        dest.parentFile?.mkdirs()
        downloads[e.id] = 0f
        dlErrors.remove(e.id)
        dlJobs[e.id] = viewModelScope.launch {
            try {
                Downloader.download(e.urls, dest, e.sha256) { done, total ->
                    if (total > 0) downloads[e.id] = done.toFloat() / total
                }
                if (store.selected(e.role) == null) store.select(e.role, e.file)
                addLog("downloaded ${e.file}")
            } catch (x: kotlinx.coroutines.CancellationException) {
                addLog("download cancelled: ${e.file} (partial file kept, will resume)")
            } catch (x: Exception) {
                dlErrors[e.id] = x.message ?: x.toString()
                addLog("download failed: ${e.file}: ${x.message}")
            } finally {
                downloads.remove(e.id); dlJobs.remove(e.id); modelsVersion++
            }
        }
    }

    fun cancelDownload(e: ModelEntry) { dlJobs[e.id]?.cancel() }

    fun deleteModel(e: ModelEntry) { store.delete(e); modelsVersion++ }

    private fun displayName(uri: Uri): String? =
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    private fun copyIn(uri: Uri, dest: File) {
        ctx.contentResolver.openInputStream(uri)?.use { i -> dest.outputStream().use { o -> i.copyTo(o, 1 shl 20) } }
    }

    fun importModel(uri: Uri, role: Role) {
        viewModelScope.launch(Dispatchers.IO) {
            val name = displayName(uri) ?: "imported_${System.currentTimeMillis()}"
            withContext(Dispatchers.Main) { importing = "Importing $name…" }
            try {
                copyIn(uri, File(store.dir(role), name))
                store.select(role, name)
            } catch (e: Exception) { addLog("import failed: ${e.message}") }
            withContext(Dispatchers.Main) { importing = null; modelsVersion++ }
        }
    }

    // ---------------- LoRA ----------------
    fun importLora(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val name = displayName(uri) ?: "lora_${System.currentTimeMillis()}.safetensors"
            withContext(Dispatchers.Main) { importing = "Importing $name…" }
            try {
                val dest = File(store.loraDir, name)
                copyIn(uri, dest)
                withContext(Dispatchers.Main) {
                    applyLoras(loras.filter { it.path != dest.path } + LoraEntry(name, dest.path))
                }
            } catch (e: Exception) { addLog("LoRA import failed: ${e.message}") }
            withContext(Dispatchers.Main) { importing = null }
        }
    }

    fun applyLoras(list: List<LoraEntry>) { loras = list; prefs.saveLoras(list) }
    fun updateLora(i: Int, l: LoraEntry) = applyLoras(loras.toMutableList().also { it[i] = l })
    fun removeLora(i: Int) {
        File(loras[i].path).delete()
        applyLoras(loras.toMutableList().also { it.removeAt(i) })
    }
}

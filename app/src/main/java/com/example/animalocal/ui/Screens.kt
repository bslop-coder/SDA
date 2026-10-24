@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.animalocal.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.animalocal.BuildConfig
import com.example.animalocal.MainViewModel
import com.example.animalocal.models.ModelEntry
import com.example.animalocal.models.ModelInspector
import com.example.animalocal.models.Role
import com.example.animalocal.models.fmtBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

enum class Screen { Main, Settings, Models }

@Composable
fun App(vm: MainViewModel) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        Surface(Modifier.fillMaxSize()) {
            var screen by remember { mutableStateOf(Screen.Main) }
            BackHandler(screen != Screen.Main) { screen = Screen.Main }
            Column(Modifier.systemBarsPadding()) {
                when (screen) {
                    Screen.Main -> MainScreen(vm) { screen = it }
                    Screen.Settings -> SettingsScreen(vm) { screen = Screen.Main }
                    Screen.Models -> ModelsScreen(vm) { screen = Screen.Main }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- helpers

@Composable
private fun Header(title: String, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.weight(1f))
        content()
    }
}

@Composable
private fun <T> Chips(options: List<T>, selected: T, label: (T) -> String = { it.toString() }, onSelect: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o -> FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(label(o)) }) }
    }
}

@Composable
private fun SliderRow(title: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int = 0, fmt: String, onChange: (Float) -> Unit) {
    Text("$title: $fmt", style = MaterialTheme.typography.bodyMedium)
    Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps)
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun LogView(vm: MainViewModel) {
    Text(
        vm.log.takeLast(60).joinToString("\n"),
        fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 12.sp,
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
    )
}

@Composable
private fun Thumb(f: File, onClick: () -> Unit) {
    val bmp by produceState<Bitmap?>(null, f) {
        value = withContext(Dispatchers.IO) {
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 4 })
        }
    }
    bmp?.let {
        Image(it.asImageBitmap(), null, Modifier.size(88.dp).clip(MaterialTheme.shapes.small).clickable(onClick = onClick), contentScale = ContentScale.Crop)
    }
}

// ---------------------------------------------------------------- main

@Composable
fun MainScreen(vm: MainViewModel, go: (Screen) -> Unit) {
    var showLog by remember { mutableStateOf(false) }
    Header("Anima Local · " + if (BuildConfig.VULKAN) "Vulkan" else "CPU") {
        TextButton(onClick = { go(Screen.Models) }) { Text("Models") }
        TextButton(onClick = { go(Screen.Settings) }) { Text("Settings") }
    }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = vm.prompt, onValueChange = vm::updatePrompt, label = { Text("Prompt") },
            minLines = 3, modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.generate() }, enabled = !vm.busy) { Text("Generate") }
            if (vm.busy) OutlinedButton(onClick = vm::cancel) { Text("Cancel") }
        }
        Text(vm.status, style = MaterialTheme.typography.bodySmall)
        if (vm.busy) LinearProgressIndicator(progress = { vm.progress }, modifier = Modifier.fillMaxWidth())
        vm.image?.let { bmp ->
            Image(bmp.asImageBitmap(), null, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
            vm.currentFile?.let { f -> TextButton(onClick = { vm.saveToPictures(f) }) { Text("Save to Pictures") } }
        }
        if (vm.gallery.isNotEmpty()) {
            Text("Gallery", style = MaterialTheme.typography.titleSmall)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(vm.gallery.take(40), key = { it.path }) { f -> Thumb(f) { vm.showFromGallery(f) } }
            }
        }
        TextButton(onClick = { showLog = !showLog }) { Text(if (showLog) "Hide log" else "Show log") }
        if (showLog) LogView(vm)
    }
}

// ---------------------------------------------------------------- settings

@Composable
fun SettingsScreen(vm: MainViewModel, back: () -> Unit) {
    val s = vm.settings
    val loraPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importLora) }
    Header("Settings") { TextButton(onClick = back) { Text("Done") } }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SliderRow("Steps", s.steps.toFloat(), 1f..50f, 48, "${s.steps}") { vm.updateSettings(s.copy(steps = it.roundToInt())) }
        SliderRow("CFG", s.cfg, 1f..10f, 0, "%.1f".format(s.cfg)) { vm.updateSettings(s.copy(cfg = (it * 10).roundToInt() / 10f)) }

        Text("Sampler")
        Chips(listOf("euler", "euler_a", "heun", "dpm++2m", "dpm++2s_a", "lcm"), s.sampler) { vm.updateSettings(s.copy(sampler = it)) }
        Text("Width")
        Chips(listOf(256, 384, 512, 640, 768, 1024), s.width) { vm.updateSettings(s.copy(width = it)) }
        Text("Height")
        Chips(listOf(256, 384, 512, 640, 768, 1024), s.height) { vm.updateSettings(s.copy(height = it)) }

        OutlinedTextField(
            value = if (s.seed < 0) "" else s.seed.toString(),
            onValueChange = { vm.updateSettings(s.copy(seed = it.filter(Char::isDigit).toLongOrNull() ?: -1)) },
            label = { Text("Seed (blank = random)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = s.negative, onValueChange = { vm.updateSettings(s.copy(negative = it)) },
            label = { Text("Negative prompt (ignored at CFG 1.0)") }, modifier = Modifier.fillMaxWidth(),
        )

        SwitchRow("VAE tiling", s.vaeTiling) { vm.updateSettings(s.copy(vaeTiling = it)) }
        SwitchRow("Flash attention", s.flashAttn) { vm.updateSettings(s.copy(flashAttn = it)) }

        Text("Offload")
        Chips(listOf(0, 1, 2), s.offloadMode, { listOf("Off", "JIT: all stages", "JIT: text encoder only")[it] }) { vm.updateSettings(s.copy(offloadMode = it)) }

        SliderRow("CPU threads", s.threads.toFloat(), 1f..8f, 6, "${s.threads}") { vm.updateSettings(s.copy(threads = it.roundToInt())) }
        OutlinedTextField(
            value = s.affinityHex, onValueChange = { vm.updateSettings(s.copy(affinityHex = it.filter { c -> c.isLetterOrDigit() }.take(4))) },
            label = { Text("CPU affinity mask, hex (C0 = cpu6+7, empty/0 = none)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = s.backend, onValueChange = { vm.updateSettings(s.copy(backend = it.trim())) },
            label = { Text("Backend override (blank = auto; cpu, gpu, diffusion=gpu,te=cpu,vae=cpu)") }, modifier = Modifier.fillMaxWidth(),
        )

        Text("LoRA", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 10.dp))
        vm.loras.forEachIndexed { i, l ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp)) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Switch(checked = l.enabled, onCheckedChange = { vm.updateLora(i, l.copy(enabled = it)) })
                        Text(l.name, Modifier.weight(1f).padding(start = 10.dp), maxLines = 2)
                        TextButton(onClick = { vm.removeLora(i) }) { Text("Delete") }
                    }
                    SliderRow("Strength", l.strength, -1f..2f, 0, "%.2f".format(l.strength)) {
                        vm.updateLora(i, l.copy(strength = (it * 20).roundToInt() / 20f))
                    }
                }
            }
        }
        OutlinedButton(onClick = { loraPicker.launch(arrayOf("*/*")) }) { Text("Import LoRA (.safetensors)") }
        vm.importing?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        Text("Tools", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 10.dp))
        Button(onClick = vm::runSmokeTest, enabled = !vm.busy) { Text("Smoke test (256², 1 step, seed 42)") }
        Text(vm.status, style = MaterialTheme.typography.bodySmall)
        LogView(vm)
    }
}

// ---------------------------------------------------------------- models

@Composable
fun ModelsScreen(vm: MainViewModel, back: () -> Unit) {
    val version = vm.modelsVersion // subscribe: recompose when files/selection change
    var pendingRole by remember { mutableStateOf<Role?>(null) }
    val expanded = remember { mutableStateListOf<String>() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val r = pendingRole
        if (uri != null && r != null) vm.importModel(uri, r)
    }
    Header("Models") { TextButton(onClick = back) { Text("Done") } }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        vm.importing?.let { Text(it) }
        Role.values().forEach { role ->
            Text(role.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            val selFile = vm.store.selectedFile(role)?.name
            vm.store.entries(role).forEach { e ->
                ModelCard(vm, e, selected = e.file == selFile && version >= 0, expanded = e.id in expanded) {
                    if (e.id in expanded) expanded.remove(e.id) else expanded.add(e.id)
                }
            }
            OutlinedButton(onClick = { pendingRole = role; picker.launch(arrayOf("*/*")) }) { Text("Import ${role.label} file…") }
        }
        Text("Keep the app open while downloading; interrupted downloads resume.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ModelCard(vm: MainViewModel, e: ModelEntry, selected: Boolean, expanded: Boolean, toggleInfo: () -> Unit) {
    val present = vm.store.present(e)
    val dl = vm.downloads[e.id]
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                RadioButton(selected = selected, onClick = { if (present) vm.select(e.role, e) }, enabled = present)
                Column(Modifier.weight(1f)) {
                    Text(e.label)
                    Text(
                        (e.size?.let { fmtBytes(it) } ?: "") + " · " + if (present) "on device" else "not downloaded",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (dl != null) {
                LinearProgressIndicator(progress = { dl }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                TextButton(onClick = { vm.cancelDownload(e) }) { Text("Cancel download") }
            } else {
                Row {
                    if (!present && e.urls.isNotEmpty()) TextButton(onClick = { vm.download(e) }) { Text("Download") }
                    if (present) {
                        TextButton(onClick = toggleInfo) { Text(if (expanded) "Hide info" else "Info") }
                        TextButton(onClick = { vm.deleteModel(e) }) { Text("Delete") }
                    }
                }
            }
            vm.dlErrors[e.id]?.let { Text("Download failed: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (expanded && present) {
                val info by produceState("reading header…", e.file) {
                    value = withContext(Dispatchers.IO) { ModelInspector.inspect(vm.store.file(e)).describe() }
                }
                Text(info, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        }
    }
}

package com.weaudio.pro.studio.drum

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.weaudio.pro.audio.keyboard.KeyMap
import com.weaudio.pro.audio.sampler.DrumInstrument
import com.weaudio.pro.ui.components.DawTextButton
import com.weaudio.pro.ui.theme.DawColors
import com.weaudio.pro.ui.theme.DawType
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun testTagOf(i: DrumInstrument) = "pad_${i.name.lowercase()}"

private fun statusText(s: InstrumentState) = when {
    s.muted -> "MUTED"
    s.status == SampleStatus.LOADING -> "LOADING"
    s.status == SampleStatus.ERROR -> "ERROR"
    s.status == SampleStatus.EMPTY -> "NO SAMPLE"
    else -> ""
}

/** One pad. Audio fires on the pointer-down event itself; the colour change is cosmetic only. */
@Composable
fun DrumPad(
    state: InstrumentState, keyLabel: String?, pressed: Boolean, selected: Boolean,
    onPress: () -> Unit, onRelease: () -> Unit, modifier: Modifier = Modifier,
) {
    val press by rememberUpdatedState(onPress)
    val rel by rememberUpdatedState(onRelease)
    val usable = state.status == SampleStatus.LOADED
    val border = when {
        state.status == SampleStatus.ERROR -> DawColors.Error
        selected -> DawColors.Accent
        else -> DawColors.Separator
    }
    val label = statusText(state)
    Box(
        modifier
            .background(if (pressed) DawColors.AccentDim else DawColors.Raised)
            .border(1.dp, border)
            .testTag(testTagOf(state.instrument))
            .semantics { contentDescription = "${state.instrument.label} pad ${label}".trim() }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    press()                      // immediate: same frame as the touch event
                    waitForUpOrCancellation()
                    rel()
                }
            },
    ) {
        Text(keyLabel ?: "–", style = DawType.Label, color = DawColors.TextSecondary, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp))
        Column(Modifier.align(Alignment.BottomStart).padding(8.dp)) {
            Text(state.instrument.label.uppercase(), style = DawType.Label,
                color = if (usable && !state.muted) DawColors.TextPrimary else DawColors.TextDisabled, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (label.isNotEmpty()) Text(label, style = DawType.Label,
                color = if (state.status == SampleStatus.ERROR) DawColors.Error else DawColors.Warning)
        }
    }
}


/** [columns] x rows grid of the 12 pads in [DrumInstrument.PAD_ORDER]. Each pad is its own pointer target (multi-touch). */
@Composable
fun PadGrid(ui: DrumUiState, pressed: Set<DrumInstrument>, columns: Int, vm: DrumViewModel, modifier: Modifier = Modifier) {
    val rows = DrumInstrument.PAD_ORDER.chunked(columns)
    Column(modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { row ->
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { inst ->
                    DrumPad(
                        ui[inst], ui.keyMap.keyFor(inst)?.let { KeyMap.label(it) }, inst in pressed, inst == ui.selected,
                        onPress = { vm.select(inst); vm.trigger(inst) }, onRelease = { vm.release(inst) },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        }
    }
}

@Composable
fun sliderColors() = SliderDefaults.colors(thumbColor = DawColors.Accent, activeTrackColor = DawColors.Accent, inactiveTrackColor = DawColors.Separator)

@Composable
fun ParamSlider(label: String, valueText: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit, tag: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label.uppercase(), style = DawType.Label, color = DawColors.TextSecondary)
            Text(valueText, style = DawType.Value, color = DawColors.TextPrimary)
        }
        Slider(value, onChange, valueRange = range, colors = sliderColors(), modifier = Modifier.testTag(tag).height(28.dp))
    }
}

/** Volume / pitch / mute and sample actions for one instrument. */
@Composable
fun InstrumentInspector(
    ui: DrumUiState, vm: DrumViewModel, onReplace: (DrumInstrument) -> Unit, modifier: Modifier = Modifier, showSampleActions: Boolean = true,
) {
    val s = ui[ui.selected]
    Column(modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.instrument.label.uppercase(), style = DawType.Label, color = DawColors.TextPrimary, modifier = Modifier.testTag("inspector_title"))
                Text(
                    when (s.status) {
                        SampleStatus.LOADED -> s.sampleName.ifBlank { "Sample loaded" } + if (s.isUserSample) " (user)" else ""
                        SampleStatus.LOADING -> "Loading…"
                        SampleStatus.ERROR -> s.error ?: "Sample failed to load"
                        SampleStatus.EMPTY -> "No sample"
                    },
                    style = DawType.Label, color = if (s.status == SampleStatus.ERROR) DawColors.Error else DawColors.TextSecondary,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            DawTextButton(if (s.muted) "Unmute" else "Mute", { vm.setMuted(s.instrument, !s.muted) }, accent = s.muted, modifier = Modifier.testTag("mute_button"))
        }
        ParamSlider("Volume", String.format(Locale.US, "%d%%", (s.volume * 100).toInt()), s.volume, 0f..1.5f, { vm.setVolume(s.instrument, it) }, "volume_slider")
        ParamSlider("Pitch", String.format(Locale.US, "%+.1f st", s.pitchSemitones), s.pitchSemitones, -12f..12f, { vm.setPitch(s.instrument, (Math.round(it * 2) / 2f)) }, "pitch_slider")
        if (s.error != null && s.status == SampleStatus.LOADED) Text(s.error, style = DawType.Label, color = DawColors.Error)
        if (showSampleActions) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DawTextButton("Replace…", { onReplace(s.instrument) }, modifier = Modifier.weight(1f).testTag("replace_sample"))
            DawTextButton("Restore", { vm.restoreOriginal(s.instrument) }, enabled = s.isUserSample || s.status == SampleStatus.ERROR, modifier = Modifier.weight(1f).testTag("restore_sample"))
        }
    }
}

@Composable
fun KitSelector(ui: DrumUiState, vm: DrumViewModel, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ui.kits.forEach { kit ->
            val sel = kit.id == ui.kitId
            Row(
                Modifier.fillMaxWidth().background(if (sel) DawColors.AccentDim else DawColors.Raised).border(1.dp, if (sel) DawColors.Accent else DawColors.Separator)
                    .clickable { if (!sel) vm.loadKit(kit.id) }.padding(horizontal = 10.dp, vertical = 10.dp).testTag("kit_${kit.id}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(kit.name.uppercase(), style = DawType.Label, color = if (sel) DawColors.TextPrimary else DawColors.TextSecondary, modifier = Modifier.weight(1f))
                if (sel) Text(if (ui.kitLoading) "LOADING" else "ACTIVE", style = DawType.Label, color = DawColors.Accent)
            }
        }
        if (ui.kits.isEmpty()) Text("No kits available", style = DawType.Label, color = DawColors.Error)
    }
}

/** Selectable list of all instruments with load status dots. */
@Composable
fun InstrumentList(ui: DrumUiState, vm: DrumViewModel, modifier: Modifier = Modifier) {
    Column(modifier) {
        DrumInstrument.entries.forEach { inst ->
            val s = ui[inst]
            val sel = inst == ui.selected
            Row(
                Modifier.fillMaxWidth().height(34.dp).background(if (sel) DawColors.Raised else DawColors.Panel)
                    .clickable { vm.select(inst) }.padding(horizontal = 10.dp).testTag("list_${inst.name.lowercase()}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(6.dp).background(when (s.status) {
                    SampleStatus.LOADED -> if (s.muted) DawColors.Warning else DawColors.Accent
                    SampleStatus.LOADING -> DawColors.Warning
                    SampleStatus.ERROR -> DawColors.Error
                    SampleStatus.EMPTY -> DawColors.TextDisabled
                }))
                Spacer(Modifier.width(8.dp))
                Text(inst.label, color = if (sel) DawColors.TextPrimary else DawColors.TextSecondary, style = androidx.compose.material3.MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1)
                ui.keyMap.keyFor(inst)?.let { Text(KeyMap.label(it), style = DawType.Label, color = DawColors.TextDisabled) }
            }
        }
    }
}

/** Real values only: loaded count from the sampler state, voices / xruns / buffer from native diagnostics. */
@Composable
fun StatusStrip(ui: DrumUiState, voices: String, xruns: String, buffer: String, engine: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().background(DawColors.Panel).padding(horizontal = 10.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        val loading = if (ui.kitLoading) "LOADING ${ui.loadedCount}/${DrumInstrument.entries.size}" else "SAMPLES ${ui.loadedCount}/${DrumInstrument.entries.size}"
        Text(loading, style = DawType.Label, color = if (ui.loadedCount == DrumInstrument.entries.size) DawColors.TextSecondary else DawColors.Warning, modifier = Modifier.testTag("samples_status"))
        Text("VOICES $voices", style = DawType.Label, color = DawColors.TextSecondary)
        Text("XRUNS $xruns", style = DawType.Label, color = DawColors.TextSecondary)
        Text("BUF $buffer", style = DawType.Label, color = DawColors.TextSecondary)
        Text(if (ui.keyboardConnected) "KEYBOARD ON" else "NO KEYBOARD", style = DawType.Label, color = if (ui.keyboardConnected) DawColors.Accent else DawColors.TextDisabled, modifier = Modifier.testTag("keyboard_status"))
        Spacer(Modifier.weight(1f))
        Text(engine, style = DawType.Label, color = DawColors.TextSecondary)
    }
}

/** Returns a function that opens the Storage Access Framework picker for [DrumInstrument]'s replacement WAV. */
@Composable
fun rememberSampleImporter(vm: DrumViewModel): (DrumInstrument) -> Unit {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var target by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf<String?>(null) }
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        val inst = target?.let { n -> DrumInstrument.entries.firstOrNull { it.name == n } }
        if (uri != null && inst != null) scope.launch {
            val r = withContext(Dispatchers.IO) { com.weaudio.pro.samples.UserSampleReader.read(context, uri) }
            val bytes = r.bytes
            if (bytes != null) vm.replaceSample(inst, r.name, bytes) else vm.reportImportError(inst, r.error ?: "Could not read the file")
        }
    }
    return { inst -> target = inst.name; launcher.launch(arrayOf("audio/*", "application/octet-stream")) }
}

package com.weaudio.pro.studio.drum

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.weaudio.pro.audio.keyboard.KeyMap
import com.weaudio.pro.audio.sampler.DrumInstrument
import com.weaudio.pro.ui.components.DawTextButton
import com.weaudio.pro.ui.components.HairlineDivider
import com.weaudio.pro.ui.components.SectionHeader
import com.weaudio.pro.ui.theme.DawColors
import com.weaudio.pro.ui.theme.DawType
import java.util.Locale

/** Kit selection, per-instrument sample management, and the editable keyboard mapping. */
@Composable
fun SoundsScreen(drums: DrumViewModel) {
    val ui by drums.state.collectAsStateWithLifecycle()
    val importer = rememberSampleImporter(drums)
    // Leaving the screen mid-capture must not leave the next key press captured.
    DisposableEffect(Unit) { onDispose { drums.cancelKeyCapture() } }
    Column(Modifier.fillMaxSize().background(DawColors.Background).verticalScroll(rememberScrollState())) {
        SectionHeader("Drum kit")
        KitSelector(ui, drums, Modifier.padding(10.dp))

        SectionHeader("Samples")
        DrumInstrument.entries.forEach { inst ->
            val s = ui[inst]
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).testTag("sample_row_${inst.name.lowercase()}")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(inst.label, color = DawColors.TextPrimary, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
                        Text(
                            when (s.status) {
                                SampleStatus.LOADED -> s.sampleName + if (s.isUserSample) " · user sample" else " · kit sample"
                                SampleStatus.LOADING -> "Loading…"
                                SampleStatus.ERROR -> "Error"
                                SampleStatus.EMPTY -> "No sample"
                            }, style = DawType.Label, color = DawColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    DawTextButton("Replace…", { importer(inst) })
                    Spacer(Modifier.width(6.dp))
                    DawTextButton("Restore", { drums.restoreOriginal(inst) }, enabled = s.isUserSample || s.status == SampleStatus.ERROR)
                }
                s.error?.let { Text(it, style = DawType.Label, color = DawColors.Error, modifier = Modifier.padding(top = 2.dp)) }
            }
            HairlineDivider()
        }
        Text("Accepted: WAV, PCM 8/16/24/32-bit or float, mono/stereo, 8–192 kHz, up to 16 MB / 12 s. Samples are converted to the device rate when loaded.",
            style = DawType.Label, color = DawColors.TextDisabled, modifier = Modifier.padding(12.dp))

        SectionHeader("Physical keyboard")
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (ui.keyboardConnected) "Keyboard detected. Keys trigger drums while the Studio is open." else "No physical keyboard detected (USB or Bluetooth).",
                style = DawType.Label, color = if (ui.keyboardConnected) DawColors.Accent else DawColors.TextSecondary)
            ParamSlider("Fixed velocity", String.format(Locale.US, "%d%%", (ui.velocity * 100).toInt()), ui.velocity, 0.1f..1f, drums::setVelocity, "velocity_slider")
            Text("Typing keyboards and touch are not pressure-sensitive, so every hit uses this fixed velocity.", style = DawType.Label, color = DawColors.TextDisabled)
        }
        DrumInstrument.entries.forEach { inst ->
            val capturing = ui.captureTarget == inst
            val key = ui.keyMap.keyFor(inst)
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(inst.label, color = DawColors.TextPrimary, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(if (capturing) "PRESS A KEY…" else key?.let { KeyMap.label(it) } ?: "UNASSIGNED", style = DawType.Value,
                    color = if (capturing) DawColors.Accent else DawColors.TextSecondary, modifier = Modifier.testTag("key_${inst.name.lowercase()}"))
                Spacer(Modifier.width(8.dp))
                DawTextButton(if (capturing) "Cancel" else "Remap", { if (capturing) drums.cancelKeyCapture() else drums.beginKeyCapture(inst) },
                    accent = capturing, modifier = Modifier.testTag("remap_${inst.name.lowercase()}"))
                Spacer(Modifier.width(6.dp))
                DawTextButton("Clear", { drums.clearKey(inst) }, enabled = key != null)
            }
        }
        Row(Modifier.padding(12.dp)) { DawTextButton("Reset to defaults", drums::resetKeyMap, modifier = Modifier.testTag("reset_keys")) }
        Text("Capture accepts a physical keyboard key (Esc cancels). A key can drive one instrument; assigning it elsewhere moves it.",
            style = DawType.Label, color = DawColors.TextDisabled, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        Spacer(Modifier.height(24.dp))
    }
}

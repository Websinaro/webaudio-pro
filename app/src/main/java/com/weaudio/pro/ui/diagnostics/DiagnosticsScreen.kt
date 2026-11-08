package com.weaudio.pro.ui.diagnostics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.weaudio.pro.audio.AudioViewModel
import com.weaudio.pro.audio.EngineState
import com.weaudio.pro.ui.components.*
import com.weaudio.pro.ui.theme.DawColors
import com.weaudio.pro.ui.theme.DawType
import java.util.Locale
import kotlin.math.pow
import kotlin.math.log2

/** Tone frequency slider is logarithmic (musical): 55 Hz .. 3520 Hz over 6 octaves. */
private fun posToHz(p: Float) = 55f * 2f.pow(p * 6f)
private fun hzToPos(hz: Float) = (log2(hz / 55f) / 6f).coerceIn(0f, 1f)

@Composable
fun DiagnosticsScreen(audio: AudioViewModel) {
    val s by audio.state.collectAsStateWithLifecycle()
    val d = s.diagnostics
    val engine = s.engine
    val colors = SliderDefaults.colors(thumbColor = DawColors.Accent, activeTrackColor = DawColors.Accent, inactiveTrackColor = DawColors.Separator)
    Column(Modifier.fillMaxSize().background(DawColors.Background).verticalScroll(rememberScrollState())) {
        SectionHeader("Engine")
        KeyValueRow("Status", engineStatusLabel(engine), valueColor = engineStatusColor(engine), modifier = Modifier.testTag("diag_status"))
        s.error?.let { KeyValueRow("Error", it, valueColor = DawColors.Error) }
        KeyValueRow("Last native error", d["lastError"])
        KeyValueRow("Audio API", d["api"])
        KeyValueRow("Sample rate", d.withUnit("sampleRate", "Hz"))
        KeyValueRow("Output channels", d["channels"])
        KeyValueRow("Audio format", d["format"])
        KeyValueRow("Performance mode", d["performanceMode"])
        KeyValueRow("Sharing mode", d["sharingMode"])
        KeyValueRow("Stream state", d["streamState"])
        KeyValueRow("Config fallback", d["fallback"])
        KeyValueRow("Stream reconnects", d["reconnects"])

        SectionHeader("Buffer")
        KeyValueRow("Frames per burst", d["framesPerBurst"])
        KeyValueRow("Buffer size (frames)", d["bufferSize"])
        KeyValueRow("Buffer capacity (frames)", d["bufferCapacity"])
        KeyValueRow("Buffer duration", d.bufferMillis())
        KeyValueRow("Underruns (xruns)", d["xRuns"])

        SectionHeader("Device")
        KeyValueRow("Stream device id", d["deviceId"])
        s.deviceInfo.forEach { (k, v) -> KeyValueRow(k, v) }

        SectionHeader("Test tone")
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Enabled", color = DawColors.TextSecondary, modifier = Modifier.weight(1f))
            Switch(
                checked = s.toneEnabled, onCheckedChange = audio::setToneEnabled, modifier = Modifier.testTag("tone_switch"),
                colors = SwitchDefaults.colors(checkedTrackColor = DawColors.Accent, checkedThumbColor = DawColors.Panel,
                    uncheckedTrackColor = DawColors.Raised, uncheckedThumbColor = DawColors.TextSecondary),
            )
        }
        LabeledSlider("Frequency", String.format(Locale.US, "%.1f Hz", s.toneFrequency), hzToPos(s.toneFrequency),
            { audio.setToneFrequency(posToHz(it)) }, colors, Modifier.testTag("tone_frequency"))
        LabeledSlider("Master volume", String.format(Locale.US, "%d%%", (s.masterVolume * 100).toInt()), s.masterVolume,
            audio::setMasterVolume, colors)
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DawTextButton("Start", audio::play, Modifier.weight(1f).testTag("diag_start"),
                enabled = engine != EngineState.RUNNING && engine != EngineState.STARTING, accent = true)
            DawTextButton("Stop", audio::stop, Modifier.weight(1f).testTag("diag_stop"),
                enabled = engine == EngineState.RUNNING || engine == EngineState.PAUSED || engine == EngineState.STARTING)
            DawTextButton("Reinitialize", audio::reinitialize, Modifier.weight(1.3f).testTag("diag_reinit"))
        }
        Text("Tone is generated natively in C++ and sent through the Oboe stream (-12 dBFS, soft-limited).",
            style = DawType.Label, color = DawColors.TextDisabled, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
    }
}

@Composable
private fun LabeledSlider(
    label: String, valueText: String, position: Float, onChange: (Float) -> Unit,
    colors: androidx.compose.material3.SliderColors, modifier: Modifier = Modifier,
) {
    Column(Modifier.padding(horizontal = 12.dp)) {
        Row {
            Text(label, color = DawColors.TextSecondary, modifier = Modifier.weight(1f))
            Text(valueText, style = DawType.Value, color = DawColors.TextPrimary)
        }
        Slider(value = position, onValueChange = onChange, colors = colors, modifier = modifier)
    }
}

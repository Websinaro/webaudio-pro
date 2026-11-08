package com.weaudio.pro.arrangement.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.weaudio.pro.arrangement.*
import com.weaudio.pro.project.TimeSignature
import com.weaudio.pro.ui.components.*
import com.weaudio.pro.ui.theme.DawColors
import com.weaudio.pro.ui.theme.DawType

/** Set by StudioScreen: while a text field is open the physical-keyboard drum mapping is switched off. */
val LocalKeyboardGate = compositionLocalOf<(Boolean) -> Unit> { {} }

@Composable
fun ArrangementTransportBar(vm: ArrangementViewModel, ui: ArrangementUiState, compact: Boolean, modifier: Modifier = Modifier) {
    val t by vm.transport.collectAsState()
    val running = t.mode == TransportMode.PLAYING || t.mode == TransportMode.AUDIO_PAUSED || t.mode == TransportMode.PREVIEW
    val buttons: @Composable () -> Unit = {
        TransportButton(TransportIcon.Restart, "Return to start", active = false, enabled = true, onClick = vm::returnToStart)
        TransportButton(TransportIcon.Play, "Play", active = t.mode == TransportMode.PLAYING, enabled = t.mode != TransportMode.PLAYING, onClick = vm::play)
        TransportButton(TransportIcon.Pause, "Pause", active = t.mode == TransportMode.PAUSED, enabled = running, onClick = vm::pause)
        TransportButton(TransportIcon.Stop, "Stop", active = false, enabled = running || t.mode == TransportMode.PAUSED || t.positionTicks > 0, onClick = vm::stop)
    }
    val position: @Composable (Modifier) -> Unit = { m ->
        val (label, color) = when (t.mode) {
            TransportMode.AUDIO_PAUSED -> "AUDIO PAUSED" to DawColors.Warning
            TransportMode.PREVIEW -> "PREVIEW" to DawColors.Accent
            else -> "POSITION" to DawColors.TextSecondary
        }
        LcdDisplay(label, t.label, m.testTag("arr_position"), valueColor = if (t.mode == TransportMode.AUDIO_PAUSED) DawColors.Warning else DawColors.TextPrimary)
    }
    val settings: @Composable () -> Unit = {
        TempoControl(vm, ui.project.tempoBpm)
        SignatureMenu(vm, ui.project.timeSignature)
        QuantizeMenu(vm, ui.project.playback.quantize)
        DawTextButton("Loop", vm::toggleLoop, accent = ui.project.playback.loop.enabled, modifier = Modifier.testTag("loop_toggle"))
        DawTextButton("Undo", vm::undo, enabled = ui.canUndo, modifier = Modifier.testTag("undo"))
        DawTextButton("Redo", vm::redo, enabled = ui.canRedo, modifier = Modifier.testTag("redo"))
        DawTextButton("Zoom −", { vm.zoomBy(0.8f) }, modifier = Modifier.testTag("zoom_out"))
        DawTextButton("Zoom +", { vm.zoomBy(1.25f) }, modifier = Modifier.testTag("zoom_in"))
    }
    if (compact) {   // landscape: one row
        Row(modifier.fillMaxWidth().background(DawColors.Panel).horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            buttons(); position(Modifier.width(112.dp)); settings()
        }
    } else {         // portrait: transport + position on top, settings scroll below
        Column(modifier.fillMaxWidth().background(DawColors.Panel).padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                buttons(); Spacer(Modifier.weight(1f)); position(Modifier.width(118.dp))
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { settings() }
        }
    }
}

@Composable
private fun TempoControl(vm: ArrangementViewModel, bpm: Double) {
    var dialog by rememberSaveable { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        DawTextButton("−", { vm.setTempo(bpm - 1) }, modifier = Modifier.testTag("tempo_down"))
        LcdDisplay("Tempo", "%.1f".format(java.util.Locale.US, bpm), Modifier.width(78.dp).clickable { dialog = true }.testTag("tempo_value"))
        DawTextButton("+", { vm.setTempo(bpm + 1) }, modifier = Modifier.testTag("tempo_up"))
    }
    if (dialog) TextEntryDialog("Tempo (BPM, ${MIN_TEMPO.toInt()}–${MAX_TEMPO.toInt()})", "%.1f".format(java.util.Locale.US, bpm), numeric = true,
        onDismiss = { dialog = false }, onConfirm = { s -> s.replace(',', '.').toDoubleOrNull()?.let(vm::setTempo); dialog = false })
}

@Composable
private fun SignatureMenu(vm: ArrangementViewModel, sig: TimeSignature) {
    var open by remember { mutableStateOf(false) }
    Box {
        LcdDisplay("Time sig", sig.toString(), Modifier.width(76.dp).clickable { open = true }.testTag("sig_value"))
        DropdownMenu(open, { open = false }, Modifier.background(DawColors.Raised)) {
            SUPPORTED_SIGNATURES.forEach { s -> DropdownMenuItem(text = { Text(s.toString()) }, onClick = { vm.setTimeSignature(s); open = false }) }
        }
    }
}

@Composable
private fun QuantizeMenu(vm: ArrangementViewModel, q: Quantize) {
    var open by remember { mutableStateOf(false) }
    Box {
        LcdDisplay("Quantize", q.label, Modifier.width(80.dp).clickable { open = true }.testTag("quantize_value"))
        DropdownMenu(open, { open = false }, Modifier.background(DawColors.Raised)) {
            Quantize.entries.forEach { e -> DropdownMenuItem(text = { Text(e.label) }, onClick = { vm.setQuantize(e); open = false }) }
        }
    }
}

@Composable
fun TextEntryDialog(title: String, initial: String, numeric: Boolean = false, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val gate = LocalKeyboardGate.current
    DisposableEffect(Unit) { gate(true); onDispose { gate(false) } }
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = DawType.Label, color = DawColors.TextSecondary) },
        text = {
            OutlinedTextField(text, { text = it }, singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text),
                modifier = Modifier.testTag("text_entry"))
        },
        confirmButton = { DawTextButton("OK", { onConfirm(text) }, accent = true) },
        dismissButton = { DawTextButton("Cancel", onDismiss) },
        containerColor = DawColors.Raised,
    )
}

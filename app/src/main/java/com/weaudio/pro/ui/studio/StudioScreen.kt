package com.weaudio.pro.ui.studio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.weaudio.pro.studio.drum.DrumViewModel
import com.weaudio.pro.arrangement.ArrangementViewModel
import com.weaudio.pro.arrangement.ui.ArrangementWorkspace
import com.weaudio.pro.arrangement.ui.LocalKeyboardGate
import androidx.compose.runtime.CompositionLocalProvider
import com.weaudio.pro.studio.drum.DrumWorkspace
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.weaudio.pro.audio.AudioViewModel
import com.weaudio.pro.audio.EngineState
import com.weaudio.pro.audio.formatTransportTime
import com.weaudio.pro.project.Project
import com.weaudio.pro.ui.components.*
import com.weaudio.pro.ui.theme.DawColors
import com.weaudio.pro.ui.theme.DawType

@Composable
fun StudioScreen(project: Project, audio: AudioViewModel, drums: DrumViewModel, arrangement: ArrangementViewModel, landscape: Boolean) {
    val state by audio.state.collectAsStateWithLifecycle()
    val engine = state.engine

    // Keyboard drumming is only live while this screen is on top.
    DisposableEffect(drums) {
        drums.setStudioVisible(true)
        onDispose { drums.setStudioVisible(false) }
    }
    // The Studio needs a running stream to make sound: start it once on first entry (after that Stop/Pause are respected).
    var autoStarted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(engine) {
        if (!autoStarted && engine == EngineState.STOPPED) { autoStarted = true; audio.play() }
    }

    // Rename/tempo dialogs switch the physical-keyboard drum mapping off so letters can be typed.
    val gate: (Boolean) -> Unit = { editing -> drums.setStudioVisible(!editing) }
    Column(Modifier.fillMaxSize().background(DawColors.Background)) {
        if (state.error != null) {
            ErrorBanner(state.error!!, onRetry = audio::reinitialize, onDismiss = audio::dismissError)
        }
        CompositionLocalProvider(LocalKeyboardGate provides gate) {
            ArrangementWorkspace(arrangement, landscape, pads = { DrumWorkspace(drums, audio, landscape, Modifier.fillMaxSize()) }, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun TransportPanel(project: Project, audio: AudioViewModel, engine: EngineState, compact: Boolean) {
    val busy = engine == EngineState.STARTING || engine == EngineState.DISCONNECTED
    val playing = engine == EngineState.RUNNING || engine == EngineState.STARTING
    val buttons: @Composable () -> Unit = {
        TransportButton(TransportIcon.Restart, "Restart", active = false, enabled = !busy, onClick = audio::restart)
        TransportButton(TransportIcon.Play, "Play", active = playing, enabled = !playing && !busy, onClick = audio::play)
        TransportButton(TransportIcon.Pause, "Pause", active = engine == EngineState.PAUSED,
            enabled = engine == EngineState.RUNNING, onClick = audio::pause)
        TransportButton(TransportIcon.Stop, "Stop", active = false,
            enabled = engine == EngineState.RUNNING || engine == EngineState.PAUSED || engine == EngineState.STARTING,
            onClick = audio::stop)
    }
    val tempo: @Composable (Modifier) -> Unit = { LcdDisplay("Tempo", "%.2f".format(java.util.Locale.US, project.tempoBpm), it) }
    val sig: @Composable (Modifier) -> Unit = { LcdDisplay("Time sig", project.timeSignature.toString(), it) }
    val eng: @Composable (Modifier) -> Unit = {
        LcdDisplay("Engine", engineStatusLabel(engine), it.testTag("engine_status"), valueColor = engineStatusColor(engine), dot = engineStatusColor(engine))
    }
    if (compact) {
        Row(Modifier.fillMaxWidth().background(DawColors.Panel).padding(horizontal = 10.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            buttons()
            Spacer(Modifier.width(8.dp))
            tempo(Modifier.width(92.dp)); sig(Modifier.width(76.dp)); eng(Modifier.width(150.dp))
            TransportTime(audio, Modifier.width(118.dp))
            Box(Modifier.weight(1f).padding(start = 8.dp)) { OutputMeter(audio) }
        }
    } else {
        Column(Modifier.fillMaxWidth().background(DawColors.Panel).padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                buttons()
                Spacer(Modifier.weight(1f))
                TransportTime(audio, Modifier.width(118.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                tempo(Modifier.weight(1.2f)); sig(Modifier.weight(0.8f)); eng(Modifier.weight(1.6f))
            }
            OutputMeter(audio)
        }
    }
}

/** Observes the fast-changing meters flow so only this widget recomposes ~6x/s. */
@Composable
private fun TransportTime(audio: AudioViewModel, modifier: Modifier) {
    val meters by audio.meters.collectAsStateWithLifecycle()
    LcdDisplay("Position", formatTransportTime(meters.positionFrames, meters.sampleRate), modifier.testTag("transport_time"))
}

/** Real master peak level reported by the native engine. */
@Composable
private fun OutputMeter(audio: AudioViewModel) {
    val meters by audio.meters.collectAsStateWithLifecycle()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("OUT", style = DawType.Label, color = DawColors.TextSecondary, modifier = Modifier.width(30.dp))
        Canvas(Modifier.weight(1f).height(5.dp)) {
            drawRect(DawColors.Lcd)
            drawRect(DawColors.Accent, size = size.copy(width = size.width * meters.peak.coerceIn(0f, 1f)))
        }
    }
}

@Composable
private fun ErrorBanner(message: String, onRetry: () -> Unit, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(DawColors.Raised).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(DawColors.Error))
        Spacer(Modifier.width(8.dp))
        Text(message, color = DawColors.TextPrimary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).testTag("error_message"))
        Spacer(Modifier.width(8.dp))
        DawTextButton("Retry", onRetry)
        Spacer(Modifier.width(6.dp))
        DawTextButton("Dismiss", onDismiss)
    }
}

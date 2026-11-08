package com.weaudio.pro.ui.studio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
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
fun StudioScreen(project: Project, audio: AudioViewModel) {
    val state by audio.state.collectAsStateWithLifecycle()
    val engine = state.engine
    Column(Modifier.fillMaxSize().background(DawColors.Background)) {
        TransportPanel(project, audio, engine)
        HairlineDivider()
        if (state.error != null) {
            ErrorBanner(state.error!!, onRetry = audio::reinitialize, onDismiss = audio::dismissError)
        }
        Workspace(project, Modifier.weight(1f))
    }
}

@Composable
private fun TransportPanel(project: Project, audio: AudioViewModel, engine: EngineState) {
    val busy = engine == EngineState.STARTING || engine == EngineState.DISCONNECTED
    val playing = engine == EngineState.RUNNING || engine == EngineState.STARTING
    Column(Modifier.fillMaxWidth().background(DawColors.Panel).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            TransportButton(TransportIcon.Restart, "Restart", active = false, enabled = !busy, onClick = audio::restart)
            TransportButton(TransportIcon.Play, "Play", active = playing, enabled = !playing && !busy, onClick = audio::play)
            TransportButton(TransportIcon.Pause, "Pause", active = engine == EngineState.PAUSED,
                enabled = engine == EngineState.RUNNING, onClick = audio::pause)
            TransportButton(TransportIcon.Stop, "Stop", active = false,
                enabled = engine == EngineState.RUNNING || engine == EngineState.PAUSED || engine == EngineState.STARTING,
                onClick = audio::stop)
            Spacer(Modifier.weight(1f))
            TransportTime(audio, Modifier.width(118.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LcdDisplay("Tempo", "%.2f".format(java.util.Locale.US, project.tempoBpm), Modifier.weight(1.2f))
            LcdDisplay("Time sig", project.timeSignature.toString(), Modifier.weight(0.8f))
            LcdDisplay("Engine", engineStatusLabel(engine), Modifier.weight(1.6f).testTag("engine_status"),
                valueColor = engineStatusColor(engine), dot = engineStatusColor(engine))
        }
        OutputMeter(audio)
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

private const val BARS = 64
private val BarWidth = 112.dp
private val RulerHeight = 28.dp

@Composable
private fun Workspace(project: Project, modifier: Modifier) {
    Row(modifier.fillMaxWidth()) {
        TrackHeaderRegion(Modifier.width(128.dp).fillMaxHeight())
        Box(Modifier.width(1.dp).fillMaxHeight().background(DawColors.Separator))
        Box(Modifier.weight(1f).fillMaxHeight()) {
            Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
                Column(Modifier.fillMaxHeight().width(BarWidth * BARS)) {
                    TimelineRuler(project.timeSignature.numerator)
                    HairlineDivider()
                    ArrangementGrid(Modifier.weight(1f).fillMaxWidth())
                }
            }
            Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Arrangement is empty", color = DawColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                Text("Layers and clips arrive in Phase 3", color = DawColors.TextDisabled, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun TrackHeaderRegion(modifier: Modifier) {
    Column(modifier.background(DawColors.Panel)) {
        Box(Modifier.fillMaxWidth().height(RulerHeight).padding(horizontal = 10.dp), contentAlignment = Alignment.CenterStart) {
            Text("TRACKS", style = DawType.Label, color = DawColors.TextSecondary)
        }
        HairlineDivider()
        Spacer(Modifier.weight(1f))
        HairlineDivider()
        // Visibly disabled: layers are not implemented in Phase 1.
        Column(Modifier.fillMaxWidth().padding(10.dp).testTag("add_layer")) {
            DawTextButton("+ Add layer", onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
            Text("Available in Phase 3", style = DawType.Label, color = DawColors.TextDisabled)
        }
    }
}

/** Bar/beat ruler from the project's time signature. It marks positions only; it is not content. */
@Composable
private fun TimelineRuler(beatsPerBar: Int) {
    val measurer = rememberTextMeasurer()
    Canvas(Modifier.fillMaxWidth().height(RulerHeight).background(DawColors.Panel)) {
        val barPx = BarWidth.toPx()
        for (bar in 0 until BARS) {
            val x = bar * barPx
            drawLine(DawColors.TextSecondary, Offset(x, 0f), Offset(x, size.height), 1f)
            drawText(measurer, "${bar + 1}", Offset(x + 4.dp.toPx(), 2.dp.toPx()), style = DawType.Label.copy(color = DawColors.TextSecondary))
            for (beat in 1 until beatsPerBar) {
                val bx = x + barPx * beat / beatsPerBar
                drawLine(DawColors.Separator, Offset(bx, size.height * 0.6f), Offset(bx, size.height), 1f)
            }
        }
    }
}

@Composable
private fun ArrangementGrid(modifier: Modifier) {
    Canvas(modifier.background(DawColors.Background)) {
        val barPx = BarWidth.toPx()
        for (bar in 0 until BARS) drawLine(DawColors.Separator, Offset(bar * barPx, 0f), Offset(bar * barPx, size.height), 1f)
    }
}

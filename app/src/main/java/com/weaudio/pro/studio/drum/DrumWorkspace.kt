package com.weaudio.pro.studio.drum

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.weaudio.pro.audio.AudioViewModel
import com.weaudio.pro.audio.EngineState
import com.weaudio.pro.audio.sampler.DrumInstrument
import com.weaudio.pro.ui.components.DawTextButton
import com.weaudio.pro.ui.components.HairlineDivider
import com.weaudio.pro.ui.components.engineStatusLabel
import com.weaudio.pro.ui.theme.DawColors
import com.weaudio.pro.ui.theme.DawType

@Composable
private fun VBar() = Box(Modifier.width(1.dp).fillMaxHeight().background(DawColors.Separator))

/**
 * Drum performance workspace. Landscape: kit/instrument browser | pads | inspector (DAW layout).
 * Portrait: pads on top, instrument chips + collapsible inspector below. Both read the same
 * [DrumViewModel], so rotation never touches sampler state.
 */
@Composable
fun DrumWorkspace(drums: DrumViewModel, audio: AudioViewModel, landscape: Boolean, modifier: Modifier = Modifier) {
    val ui by drums.state.collectAsStateWithLifecycle()
    val pressed by drums.pressed.collectAsStateWithLifecycle()
    val a by audio.state.collectAsStateWithLifecycle()
    val importer = rememberSampleImporter(drums)
    val d = a.diagnostics
    val strip: @Composable () -> Unit = {
        HairlineDivider()
        StatusStrip(ui, d["drumActiveVoices"], d["xRuns"], d.bufferMillis(), engineStatusLabel(a.engine))
    }
    Column(modifier.fillMaxSize().background(DawColors.Background)) {
        if (a.engine != EngineState.RUNNING && a.engine != EngineState.STARTING) {
            Text("Audio is ${engineStatusLabel(a.engine).lowercase()} — press Play to hear the drums",
                style = DawType.Label, color = DawColors.Warning,
                modifier = Modifier.fillMaxWidth().background(DawColors.Raised).padding(horizontal = 10.dp, vertical = 5.dp).testTag("audio_not_running"))
        }
        if (landscape) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.width(196.dp).fillMaxHeight().background(DawColors.Panel).verticalScroll(rememberScrollState())) {
                    Text("KIT", style = DawType.Label, color = DawColors.TextSecondary, modifier = Modifier.padding(10.dp, 8.dp, 10.dp, 4.dp))
                    KitSelector(ui, drums, Modifier.padding(horizontal = 8.dp))
                    Text("INSTRUMENTS", style = DawType.Label, color = DawColors.TextSecondary, modifier = Modifier.padding(10.dp, 12.dp, 10.dp, 4.dp))
                    InstrumentList(ui, drums)
                }
                VBar()
                PadGrid(ui, pressed, columns = 4, vm = drums, modifier = Modifier.weight(1f).fillMaxHeight())
                VBar()
                InstrumentInspector(ui, drums, importer, Modifier.width(236.dp).fillMaxHeight().background(DawColors.Panel).verticalScroll(rememberScrollState()))
            }
        } else {
            PadGrid(ui, pressed, columns = 3, vm = drums, modifier = Modifier.weight(1f).fillMaxWidth())
            HairlineDivider()
            InstrumentChips(ui, drums)
            HairlineDivider()
            var expanded by rememberSaveable { mutableStateOf(true) }
            Column(Modifier.fillMaxWidth().background(DawColors.Panel)) {
                Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 10.dp, vertical = 8.dp).testTag("inspector_toggle"), verticalAlignment = Alignment.CenterVertically) {
                    Text("INSTRUMENT SETTINGS — ${ui.selected.label.uppercase()}", style = DawType.Label, color = DawColors.TextSecondary, modifier = Modifier.weight(1f))
                    Text(if (expanded) "HIDE" else "SHOW", style = DawType.Label, color = DawColors.Accent)
                }
                if (expanded) InstrumentInspector(ui, drums, importer, Modifier.heightIn(max = 250.dp).verticalScroll(rememberScrollState()))
            }
        }
        strip()
    }
}

@Composable
private fun InstrumentChips(ui: DrumUiState, vm: DrumViewModel) {
    Row(Modifier.fillMaxWidth().background(DawColors.Panel).horizontalScroll(rememberScrollState()).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        DrumInstrument.entries.forEach { inst ->
            val sel = inst == ui.selected
            Text(inst.label.uppercase(), style = DawType.Label, color = if (sel) DawColors.TextPrimary else DawColors.TextSecondary,
                modifier = Modifier.background(if (sel) DawColors.AccentDim else DawColors.Raised).border(1.dp, if (sel) DawColors.Accent else DawColors.Separator)
                    .clickable { vm.select(inst) }.padding(horizontal = 10.dp, vertical = 10.dp).testTag("chip_${inst.name.lowercase()}"))
        }
    }
}

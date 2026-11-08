package com.weaudio.pro.arrangement.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.weaudio.pro.arrangement.*
import com.weaudio.pro.audio.sampler.DrumInstrument
import com.weaudio.pro.ui.components.DawTextButton
import com.weaudio.pro.ui.components.HairlineDivider
import com.weaudio.pro.ui.theme.DawColors
import com.weaudio.pro.ui.theme.DawType

/**
 * Arrangement workspace.
 *  Landscape: transport / [track list | ruler + timeline] / [track+clip details | pattern editor] (+ ARRANGE|PADS switch).
 *  Portrait : transport / one tab at a time: ARRANGE (timeline + details) | PATTERN (full editor) | PADS, with a bottom tab bar.
 * All state lives in [ArrangementViewModel], so rotating never loses selection, zoom, scroll or edits.
 */
@Composable
fun ArrangementWorkspace(vm: ArrangementViewModel, landscape: Boolean, pads: @Composable () -> Unit, modifier: Modifier = Modifier) {
    val ui by vm.state.collectAsState()
    Column(modifier.fillMaxSize().background(DawColors.Background)) {
        ArrangementTransportBar(vm, ui, compact = landscape)
        HairlineDivider()
        ui.message?.let { MessageBar(it, vm::dismissMessage) }
        if (landscape) {
            val tab = if (ui.tab == StudioTab.PADS) StudioTab.PADS else StudioTab.ARRANGE
            TabBar(tab, listOf(StudioTab.ARRANGE, StudioTab.PADS), vm::setTab, Modifier.fillMaxWidth())
            HairlineDivider()
            if (tab == StudioTab.PADS) Box(Modifier.weight(1f)) { pads() }
            else {
                Box(Modifier.weight(0.56f).fillMaxWidth()) { ArrangementTimeline(vm, ui) }
                HairlineDivider()
                Row(Modifier.weight(0.44f).fillMaxWidth()) {
                    DetailsPane(vm, ui, Modifier.width(236.dp).fillMaxHeight())
                    Box(Modifier.width(1.dp).fillMaxHeight().background(DawColors.Separator))
                    Box(Modifier.weight(1f)) { PatternEditor(vm, ui) }
                }
            }
        } else {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (ui.tab) {
                    StudioTab.ARRANGE -> Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f)) { ArrangementTimeline(vm, ui) }
                        HairlineDivider()
                        DetailsPane(vm, ui, Modifier.fillMaxWidth().heightIn(max = 190.dp))
                    }
                    StudioTab.PATTERN -> PatternEditor(vm, ui)
                    StudioTab.PADS -> pads()
                }
            }
            HairlineDivider()
            TabBar(ui.tab, StudioTab.entries, vm::setTab, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun TabBar(current: StudioTab, tabs: List<StudioTab>, onSelect: (StudioTab) -> Unit, modifier: Modifier) {
    Row(modifier.background(DawColors.Panel)) {
        tabs.forEach { t ->
            val sel = t == current
            Column(Modifier.weight(1f).clickable { onSelect(t) }.padding(vertical = 8.dp).testTag("tab_${t.name.lowercase()}"), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(t.name, style = DawType.Label, color = if (sel) DawColors.TextPrimary else DawColors.TextSecondary)
                Spacer(Modifier.height(4.dp))
                Box(Modifier.width(24.dp).height(2.dp).background(if (sel) DawColors.Accent else DawColors.Panel))
            }
        }
    }
}

@Composable
private fun MessageBar(text: String, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(DawColors.Raised).clickable(onClick = onDismiss).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(DawColors.Warning)); Spacer(Modifier.width(8.dp))
        Text(text, style = DawType.Label, color = DawColors.TextPrimary, modifier = Modifier.testTag("arr_message"))
    }
}

/** Selected track controls (instrument, volume, pan, rename/duplicate/delete) and selected clip operations. */
@Composable
fun DetailsPane(vm: ArrangementViewModel, ui: ArrangementUiState, modifier: Modifier = Modifier) {
    val track = ui.selectedTrack; val clip = ui.selectedClip
    var rename by rememberSaveable { mutableStateOf(false) }
    var instMenu by remember { mutableStateOf(false) }
    val t by vm.transport.collectAsState()
    Column(modifier.background(DawColors.Panel).verticalScroll(rememberScrollState()).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("TRACK", style = DawType.Label, color = DawColors.TextSecondary)
        if (track == null) Text("No track selected", style = DawType.Label, color = DawColors.TextDisabled)
        else {
            Text(track.name, color = DawColors.TextPrimary, style = DawType.Value, maxLines = 1)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box {
                    DawTextButton((DrumInstrument.fromSlot(track.instrumentSlot)?.label ?: "Instrument") + " ▾", { instMenu = true }, modifier = Modifier.testTag("track_instrument"))
                    DropdownMenu(instMenu, { instMenu = false }, Modifier.background(DawColors.Raised)) {
                        DrumInstrument.entries.forEach { i -> DropdownMenuItem(text = { Text(i.label) }, onClick = { vm.setTrackInstrument(track.id, i.slot); instMenu = false }) }
                    }
                }
                DawTextButton("Rename", { rename = true }, modifier = Modifier.testTag("track_rename"))
            }
            LabeledSlider("VOL", track.volume, 0f..1.5f, "%.0f%%".format(java.util.Locale.US, track.volume * 100), "track_volume") { vm.setTrackVolume(track.id, it) }
            LabeledSlider("PAN", track.pan, -1f..1f, panLabel(track.pan), "track_pan") { vm.setTrackPan(track.id, it) }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                DawTextButton("Duplicate", { vm.duplicateTrack(track.id) }, modifier = Modifier.testTag("track_duplicate"))
                DawTextButton("Delete", { vm.removeTrack(track.id) }, modifier = Modifier.testTag("track_delete"))
            }
        }
        HairlineDivider()
        Text("CLIP", style = DawType.Label, color = DawColors.TextSecondary)
        if (clip == null) {
            Text("Long-press a lane to create a clip, or:", style = DawType.Label, color = DawColors.TextDisabled)
            DawTextButton("+ Clip at playhead", { vm.addClipAtPlayhead() }, enabled = track != null, modifier = Modifier.testTag("clip_add"))
        } else {
            val sig = ui.project.timeSignature
            val uses = ui.project.patternUseCount(clip.patternId)
            Text(clip.name, color = DawColors.TextPrimary, style = DawType.Value, maxLines = 1)
            Text("START ${formatBarBeat(clip.startTick, sig)}   END ${formatBarBeat(clip.endTick, sig)}", style = DawType.Label, color = DawColors.TextSecondary, modifier = Modifier.testTag("clip_range"))
            Text(if (uses > 1) "PATTERN SHARED BY $uses CLIPS — edits change all" else "PATTERN USED ONLY HERE", style = DawType.Label,
                color = if (uses > 1) DawColors.Warning else DawColors.TextSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                DawTextButton("Split", { vm.splitSelectedAtPlayhead() }, modifier = Modifier.testTag("clip_split"))
                DawTextButton("Duplicate", { vm.duplicateSelectedClip() }, modifier = Modifier.testTag("clip_duplicate"))
                DawTextButton("Delete", { vm.deleteSelectedClip() }, modifier = Modifier.testTag("clip_delete"))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                DawTextButton(if (clip.loop) "Loop: on" else "Loop: off", { vm.toggleSelectedClipLoop() }, accent = clip.loop, modifier = Modifier.testTag("clip_loop"))
                DawTextButton("Make unique", { vm.makeSelectedClipUnique() }, enabled = uses > 1, modifier = Modifier.testTag("clip_unique"))
            }
            val grid = ui.project.playback.quantize.gridTicks(sig).toLong().coerceAtLeast(TICKS_PER_SIXTEENTH.toLong())
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                DawTextButton("End −", { vm.setClipRange(clip.id, clip.startTick, clip.endTick - grid) }, enabled = clip.durationTicks > grid, modifier = Modifier.testTag("clip_end_minus"))
                DawTextButton("End +", { vm.setClipRange(clip.id, clip.startTick, clip.endTick + grid) }, modifier = Modifier.testTag("clip_end_plus"))
                DawTextButton("Start +", { vm.setClipRange(clip.id, clip.startTick + grid, clip.endTick) }, enabled = clip.durationTicks > grid, modifier = Modifier.testTag("clip_start_plus"))
            }
        }
    }
    if (rename && track != null) TextEntryDialog("Rename track", track.name, onDismiss = { rename = false }, onConfirm = { vm.renameTrack(track.id, it); rename = false })
}

private fun panLabel(p: Float) = when { p < -0.01f -> "L%d".format((-p * 100).toInt()); p > 0.01f -> "R%d".format((p * 100).toInt()); else -> "C" }

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, valueText: String, tag: String, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = DawType.Label, color = DawColors.TextSecondary, modifier = Modifier.width(32.dp))
        Slider(value, onChange, valueRange = range, modifier = Modifier.weight(1f).height(28.dp).testTag(tag),
            colors = SliderDefaults.colors(thumbColor = DawColors.Accent, activeTrackColor = DawColors.Accent, inactiveTrackColor = DawColors.Separator))
        Text(valueText, style = DawType.Label, color = DawColors.TextPrimary, modifier = Modifier.width(40.dp))
    }
}

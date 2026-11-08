package com.weaudio.pro.arrangement.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weaudio.pro.arrangement.*
import com.weaudio.pro.audio.sampler.DrumInstrument
import com.weaudio.pro.ui.components.DawTextButton
import com.weaudio.pro.ui.theme.DawColors
import com.weaudio.pro.ui.theme.DawType
import kotlin.math.roundToInt

private val CELL_W = 30.dp
private val CELL_H = 24.dp
private val LABEL_W = 92.dp
private const val DEFAULT_VELOCITY = 0.8f

/**
 * Step sequencer for the selected pattern. Every cell is a real [DrumEvent] in the shared pattern (all clips that use
 * it change); the step width follows the Quantize setting. Rows are the 12 DrumSampler instruments, the selected
 * track's instrument first.
 */
@Composable
fun PatternEditor(vm: ArrangementViewModel, ui: ArrangementUiState, modifier: Modifier = Modifier) {
    val pattern = ui.selectedPattern
    Column(modifier.fillMaxSize().background(DawColors.Background)) {
        PatternHeader(vm, ui)
        if (pattern == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                DawTextButton("New pattern", { vm.createPattern() }, accent = true, modifier = Modifier.testTag("pattern_new_empty"))
            }
            return@Column
        }
        val stepTicks = stepTicksFor(ui.project.playback.quantize)
        val steps = (pattern.lengthTicks + stepTicks - 1) / stepTicks
        val beatSteps = maxOf(1, ticksPerBeat(ui.project.timeSignature) / stepTicks)
        val trackSlot = ui.selectedTrack?.instrumentSlot
        val lanes = remember(trackSlot) { DrumInstrument.entries.sortedBy { if (it.slot == trackSlot) -1 else it.slot } }
        var velocitySlot by rememberSaveable(pattern.id) { mutableStateOf(trackSlot ?: DrumInstrument.KICK.slot) }
        val hScroll = rememberScrollState()
        val vScroll = rememberScrollState()

        Row(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.width(LABEL_W).fillMaxHeight().background(DawColors.Panel)) {
                Column(Modifier.weight(1f).verticalScroll(vScroll)) {
                    lanes.forEach { inst ->
                        val sel = inst.slot == velocitySlot
                        Box(Modifier.height(CELL_H).fillMaxWidth().background(if (sel) DawColors.AccentDim else DawColors.Panel)
                            .clickable { velocitySlot = inst.slot }.padding(horizontal = 6.dp).testTag("lane_label_${inst.name.lowercase()}"), contentAlignment = Alignment.CenterStart) {
                            Text(inst.label.uppercase(), style = DawType.Label.copy(fontSize = 9.sp), color = if (sel) DawColors.TextPrimary else DawColors.TextSecondary, maxLines = 1)
                        }
                    }
                }
                Box(Modifier.height(52.dp).fillMaxWidth().padding(6.dp), contentAlignment = Alignment.CenterStart) {
                    Text("VELOCITY", style = DawType.Label.copy(fontSize = 9.sp), color = DawColors.TextSecondary)
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight().horizontalScroll(hScroll)) {
                Box(Modifier.weight(1f).width(CELL_W * steps.toInt())) {
                    Column(Modifier.fillMaxSize().verticalScroll(vScroll)) {
                        lanes.forEach { inst ->
                            Row(Modifier.height(CELL_H)) {
                                for (s in 0 until steps.toInt()) {
                                    val cellStart = s * stepTicks
                                    val hit = pattern.events.firstOrNull { it.instrumentSlot == inst.slot && it.tick >= cellStart && it.tick < cellStart + stepTicks }
                                    StepCell(hit?.velocity, s % (beatSteps * 2) < beatSteps, "step_${inst.slot}_$s") {
                                        vm.toggleStep(pattern.id, inst.slot, hit?.tick ?: cellStart, DEFAULT_VELOCITY)
                                    }
                                }
                            }
                        }
                    }
                    StepCursor(vm, ui, pattern, stepTicks)
                }
                VelocityLane(vm, pattern, velocitySlot, steps.toInt(), stepTicks)
            }
        }
    }
}

@Composable
private fun StepCell(velocity: Float?, shaded: Boolean, tag: String, onClick: () -> Unit) {
    Box(Modifier.width(CELL_W).fillMaxHeight().padding(1.dp).background(if (shaded) Color2 else Color1)
        .clickable(onClick = onClick).testTag(tag)) {
        if (velocity != null) Box(Modifier.fillMaxSize().background(DawColors.Accent.copy(alpha = 0.35f + 0.65f * velocity)))
    }
}
private val Color1 = DawColors.Raised
private val Color2 = androidx.compose.ui.graphics.Color(0xFF16181A)

@Composable
private fun VelocityLane(vm: ArrangementViewModel, pattern: Pattern, slot: Int, steps: Int, stepTicks: Int) {
    val density = LocalDensity.current
    Row(Modifier.height(52.dp).width(CELL_W * steps).background(DawColors.Panel)) {
        for (s in 0 until steps) {
            val cellStart = s * stepTicks
            val hit = pattern.events.firstOrNull { it.instrumentSlot == slot && it.tick >= cellStart && it.tick < cellStart + stepTicks }
            var live by remember(pattern.id, slot, s, hit?.velocity) { mutableStateOf<Float?>(null) }
            val shown = live ?: hit?.velocity
            Box(Modifier.width(CELL_W).fillMaxHeight().padding(horizontal = 4.dp, vertical = 2.dp)
                .pointerInput(pattern.id, slot, s, hit?.tick) {
                    if (hit == null) return@pointerInput
                    val h = size.height.toFloat()
                    detectTapGestures { o -> vm.setStepVelocity(pattern.id, slot, hit.tick, 1f - (o.y / h)) }
                }
                .pointerInput(pattern.id, slot, s, hit?.tick) {
                    if (hit == null) return@pointerInput
                    val h = size.height.toFloat()
                    detectDragGestures(
                        onDrag = { change, _ -> change.consume(); live = (1f - change.position.y / h).coerceIn(0.05f, 1f) },
                        onDragEnd = { live?.let { vm.setStepVelocity(pattern.id, slot, hit.tick, it) }; live = null },
                        onDragCancel = { live = null })
                }.testTag("velocity_$s"), contentAlignment = Alignment.BottomCenter) {
                if (shown != null) Box(Modifier.fillMaxWidth().fillMaxHeight(shown.coerceIn(0.05f, 1f)).background(DawColors.Accent))
                else Box(Modifier.fillMaxWidth().height(1.dp).background(DawColors.Separator))
            }
        }
    }
}

/** Highlights the step the native transport (or the preview loop) is playing now. */
@Composable
private fun StepCursor(vm: ArrangementViewModel, ui: ArrangementUiState, pattern: Pattern, stepTicks: Int) {
    val t by vm.transport.collectAsState()
    val density = LocalDensity.current
    val len = pattern.lengthTicks.toLong()
    val tickInPattern: Long? = when {
        ui.previewPatternId == pattern.id && t.mode == TransportMode.PREVIEW -> t.positionTicks % len
        t.mode != TransportMode.PLAYING -> null
        else -> ui.project.tracks.asSequence().flatMap { it.clips.asSequence() }
            .firstOrNull { it.patternId == pattern.id && t.positionTicks >= it.startTick && t.positionTicks < it.endTick && (it.loop || t.positionTicks - it.startTick + it.offsetTicks < len) }
            ?.let { (t.positionTicks - it.startTick + it.offsetTicks) % len }
    }
    if (tickInPattern == null) return
    val step = (tickInPattern / stepTicks).toInt()
    Box(Modifier.offset { IntOffset((CELL_W.toPx() * step).roundToInt(), 0) }.width(CELL_W).fillMaxHeight()
        .border(1.dp, DawColors.TextPrimary.copy(alpha = 0.7f)).testTag("step_cursor"))
}

@Composable
private fun PatternHeader(vm: ArrangementViewModel, ui: ArrangementUiState) {
    val pattern = ui.selectedPattern
    var menu by remember { mutableStateOf(false) }
    var lenMenu by remember { mutableStateOf(false) }
    val previewing = pattern != null && ui.previewPatternId == pattern.id
    val uses = pattern?.let { ui.project.patternUseCount(it.id) } ?: 0
    Row(Modifier.fillMaxWidth().background(DawColors.Panel).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            DawTextButton((pattern?.name ?: "No pattern") + " ▾", { menu = true }, modifier = Modifier.testTag("pattern_select"))
            DropdownMenu(menu, { menu = false }, Modifier.background(DawColors.Raised)) {
                ui.project.patterns.forEach { p -> DropdownMenuItem(text = { Text(p.name) }, onClick = { vm.selectPattern(p.id); menu = false }) }
            }
        }
        DawTextButton(if (previewing) "Stop preview" else "Preview", { if (previewing) vm.stopPreview() else pattern?.let { vm.startPreview(it.id) } },
            enabled = pattern != null, accent = previewing, modifier = Modifier.testTag("pattern_preview"))
        DawTextButton("New", { vm.createPattern() }, modifier = Modifier.testTag("pattern_new"))
        DawTextButton("Duplicate", { pattern?.let { vm.duplicatePattern(it.id) } }, enabled = pattern != null, modifier = Modifier.testTag("pattern_duplicate"))
        DawTextButton("Clear", { pattern?.let { vm.clearPattern(it.id) } }, enabled = pattern != null, modifier = Modifier.testTag("pattern_clear"))
        Box {
            val bar = ticksPerBar(ui.project.timeSignature)
            DawTextButton("Length ${pattern?.let { "%.2g".format(java.util.Locale.US, it.lengthTicks / bar.toDouble()) } ?: "-"} bar ▾", { lenMenu = true }, enabled = pattern != null, modifier = Modifier.testTag("pattern_length"))
            DropdownMenu(lenMenu, { lenMenu = false }, Modifier.background(DawColors.Raised)) {
                listOf(0.5, 1.0, 2.0, 4.0).forEach { b -> DropdownMenuItem(text = { Text("$b bar") }, onClick = { pattern?.let { vm.setPatternLength(it.id, (bar * b).toInt()) }; lenMenu = false }) }
            }
        }
        Text(when { uses > 1 -> "SHARED BY $uses CLIPS"; uses == 1 -> "USED BY 1 CLIP"; else -> "NOT PLACED" }, style = DawType.Label,
            color = if (uses > 1) DawColors.Warning else DawColors.TextSecondary, maxLines = 1, modifier = Modifier.testTag("pattern_usage"))
        Text("STEP ${Quantize.entries.firstOrNull { it.ticks == stepTicksFor(ui.project.playback.quantize) }?.label ?: "1/16"}", style = DawType.Label, color = DawColors.TextSecondary, maxLines = 1)
    }
}


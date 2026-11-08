package com.weaudio.pro.arrangement.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weaudio.pro.arrangement.*
import com.weaudio.pro.audio.sampler.DrumInstrument
import com.weaudio.pro.ui.theme.DawColors
import com.weaudio.pro.ui.theme.DawType
import kotlin.math.roundToInt

private val RULER_H = 24.dp
private const val HEADER_W = 150

/** Track headers (fixed, left) + bar ruler + lanes (horizontally scrolling, zoomable). One vertical scroll drives both. */
@Composable
fun ArrangementTimeline(vm: ArrangementViewModel, ui: ArrangementUiState, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val vp = ui.viewport
    val project = ui.project
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState(with(density) { vp.scrollDp.dp.roundToPx() })
    val contentDp = vp.contentWidthDp(project.lengthTicks)

    // The ScrollState is the live source while the user drags; the ViewModel keeps it across rotation and drives follow-playhead.
    LaunchedEffect(hScroll) { snapshotFlow { hScroll.value }.collect { vm.scrollTo(with(density) { it.toDp().value }) } }
    LaunchedEffect(vp.scrollDp) {
        val target = with(density) { vp.scrollDp.dp.roundToPx() }
        if (kotlin.math.abs(hScroll.value - target) > 2) hScroll.scrollTo(target)
    }

    Row(modifier.fillMaxSize().background(DawColors.Background)) {
        Column(Modifier.width(HEADER_W.dp).fillMaxHeight().background(DawColors.Panel)) {
            Box(Modifier.fillMaxWidth().height(RULER_H).background(DawColors.Panel), contentAlignment = Alignment.CenterStart) {
                Text("TRACKS", style = DawType.Label, color = DawColors.TextSecondary, modifier = Modifier.padding(start = 8.dp))
            }
            Column(Modifier.weight(1f).verticalScroll(vScroll)) {
                project.tracks.forEach { t -> TrackHeader(vm, ui, t) }
                AddTrackButton(vm)
            }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(DawColors.Separator))
        Box(Modifier.weight(1f).fillMaxHeight().onSizeChanged { vm.viewWidthDp = with(density) { it.width.toDp().value } }) {
            Column(Modifier.fillMaxSize().horizontalScroll(hScroll)) {
                Ruler(vm, ui, contentDp)
                Box(Modifier.weight(1f).width(contentDp.dp)) {
                    Column(Modifier.fillMaxSize().verticalScroll(vScroll)) {
                        project.tracks.forEach { t -> Lane(vm, ui, t, contentDp) }
                        Spacer(Modifier.height(56.dp))
                    }
                    LoopBand(ui)
                    Playhead(vm, vp)
                }
            }
        }
    }
}

@Composable
private fun TrackHeader(vm: ArrangementViewModel, ui: ArrangementUiState, t: Track) {
    val selected = ui.selectedTrackId == t.id
    Row(Modifier.fillMaxWidth().height(ui.trackHeightDp.dp).background(if (selected) DawColors.Raised else DawColors.Panel)
        .clickable { vm.selectTrack(t.id) }.testTag("track_header_${t.id}"),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(Color(t.colorArgb)))
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Text(t.name, color = DawColors.TextPrimary, style = DawType.Value.copy(fontSize = 12.sp), maxLines = 1)
            Text((DrumInstrument.fromSlot(t.instrumentSlot)?.label ?: "—").uppercase(), style = DawType.Label, color = DawColors.TextSecondary, maxLines = 1)
        }
        ToggleBox("M", t.mute, DawColors.Warning, "mute_${t.id}") { vm.setTrackMute(t.id, !t.mute) }
        Spacer(Modifier.width(3.dp))
        ToggleBox("S", t.solo, DawColors.Accent, "solo_${t.id}") { vm.setTrackSolo(t.id, !t.solo) }
        Spacer(Modifier.width(5.dp))
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(DawColors.Separator))
}

@Composable
private fun ToggleBox(label: String, on: Boolean, color: Color, tag: String, onClick: () -> Unit) {
    Box(Modifier.size(26.dp).background(if (on) color else DawColors.Lcd).border(1.dp, if (on) color else DawColors.Separator)
        .clickable(onClick = onClick).testTag(tag), contentAlignment = Alignment.Center) {
        Text(label, style = DawType.Label, color = if (on) DawColors.Background else DawColors.TextSecondary)
    }
}

@Composable
private fun AddTrackButton(vm: ArrangementViewModel) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().padding(8.dp)) {
        Box(Modifier.fillMaxWidth().background(DawColors.Raised).border(1.dp, DawColors.Separator).clickable { open = true }.padding(vertical = 10.dp).testTag("add_track"),
            contentAlignment = Alignment.Center) { Text("+ TRACK", style = DawType.Label, color = DawColors.Accent) }
        DropdownMenu(open, { open = false }, Modifier.background(DawColors.Raised)) {
            DrumInstrument.entries.forEach { i -> DropdownMenuItem(text = { Text(i.label) }, onClick = { vm.addTrack(i.slot); open = false }) }
        }
    }
}

@Composable
private fun Ruler(vm: ArrangementViewModel, ui: ArrangementUiState, contentDp: Float) {
    val vp = ui.viewport; val sig = ui.project.timeSignature
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    Canvas(Modifier.width(contentDp.dp).height(RULER_H).background(DawColors.Panel)
        .pointerInput(vp, sig) {
            detectTapGestures { o -> vm.seekTo(vp.dpToTick(o.x / density.density)) }
        }
        .pointerInput(vp, sig) {
            detectDragGestures { change, _ -> vm.seekTo(vp.dpToTick(change.position.x.coerceAtLeast(0f) / density.density)) }
        }.testTag("ruler")) {
        val barTicks = ticksPerBar(sig).toLong(); val beatTicks = ticksPerBeat(sig).toLong()
        val total = ui.project.lengthTicks + 8 * barTicks
        var tick = 0L; var bar = 1
        val labelStyle = TextStyle(color = DawColors.TextSecondary, fontSize = 10.sp)
        while (tick <= total) {
            val x = vp.tickToDp(tick).dp.toPx()
            drawLine(DawColors.TextSecondary, Offset(x, size.height * 0.35f), Offset(x, size.height), 1f)
            if (x + 24.dp.toPx() < size.width) {   // fixed label box: never lay text out past the canvas edge (negative maxWidth crash)
                drawText(measurer, "$bar", Offset(x + 3.dp.toPx(), 1.dp.toPx()), labelStyle, size = Size(24.dp.toPx(), 14.dp.toPx()))
            }
            if (vp.dpPerBeat >= 28f) { var b = beatTicks; while (b < barTicks) {
                val bx = vp.tickToDp(tick + b).dp.toPx(); drawLine(DawColors.Separator, Offset(bx, size.height * 0.65f), Offset(bx, size.height), 1f); b += beatTicks } }
            tick += barTicks; bar++
        }
        val l = ui.project.playback.loop
        if (l.enabled) drawRect(DawColors.Accent.copy(alpha = 0.35f), Offset(vp.tickToDp(l.startTick).dp.toPx(), size.height - 4.dp.toPx()),
            Size((vp.tickToDp(l.endTick) - vp.tickToDp(l.startTick)).dp.toPx(), 4.dp.toPx()))
    }
}

@Composable
private fun Lane(vm: ArrangementViewModel, ui: ArrangementUiState, t: Track, contentDp: Float) {
    val vp = ui.viewport; val sig = ui.project.timeSignature; val density = LocalDensity.current
    val selectedTrack = ui.selectedTrackId == t.id
    Box(Modifier.width(contentDp.dp).height(ui.trackHeightDp.dp).testTag("lane_${t.id}")
        .background(if (selectedTrack) Color(0xFF181A1D) else DawColors.Background)
        .pointerInput(vp, t.id, ui.project.playback.quantize) {
            detectTapGestures(
                onTap = { vm.selectTrack(t.id); vm.selectClip(null) },
                onLongPress = { o -> vm.createPatternClip(t.id, vp.dpToTick(o.x / density.density)) },
            )
        }) {
        Canvas(Modifier.fillMaxSize()) {
            val barTicks = ticksPerBar(sig).toLong(); val beatTicks = ticksPerBeat(sig).toLong()
            val total = ui.project.lengthTicks + 8 * barTicks
            var tick = 0L
            while (tick <= total) {
                val x = vp.tickToDp(tick).dp.toPx()
                drawLine(DawColors.Separator, Offset(x, 0f), Offset(x, size.height), 1.5f)
                if (vp.dpPerBeat >= 40f) { var b = beatTicks; while (b < barTicks) {
                    val bx = vp.tickToDp(tick + b).dp.toPx(); drawLine(Color(0xFF202327), Offset(bx, 0f), Offset(bx, size.height), 1f); b += beatTicks } }
                tick += barTicks
            }
            drawLine(DawColors.Separator, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1f)
        }
        t.clips.forEach { c -> ClipBlock(vm, ui, t, c, density.density) }
    }
}

@Composable
private fun ClipBlock(vm: ArrangementViewModel, ui: ArrangementUiState, track: Track, clip: Clip, density: Float) {
    val vp = ui.viewport
    val selected = ui.selectedClipId == clip.id
    val pattern = ui.project.pattern(clip.patternId)
    val shared = ui.project.patternUseCount(clip.patternId)
    var dragX by remember(clip.id, clip.startTick) { mutableStateOf(0f) }
    var dragY by remember(clip.id, clip.startTick) { mutableStateOf(0f) }
    var resizeX by remember(clip.id, clip.durationTicks) { mutableStateOf(0f) }
    val color = Color(track.colorArgb)
    val widthDp = (vp.tickToDp(clip.durationTicks) + resizeX / density).coerceAtLeast(8f)
    val trackH = ui.trackHeightDp

    Box(Modifier
        .offset { IntOffset((vp.tickToDp(clip.startTick) * density + dragX).roundToInt(), dragY.roundToInt()) }
        .width(widthDp.dp).fillMaxHeight().padding(vertical = 3.dp)
        .background(color.copy(alpha = 0.28f)).border(if (selected) 2.dp else 1.dp, if (selected) DawColors.Accent else color.copy(alpha = 0.8f))
        .pointerInput(clip.id, vp, ui.project.playback.quantize) { detectTapGestures(onTap = { vm.selectClip(clip.id) }) }
        .pointerInput(clip.id, vp, ui.project.playback.quantize, ui.project.tracks.size) {
            detectDragGestures(
                onDragStart = { vm.selectClip(clip.id) },
                onDrag = { change, d -> change.consume(); dragX += d.x; dragY += d.y },
                onDragEnd = {
                    val row = (dragY / (trackH * density)).roundToInt()
                    val idx = (ui.project.trackIndex(track.id) + row).coerceIn(0, ui.project.tracks.lastIndex)
                    vm.moveClip(clip.id, clip.startTick + vp.dpToTick(dragX / density), ui.project.tracks[idx].id)
                    dragX = 0f; dragY = 0f
                },
                onDragCancel = { dragX = 0f; dragY = 0f },
            )
        }.testTag("clip_${clip.id}")) {
        // Real content: one tick mark per drum event, positioned by tick (no fake waveform).
        if (pattern != null && pattern.lengthTicks > 0) Canvas(Modifier.fillMaxSize().padding(top = 14.dp, bottom = 3.dp)) {
            val len = pattern.lengthTicks.toLong(); val off = clip.offsetTicks.toLong()
            val cycles = if (clip.loop) (clip.durationTicks + off + len - 1) / len else 1L
            val slots = pattern.events.map { it.instrumentSlot }.distinct().sorted()
            val rowH = size.height / maxOf(1, slots.size)
            for (k in 0 until cycles) for (e in pattern.events) {
                val rel = e.tick - off + k * len
                if (!e.enabled || rel < 0 || rel >= clip.durationTicks) continue
                if (!clip.loop && e.tick < off) continue
                val x = vp.tickToDp(rel).dp.toPx(); val row = slots.indexOf(e.instrumentSlot)
                drawRect(color.copy(alpha = 0.55f + 0.45f * e.velocity), Offset(x, row * rowH + 1f), Size(maxOf(2f, vp.tickToDp(TICKS_PER_SIXTEENTH.toLong()).dp.toPx() * 0.7f), maxOf(2f, rowH - 2f)))
            }
        }
        Text(clip.name + (if (shared > 1) " ×$shared" else "") + (if (!clip.loop) " ·1×" else ""), style = DawType.Label.copy(fontSize = 9.sp), color = DawColors.TextPrimary,
            maxLines = 1, modifier = Modifier.padding(start = 4.dp, top = 1.dp))
        // Right-edge resize handle.
        Box(Modifier.align(Alignment.CenterEnd).width(14.dp).fillMaxHeight().pointerInput(clip.id, vp, ui.project.playback.quantize) {
            detectDragGestures(
                onDrag = { change, d -> change.consume(); resizeX += d.x },
                onDragEnd = { vm.setClipRange(clip.id, clip.startTick, clip.endTick + vp.dpToTick(resizeX / density)); resizeX = 0f },
                onDragCancel = { resizeX = 0f })
        }.background(if (selected) DawColors.Accent.copy(alpha = 0.5f) else color.copy(alpha = 0.25f)))
    }
}

@Composable
private fun LoopBand(ui: ArrangementUiState) {
    val l = ui.project.playback.loop
    if (!l.enabled) return
    val vp = ui.viewport
    Box(Modifier.offset { IntOffset(vp.tickToDp(l.startTick).dp.roundToPx(), 0) }.width((vp.tickToDp(l.endTick) - vp.tickToDp(l.startTick)).dp)
        .fillMaxHeight().background(DawColors.Accent.copy(alpha = 0.06f)))
}

/** Reads the native position (via the transport flow); only this tiny composable re-lays out ~30x/s. */
@Composable
private fun Playhead(vm: ArrangementViewModel, vp: TimelineViewport) {
    val t by vm.transport.collectAsState()
    val density = LocalDensity.current
    if (t.mode == TransportMode.PREVIEW) return
    Box(Modifier.offset { IntOffset((vp.tickToDp(t.positionTicks) * density.density).roundToInt(), 0) }.width(2.dp).fillMaxHeight()
        .background(DawColors.Accent).testTag("playhead"))
}

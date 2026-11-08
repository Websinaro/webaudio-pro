package com.weaudio.pro.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.weaudio.pro.audio.EngineState
import com.weaudio.pro.ui.theme.DawColors
import com.weaudio.pro.ui.theme.DawType

private val Radius = RoundedCornerShape(3.dp)

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), style = DawType.Label, color = DawColors.TextSecondary,
        modifier = modifier.fillMaxWidth().background(DawColors.Panel).padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
fun KeyValueRow(key: String, value: String, modifier: Modifier = Modifier, valueColor: Color = DawColors.TextPrimary) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(key, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, color = DawColors.TextSecondary, modifier = Modifier.weight(1f))
        Text(value, style = DawType.Value, color = if (value == "Unavailable") DawColors.TextDisabled else valueColor,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}


@Composable
fun HairlineDivider() = Box(Modifier.fillMaxWidth().height(1.dp).background(DawColors.Separator))

/** Recessed read-out like a hardware LCD. */
@Composable
fun LcdDisplay(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = DawColors.TextPrimary, dot: Color? = null) {
    Column(
        modifier.clip(Radius).background(DawColors.Lcd).border(1.dp, DawColors.Separator, Radius)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(label.uppercase(), style = DawType.Label, color = DawColors.TextSecondary, maxLines = 1)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (dot != null) {
                Box(Modifier.size(7.dp).background(dot, androidx.compose.foundation.shape.CircleShape))
                Spacer(Modifier.width(6.dp))
            }
            Text(value, style = DawType.Lcd, color = valueColor, maxLines = 1, overflow = TextOverflow.Clip)
        }
    }
}

enum class TransportIcon { Restart, Play, Pause, Stop }

@Composable
fun TransportButton(
    icon: TransportIcon, description: String, active: Boolean, enabled: Boolean,
    onClick: () -> Unit, modifier: Modifier = Modifier,
) {
    val tint = when {
        !enabled -> DawColors.TextDisabled
        active -> DawColors.Accent
        else -> DawColors.TextPrimary
    }
    Box(
        modifier
            .size(width = 44.dp, height = 40.dp)
            .clip(Radius)
            .background(if (active) DawColors.AccentDim else DawColors.Raised)
            .border(1.dp, if (active) DawColors.Accent else DawColors.Separator, Radius)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .testTag("transport_${icon.name.lowercase()}"),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(16.dp)) {
            val w = size.width; val h = size.height
            when (icon) {
                TransportIcon.Play -> drawPath(Path().apply { moveTo(w * .15f, 0f); lineTo(w, h / 2); lineTo(w * .15f, h); close() }, tint)
                TransportIcon.Pause -> {
                    drawRect(tint, Offset(w * .1f, 0f), Size(w * .30f, h))
                    drawRect(tint, Offset(w * .6f, 0f), Size(w * .30f, h))
                }
                TransportIcon.Stop -> drawRect(tint, Offset(w * .1f, h * .1f), Size(w * .8f, h * .8f))
                TransportIcon.Restart -> {
                    drawRect(tint, Offset(0f, 0f), Size(w * .16f, h))
                    drawPath(Path().apply { moveTo(w, 0f); lineTo(w * .26f, h / 2); lineTo(w, h); close() }, tint)
                }
            }
        }
    }
}

fun engineStatusColor(s: EngineState): Color = when (s) {
    EngineState.RUNNING -> DawColors.Accent
    EngineState.STARTING, EngineState.DISCONNECTED -> DawColors.Warning
    EngineState.ERROR -> DawColors.Error
    else -> DawColors.TextSecondary
}

fun engineStatusLabel(s: EngineState): String = when (s) {
    EngineState.UNINITIALIZED -> "OFFLINE"
    EngineState.STOPPED -> "STOPPED"
    EngineState.STARTING -> "STARTING"
    EngineState.RUNNING -> "RUNNING"
    EngineState.PAUSED -> "PAUSED"
    EngineState.DISCONNECTED -> "RECONNECTING"
    EngineState.ERROR -> "ERROR"
}

/** Flat text button used for secondary actions. */
@Composable
fun DawTextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, accent: Boolean = false) {
    Box(
        modifier.clip(Radius).background(DawColors.Raised).border(1.dp, if (accent && enabled) DawColors.Accent else DawColors.Separator, Radius)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text.uppercase(), style = DawType.Label,
            color = if (!enabled) DawColors.TextDisabled else if (accent) DawColors.Accent else DawColors.TextPrimary)
    }
}

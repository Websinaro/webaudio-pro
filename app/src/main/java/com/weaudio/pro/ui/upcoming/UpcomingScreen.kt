package com.weaudio.pro.ui.upcoming

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.weaudio.pro.ui.theme.DawColors
import com.weaudio.pro.ui.theme.DawType

/** Honest placeholder for destinations that are not built yet: no controls, just status. */
@Composable
fun UpcomingScreen(title: String, phase: Int, description: String) {
    Column(
        Modifier.fillMaxSize().background(DawColors.Background).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Text(title.uppercase(), style = DawType.Label, color = DawColors.TextSecondary)
        Spacer(Modifier.height(8.dp))
        Text("Coming in Phase $phase", color = DawColors.TextPrimary, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(description, color = DawColors.TextSecondary, textAlign = TextAlign.Center,
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
    }
}

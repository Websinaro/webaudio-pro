package com.weaudio.pro.ui.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.weaudio.pro.project.ProjectViewModel
import com.weaudio.pro.ui.components.HairlineDivider
import com.weaudio.pro.ui.components.KeyValueRow
import com.weaudio.pro.ui.components.SectionHeader
import com.weaudio.pro.ui.theme.DawColors

@Composable
fun ProjectsScreen(viewModel: ProjectViewModel) {
    val project by viewModel.project.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().background(DawColors.Background)) {
        SectionHeader("Current project")
        KeyValueRow("Name", project.name)
        HairlineDivider()
        KeyValueRow("Tempo", "%.2f BPM".format(java.util.Locale.US, project.tempoBpm))
        HairlineDivider()
        KeyValueRow("Time signature", project.timeSignature.toString())
        HairlineDivider()
        KeyValueRow("Tracks", project.trackCount.toString())
        HairlineDivider()
        KeyValueRow("Saved", if (project.isSaved) "Yes" else "No")
        Spacer(Modifier.height(16.dp))
        SectionHeader("Project library")
        Text(
            "Saving, opening and browsing projects arrive with the timeline in Phase 3. " +
                "Phase 1 keeps a single in-memory project so the engine and workspace have a project context.",
            color = DawColors.TextSecondary, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(12.dp),
        )
    }
}

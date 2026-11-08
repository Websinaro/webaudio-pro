package com.weaudio.pro

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import com.weaudio.pro.audio.AudioViewModel
import com.weaudio.pro.audio.EngineState
import com.weaudio.pro.navigation.Destination
import com.weaudio.pro.navigation.ROUTE_DIAGNOSTICS
import com.weaudio.pro.project.ProjectViewModel
import com.weaudio.pro.studio.drum.DrumViewModel
import com.weaudio.pro.studio.drum.SoundsScreen
import com.weaudio.pro.ui.components.engineStatusColor
import com.weaudio.pro.ui.diagnostics.DiagnosticsScreen
import com.weaudio.pro.ui.projects.ProjectsScreen
import com.weaudio.pro.ui.studio.StudioScreen
import com.weaudio.pro.ui.theme.DawColors
import com.weaudio.pro.ui.theme.DawType
import com.weaudio.pro.ui.upcoming.UpcomingScreen

@Composable
fun WeAudioApp(audio: AudioViewModel, drums: DrumViewModel) {
    val nav = rememberNavController()
    val projectVm: ProjectViewModel = viewModel()
    val project by projectVm.project.collectAsStateWithLifecycle()
    val route = nav.currentBackStackEntryAsState().value?.destination?.route
    val onDiagnostics = route == ROUTE_DIAGNOSTICS

    BoxWithConstraints(Modifier.fillMaxSize().background(DawColors.Panel).statusBarsPadding().navigationBarsPadding()) {
        // Landscape DAW workspace when the window is wider than tall; phone-style portrait otherwise.
        val landscape = maxWidth > maxHeight
        Row(Modifier.fillMaxSize()) {
            if (landscape && !onDiagnostics) {
                NavRail(route, nav)
                Box(Modifier.width(1.dp).fillMaxHeight().background(DawColors.Separator))
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                if (onDiagnostics) {
                    SimpleTopBar("Audio settings & diagnostics") { nav.popBackStack() }
                } else {
                    StudioTopBar(project.name, project.isSaved, nav, audio)
                }
                Box(Modifier.weight(1f)) {
                    NavHost(nav, startDestination = Destination.Studio.route) {
                        composable(Destination.Studio.route) { StudioScreen(project, audio, drums, landscape) }
                        composable(Destination.Sounds.route) { SoundsScreen(drums) }
                        composable(Destination.Mixer.route) {
                            UpcomingScreen("Mixer", 4, "Channel strips, EQ, reverb, delay and WAV rendering.")
                        }
                        composable(Destination.Projects.route) { ProjectsScreen(projectVm) }
                        composable(ROUTE_DIAGNOSTICS) { DiagnosticsScreen(audio) }
                    }
                }
                if (!onDiagnostics && !landscape) BottomBar(route, nav)
            }
        }
    }
}

/** Landscape navigation: a slim rail replaces the bottom bar so the workspace keeps its height. */
@Composable
private fun NavRail(currentRoute: String?, nav: NavHostController) {
    Column(Modifier.width(64.dp).fillMaxHeight().background(DawColors.Panel), verticalArrangement = Arrangement.Center) {
        Destination.entries.forEach { dest ->
            val selected = currentRoute == dest.route
            Column(
                Modifier.weight(1f).fillMaxWidth().clickable { if (!selected) nav.navigateTop(dest.route) }.testTag("nav_${dest.route}"),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Box(Modifier.width(24.dp).height(2.dp).background(if (selected) DawColors.Accent else DawColors.Panel))
                Spacer(Modifier.height(6.dp))
                Text(dest.label.uppercase(), style = DawType.Label.copy(fontSize = 9.sp), color = if (selected) DawColors.TextPrimary else DawColors.TextSecondary, maxLines = 1)
            }
        }
    }
}

@Composable
private fun StudioTopBar(projectName: String, saved: Boolean, nav: NavHostController, audio: AudioViewModel) {
    val state by audio.state.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(44.dp).background(DawColors.Panel).padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(DawColors.Accent))
        Spacer(Modifier.width(8.dp))
        Text("WE AUDIO PRO", style = DawType.Label.copy(letterSpacing = 1.6.sp), color = DawColors.TextPrimary, maxLines = 1)
        Spacer(Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Text(projectName, color = DawColors.TextPrimary, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(5.dp).background(if (saved) DawColors.Accent else DawColors.Warning))
                Spacer(Modifier.width(4.dp))
                Text(if (saved) "SAVED" else "NOT SAVED", style = DawType.Label, color = DawColors.TextSecondary,
                    modifier = Modifier.testTag("project_status"))
            }
        }
        IconTap(Icons.Default.Settings, "Settings", Modifier.testTag("settings_button")) { nav.navigate(ROUTE_DIAGNOSTICS) { launchSingleTop = true } }
        Box {
            IconTap(Icons.Default.MoreVert, "Project menu", Modifier.testTag("project_menu")) { menu = true }
            DropdownMenu(menu, { menu = false }, Modifier.background(DawColors.Raised)) {
                DropdownMenuItem(text = { Text("Project info") }, onClick = { menu = false; nav.navigateTop(Destination.Projects.route) })
                DropdownMenuItem(text = { Text("Audio diagnostics") }, onClick = { menu = false; nav.navigate(ROUTE_DIAGNOSTICS) })
                DropdownMenuItem(text = { Text("Save project (Phase 3)") }, onClick = {}, enabled = false)
                DropdownMenuItem(text = { Text("Render WAV (Phase 4)") }, onClick = {}, enabled = false)
            }
        }
    }
    // Engine health hairline: accent when running, red on error.
    val line = when (state.engine) {
        EngineState.RUNNING, EngineState.ERROR -> engineStatusColor(state.engine)
        else -> DawColors.Separator
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(line))
}

@Composable
private fun SimpleTopBar(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(44.dp).background(DawColors.Panel), verticalAlignment = Alignment.CenterVertically) {
        IconTap(Icons.AutoMirrored.Filled.ArrowBack, "Back", Modifier.testTag("back_button"), onBack)
        Text(title, color = DawColors.TextPrimary, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(DawColors.Separator))
}

@Composable
private fun IconTap(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.size(44.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, description, tint = DawColors.TextSecondary, modifier = Modifier.size(20.dp))
    }
}

private fun NavHostController.navigateTop(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

@Composable
private fun BottomBar(currentRoute: String?, nav: NavHostController) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(DawColors.Separator))
    Row(Modifier.fillMaxWidth().height(52.dp).background(DawColors.Panel)) {
        Destination.entries.forEach { dest ->
            val selected = currentRoute == dest.route
            Column(
                Modifier.weight(1f).fillMaxHeight().clickable { if (!selected) nav.navigateTop(dest.route) }.testTag("nav_${dest.route}"),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Box(Modifier.width(24.dp).height(2.dp).background(if (selected) DawColors.Accent else DawColors.Panel))
                Spacer(Modifier.height(6.dp))
                Text(dest.label.uppercase(), style = DawType.Label,
                    color = if (selected) DawColors.TextPrimary else DawColors.TextSecondary)
                Text(dest.upcomingPhase?.let { "PHASE $it" } ?: " ", style = DawType.Label.copy(fontSize = 8.sp),
                    color = DawColors.TextDisabled)
            }
        }
    }
}

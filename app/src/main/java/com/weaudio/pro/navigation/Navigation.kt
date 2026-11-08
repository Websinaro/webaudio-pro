package com.weaudio.pro.navigation

enum class Destination(val route: String, val label: String, val upcomingPhase: Int?) {
    Studio("studio", "Studio", null),
    Sounds("sounds", "Sounds", null),
    Mixer("mixer", "Mixer", 4),
    Projects("projects", "Projects", null),
}

const val ROUTE_DIAGNOSTICS = "diagnostics"

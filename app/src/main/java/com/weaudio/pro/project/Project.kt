package com.weaudio.pro.project

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class TimeSignature(val numerator: Int, val denominator: Int) {
    override fun toString() = "$numerator/$denominator"
}

/** Phase 1 project model: metadata only. Tracks/clips arrive in Phase 3. */
data class Project(
    val name: String = "Untitled Project",
    val tempoBpm: Double = 120.0,
    val timeSignature: TimeSignature = TimeSignature(4, 4),
    val trackCount: Int = 0,
    val isSaved: Boolean = false,
)

/** Seam for persistence; Phase 1 keeps the session project in memory only. */
interface ProjectRepository {
    val current: StateFlow<Project>
}

class InMemoryProjectRepository(initial: Project = Project()) : ProjectRepository {
    private val state = MutableStateFlow(initial)
    override val current: StateFlow<Project> = state.asStateFlow()
}

class ProjectViewModel(repository: ProjectRepository = InMemoryProjectRepository()) : ViewModel() {
    val project: StateFlow<Project> = repository.current
}

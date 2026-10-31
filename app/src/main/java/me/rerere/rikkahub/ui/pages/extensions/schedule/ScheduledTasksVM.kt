package me.rerere.rikkahub.ui.pages.extensions.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.ScheduledTask
import me.rerere.rikkahub.data.model.ScheduledTaskTrigger
import me.rerere.rikkahub.data.schedule.ScheduledTaskRunner

class ScheduledTasksVM(
    private val settingsStore: SettingsStore,
    private val runner: ScheduledTaskRunner,
) : ViewModel() {

    val tasks = settingsStore.settingsFlow
        .map { it.scheduledTasks }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    fun addTask(name: String, prompt: String, trigger: ScheduledTaskTrigger, hour: Int, minute: Int) {
        if (prompt.isBlank()) return
        viewModelScope.launch {
            val assistantId = settingsStore.settingsFlow.value.getCurrentAssistant().id.toString()
            val task = ScheduledTask(
                name = name.trim().ifBlank { "主动任务" },
                prompt = prompt.trim(),
                assistantId = assistantId,
                trigger = trigger,
                hour = hour.coerceIn(0, 23),
                minute = minute.coerceIn(0, 59),
            )
            settingsStore.update { it.copy(scheduledTasks = it.scheduledTasks + task) }
            _message.value = "已添加任务：${task.name}"
        }
    }

    fun toggleTask(id: String, enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.update { settings ->
                settings.copy(
                    scheduledTasks = settings.scheduledTasks.map {
                        if (it.id == id) it.copy(enabled = enabled) else it
                    }
                )
            }
        }
    }

    fun deleteTask(id: String) {
        viewModelScope.launch {
            settingsStore.update { settings ->
                settings.copy(scheduledTasks = settings.scheduledTasks.filterNot { it.id == id })
            }
        }
    }

    fun runNow(task: ScheduledTask) {
        viewModelScope.launch {
            val id = runCatching { runner.runTask(task) }.getOrNull()
            _message.value = if (id != null) "已在新的会话中执行：${task.name}" else "执行失败"
        }
    }

    fun consumeMessage() {
        _message.value = null
    }
}

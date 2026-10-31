package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
enum class ScheduledTaskTrigger {
    /** Every day at hour:minute on selected weekdays. */
    TIME,

    /** When the phone starts charging. */
    CHARGING,

    /** When battery runs low. */
    BATTERY_LOW,
}

/**
 * A proactive task: when the trigger fires, the prompt is sent to the chosen
 * assistant in a fresh conversation, so the AI works without user input.
 */
@Serializable
data class ScheduledTask(
    val id: String = Uuid.random().toString(),
    val name: String,
    val prompt: String,
    val assistantId: String,
    val trigger: ScheduledTaskTrigger = ScheduledTaskTrigger.TIME,
    val hour: Int = 9,
    val minute: Int = 0,
    /** ISO day-of-week numbers, 1 = Monday ... 7 = Sunday. Only used by TIME. */
    val daysOfWeek: Set<Int> = setOf(1, 2, 3, 4, 5, 6, 7),
    val enabled: Boolean = true,
    val lastRunAt: Long = 0L,
)

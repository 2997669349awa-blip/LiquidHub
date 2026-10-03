package me.rerere.rikkahub.web.routes

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.web.dto.SkillDto

/**
 * GET /api/skills
 * 返回磁盘上的全部 skill，并标记当前助手是否已启用，供网页输入框的 skill 选择器使用。
 */
fun Route.skillRoutes(skillManager: SkillManager, settingsStore: SettingsStore) {
    get("/skills") {
        val settings = settingsStore.settingsFlow.value
        val enabled = settings.getCurrentAssistant().enabledSkills
        val skills = skillManager.listSkills().map { skill ->
            SkillDto(
                name = skill.name,
                description = skill.description,
                enabled = skill.name in enabled,
            )
        }
        call.respond(skills)
    }
}

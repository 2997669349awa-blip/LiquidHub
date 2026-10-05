package me.rerere.rikkahub.web.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.web.BadRequestException
import me.rerere.rikkahub.web.NotFoundException
import me.rerere.rikkahub.web.dto.AssistantDto
import me.rerere.rikkahub.web.dto.CreateAssistantRequest
import me.rerere.rikkahub.web.dto.FileItemDto
import me.rerere.rikkahub.web.dto.GeneralSettingsDto
import me.rerere.rikkahub.web.dto.ModelDto
import me.rerere.rikkahub.web.dto.ProviderDto
import me.rerere.rikkahub.web.dto.SaveSkillRequest
import me.rerere.rikkahub.web.dto.SkillDetailDto
import me.rerere.rikkahub.web.dto.UpdateAssistantFieldsRequest
import me.rerere.rikkahub.web.dto.UpdateGeneralSettingsRequest
import me.rerere.rikkahub.web.dto.UpdateProviderRequest
import kotlin.uuid.Uuid

/**
 * Management APIs for the web control-center: assistants, providers, general settings,
 * skills editor and the managed-files browser.
 */
fun Route.manageRoutes(
    settingsStore: SettingsStore,
    skillManager: SkillManager,
    filesManager: FilesManager,
) {
    route("/manage") {
        // ---------- Assistants ----------
        get("/assistants") {
            call.respond(settingsStore.settingsFlow.value.assistants.map { it.toManageDto() })
        }

        post("/assistants") {
            val request = call.receive<CreateAssistantRequest>()
            val name = request.name.trim()
            if (name.isEmpty()) throw BadRequestException("name is required")
            val created = Assistant(name = name)
            settingsStore.update { it.copy(assistants = it.assistants + created) }
            call.respond(HttpStatusCode.Created, created.toManageDto())
        }

        post("/assistants/{id}") {
            val id = call.parameters["id"].toUuid("id")
            val request = call.receive<UpdateAssistantFieldsRequest>()
            var updated: Assistant? = null
            settingsStore.update { settings ->
                val existing = settings.assistants.firstOrNull { it.id == id }
                    ?: throw NotFoundException("Assistant not found")

                val chatModelId = request.chatModelId?.let { raw ->
                    val modelId = raw.toUuid("chatModelId")
                    settings.findModelById(modelId) ?: throw BadRequestException("Model not found")
                }
                val reasoning = request.reasoningLevel?.let { raw ->
                    runCatching { ReasoningLevel.valueOf(raw.trim().uppercase()) }.getOrNull()
                        ?: throw BadRequestException("Invalid reasoningLevel")
                }

                val merged = existing.copy(
                    name = request.name?.trim()?.takeIf { it.isNotEmpty() } ?: existing.name,
                    systemPrompt = request.systemPrompt ?: existing.systemPrompt,
                    chatModelId = if (request.chatModelId != null) chatModelId?.id else existing.chatModelId,
                    temperature = request.temperature ?: existing.temperature,
                    topP = request.topP ?: existing.topP,
                    contextMessageLimit = request.contextMessageLimit ?: existing.contextMessageLimit,
                    streamOutput = request.streamOutput ?: existing.streamOutput,
                    enableMemory = request.enableMemory ?: existing.enableMemory,
                    useGlobalMemory = request.useGlobalMemory ?: existing.useGlobalMemory,
                    enableWebSearch = request.enableWebSearch ?: existing.enableWebSearch,
                    enableRecentChatsReference = request.enableRecentChatsReference
                        ?: existing.enableRecentChatsReference,
                    maxTokens = request.maxTokens ?: existing.maxTokens,
                    reasoningLevel = reasoning ?: existing.reasoningLevel,
                    useGradientBackground = request.useGradientBackground ?: existing.useGradientBackground,
                    workspaceId = if (request.workspaceId != null) request.workspaceId.toUuidOrNull() else existing.workspaceId,
                )
                updated = merged
                settings.copy(assistants = settings.assistants.map { if (it.id == id) merged else it })
            }
            call.respond(updated!!.toManageDto())
        }

        post("/assistants/{id}/clone") {
            val id = call.parameters["id"].toUuid("id")
            var created: Assistant? = null
            settingsStore.update { settings ->
                val source = settings.assistants.firstOrNull { it.id == id }
                    ?: throw NotFoundException("Assistant not found")
                val clone = source.copy(id = Uuid.random(), name = source.name + " 副本")
                created = clone
                settings.copy(assistants = settings.assistants + clone)
            }
            call.respond(HttpStatusCode.Created, created!!.toManageDto())
        }

        delete("/assistants/{id}") {
            val id = call.parameters["id"].toUuid("id")
            settingsStore.update { settings ->
                if (settings.assistants.none { it.id == id }) {
                    throw NotFoundException("Assistant not found")
                }
                if (settings.assistants.size <= 1) {
                    throw BadRequestException("Cannot delete the last assistant")
                }
                val remaining = settings.assistants.filter { it.id != id }
                settings.copy(
                    assistants = remaining,
                    assistantId = if (settings.assistantId == id) remaining.first().id else settings.assistantId,
                )
            }
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }

        // ---------- Providers ----------
        get("/providers") {
            call.respond(settingsStore.settingsFlow.value.providers.map { it.toManageDto() })
        }

        post("/providers/{id}") {
            val id = call.parameters["id"].toUuid("id")
            val request = call.receive<UpdateProviderRequest>()
            settingsStore.update { settings ->
                val existing = settings.providers.firstOrNull { it.id == id }
                    ?: throw NotFoundException("Provider not found")
                val updated: ProviderSetting = when (existing) {
                    is ProviderSetting.OpenAI -> existing.copy(
                        name = request.name ?: existing.name,
                        enabled = request.enabled ?: existing.enabled,
                        apiKey = request.apiKey ?: existing.apiKey,
                        baseUrl = request.baseUrl ?: existing.baseUrl,
                    )

                    is ProviderSetting.Google -> existing.copy(
                        name = request.name ?: existing.name,
                        enabled = request.enabled ?: existing.enabled,
                        apiKey = request.apiKey ?: existing.apiKey,
                        baseUrl = request.baseUrl ?: existing.baseUrl,
                    )

                    is ProviderSetting.Claude -> existing.copy(
                        name = request.name ?: existing.name,
                        enabled = request.enabled ?: existing.enabled,
                        apiKey = request.apiKey ?: existing.apiKey,
                        baseUrl = request.baseUrl ?: existing.baseUrl,
                    )
                }
                settings.copy(providers = settings.providers.map { if (it.id == id) updated else it })
            }
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }

        // ---------- General settings ----------
        get("/settings") {
            call.respond(settingsStore.settingsFlow.value.toManageDto())
        }

        post("/settings") {
            val request = call.receive<UpdateGeneralSettingsRequest>()
            settingsStore.update { settings ->
                settings.copy(
                    dynamicColor = request.dynamicColor ?: settings.dynamicColor,
                    themeId = request.themeId ?: settings.themeId,
                    developerMode = request.developerMode ?: settings.developerMode,
                    enableSuggestion = request.enableSuggestion ?: settings.enableSuggestion,
                    chatModelId = request.chatModelId?.toUuid("chatModelId") ?: settings.chatModelId,
                    fastModelId = request.fastModelId?.toUuid("fastModelId") ?: settings.fastModelId,
                    imageGenerationModelId = request.imageGenerationModelId?.toUuid("imageGenerationModelId")
                        ?: settings.imageGenerationModelId,
                    translateModeId = request.translateModeId?.toUuid("translateModeId") ?: settings.translateModeId,
                    ocrModelId = request.ocrModelId?.toUuid("ocrModelId") ?: settings.ocrModelId,
                    compressModelId = request.compressModelId?.toUuid("compressModelId") ?: settings.compressModelId,
                )
            }
            call.respond(HttpStatusCode.OK, settingsStore.settingsFlow.value.toManageDto())
        }

        // ---------- Skills ----------
        get("/skills/{name}") {
            val name = call.parameters["name"] ?: throw BadRequestException("Missing name")
            val content = skillManager.readSkillContent(name) ?: throw NotFoundException("Skill not found")
            val meta = skillManager.listSkills().firstOrNull { it.name == name }
            val assistant = settingsStore.settingsFlow.value.assistants
                .firstOrNull { it.id == settingsStore.settingsFlow.value.assistantId }
            call.respond(
                SkillDetailDto(
                    name = name,
                    description = meta?.description.orEmpty(),
                    enabled = assistant?.enabledSkills?.contains(name) == true,
                    content = content,
                )
            )
        }

        post("/skills") {
            val request = call.receive<SaveSkillRequest>()
            if (request.name.isBlank()) throw BadRequestException("name is required")
            val saved = skillManager.saveSkill(request.name.trim(), request.content)
                ?: throw BadRequestException("Invalid skill content (name/description required in frontmatter)")
            call.respond(SkillDetailDto(saved.name, saved.description, false, request.content))
        }

        delete("/skills/{name}") {
            val name = call.parameters["name"] ?: throw BadRequestException("Missing name")
            if (!skillManager.deleteSkill(name)) throw NotFoundException("Skill not found")
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }

        // ---------- Files ----------
        get("/files") {
            val folder = call.request.queryParameters["folder"]?.takeIf { it.isNotBlank() }
                ?: FileFolders.UPLOAD
            call.respond(filesManager.list(folder).map { it.toManageDto() })
        }
    }
}

private fun Assistant.toManageDto() = AssistantDto(
    id = id.toString(),
    name = name,
    systemPrompt = systemPrompt,
    chatModelId = chatModelId?.toString(),
    temperature = temperature,
    topP = topP,
    contextMessageLimit = contextMessageLimit,
    streamOutput = streamOutput,
    enableMemory = enableMemory,
    useGlobalMemory = useGlobalMemory,
    enableWebSearch = enableWebSearch,
    enableRecentChatsReference = enableRecentChatsReference,
    maxTokens = maxTokens,
    reasoningLevel = reasoningLevel.name,
    useGradientBackground = useGradientBackground,
    workspaceId = workspaceId?.toString(),
)

private fun Model.toManageDto() = ModelDto(
    id = id.toString(),
    modelId = modelId,
    displayName = displayName,
    type = type.name,
    tools = tools.map { tool ->
        when (tool) {
            is BuiltInTools.Search -> "search"
            is BuiltInTools.UrlContext -> "url_context"
            is BuiltInTools.ImageGeneration -> "image_generation"
        }
    },
)

private fun ProviderSetting.toManageDto(): ProviderDto {
    val type = when (this) {
        is ProviderSetting.OpenAI -> "openai"
        is ProviderSetting.Google -> "google"
        is ProviderSetting.Claude -> "claude"
    }
    val key = when (this) {
        is ProviderSetting.OpenAI -> apiKey
        is ProviderSetting.Google -> apiKey
        is ProviderSetting.Claude -> apiKey
    }
    val url = when (this) {
        is ProviderSetting.OpenAI -> baseUrl
        is ProviderSetting.Google -> baseUrl
        is ProviderSetting.Claude -> baseUrl
    }
    return ProviderDto(
        id = id.toString(),
        name = name,
        enabled = enabled,
        builtIn = builtIn,
        type = type,
        apiKey = key,
        baseUrl = url,
        models = models.map { it.toManageDto() },
    )
}

private fun Settings.toManageDto() = GeneralSettingsDto(
    dynamicColor = dynamicColor,
    themeId = themeId,
    developerMode = developerMode,
    enableSuggestion = enableSuggestion,
    chatModelId = chatModelId.toString(),
    fastModelId = fastModelId.toString(),
    imageGenerationModelId = imageGenerationModelId.toString(),
    translateModeId = translateModeId.toString(),
    ocrModelId = ocrModelId.toString(),
    compressModelId = compressModelId.toString(),
    webServerEnabled = webServerEnabled,
    webServerPort = webServerPort,
    webServerJwtEnabled = webServerJwtEnabled,
    webServerLocalhostOnly = webServerLocalhostOnly,
)

private fun ManagedFileEntity.toManageDto() = FileItemDto(
    id = id,
    folder = folder,
    displayName = displayName,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
    relativePath = relativePath,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun String.toUuidOrNull(): Uuid? =
    runCatching { Uuid.parse(this) }.getOrNull()

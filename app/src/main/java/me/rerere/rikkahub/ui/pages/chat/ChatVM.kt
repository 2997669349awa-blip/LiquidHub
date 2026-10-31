package me.rerere.rikkahub.ui.pages.chat

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.analytics.FirebaseAnalytics
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.NodeFavoriteTarget
import me.rerere.rikkahub.data.model.getAssistantOf
import me.rerere.rikkahub.data.model.getChatModelOf
import me.rerere.rikkahub.data.repository.FavoriteRepository
import me.rerere.rikkahub.service.ChatError
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.ui.hooks.writeStringPreference
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.utils.UiState
import me.rerere.rikkahub.utils.UpdateChecker
import me.rerere.rikkahub.web.ApiException
import java.util.Locale
import kotlin.uuid.Uuid

private const val TAG = "ChatVM"

class ChatVM(
    id: String,
    private val context: Application,
    private val settingsStore: SettingsStore,
    private val chatService: ChatService,
    val updateChecker: UpdateChecker,
    private val analytics: FirebaseAnalytics,
    private val filesManager: FilesManager,
    private val favoriteRepository: FavoriteRepository,
    private val backgroundTaskManager: me.rerere.rikkahub.service.BackgroundTaskManager,
) : ViewModel() {
    private val _conversationId: Uuid = Uuid.parse(id)
    val conversation: StateFlow<Conversation> = chatService.getConversationFlow(_conversationId)
    var chatListInitialized by mutableStateOf(false) // 聊天列表是否已经滚动到底部

    // 聊天输入状态 - 保存在 ViewModel 中避免 TransactionTooLargeException
    val inputState = ChatInputState()

    val voiceSession = VoiceSessionController(viewModelScope, context::getString) {
        chatService.enqueueVoiceMessage(_conversationId, it)
    }

    // 异步任务 (从ChatService获取，响应式)
    val conversationJob: StateFlow<Job?> =
        chatService
            .getGenerationJobStateFlow(_conversationId)
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val processingStatus: StateFlow<String?> =
        chatService
            .getProcessingStatusFlow(_conversationId)

    val conversationJobs = chatService
        .getConversationJobs()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    init {
        // 添加对话引用
        chatService.addConversationReference(_conversationId)

        // 初始化对话
        viewModelScope.launch {
            chatService.initializeConversation(_conversationId)
        }

        // 记住对话ID, 方便下次启动恢复
        context.writeStringPreference("lastConversationId", _conversationId.toString())
    }

    override fun onCleared() {
        voiceSession.stop()
        super.onCleared()
        // 移除对话引用
        chatService.removeConversationReference(_conversationId)
    }

    // 用户设置
    val settings: StateFlow<Settings> =
        settingsStore.settingsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    // 流式输出时会话每个 chunk 都在变，助手和模型只取决于下面这几个字段
    private val configuredConversation = conversation.distinctUntilChanged { old, new ->
        old.assistantId == new.assistantId && old.config == new.config &&
            old.modeInjectionIds == new.modeInjectionIds && old.lorebookIds == new.lorebookIds
    }

    // 会话视角下的助手：会话开始后，模型、思考级别、搜索等以会话上固定的配置为准
    val assistant: StateFlow<Assistant> = combine(settings, configuredConversation) { settings, conversation ->
        settings.getAssistantOf(conversation)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, settings.value.getAssistantOf(conversation.value))

    // 当前模型
    val currentChatModel: StateFlow<Model?> = combine(settings, configuredConversation) { settings, conversation ->
        settings.getChatModelOf(conversation)
    }.stateIn(viewModelScope, SharingStarted.Lazily, settings.value.getChatModelOf(conversation.value))

    // 错误状态
    val errors: StateFlow<List<ChatError>> = chatService.errors

    fun dismissError(id: Uuid) = chatService.dismissError(id)

    fun clearAllErrors() = chatService.clearAllErrors()

    val messageQueue = chatService.getMessageQueueFlow(_conversationId)

    fun removeQueuedMessage(id: Uuid) = chatService.removeQueuedMessage(_conversationId, id)

    fun beginEditQueuedMessage(id: Uuid) = chatService.beginEditQueuedMessage(_conversationId, id)

    fun finishEditQueuedMessage(id: Uuid, parts: List<UIMessagePart>?) =
        chatService.finishEditQueuedMessage(_conversationId, id, parts)

    fun resumeMessageQueue() = chatService.resumeMessageQueue(_conversationId)

    // 生成完成
    val generationDoneFlow: SharedFlow<Uuid> = chatService.generationDoneFlow

    // MCP管理器
    val mcpManager = chatService.mcpManager

    // 更新设置
    fun updateSettings(fn: (Settings) -> Settings): Job {
        return viewModelScope.launch {
            var oldSettings: Settings? = null
            var newSettings: Settings? = null
            settingsStore.update { old ->
                fn(old).also { new ->
                    oldSettings = old
                    newSettings = new
                }
            }
            // 检查用户头像是否有变化，如果有则删除旧头像
            checkUserAvatarDelete(oldSettings ?: return@launch, newSettings ?: return@launch)
        }
    }

    // 检查用户头像删除
    private fun checkUserAvatarDelete(oldSettings: Settings, newSettings: Settings) {
        val oldAvatar = oldSettings.displaySetting.userAvatar
        val newAvatar = newSettings.displaySetting.userAvatar

        if (oldAvatar is Avatar.Image && oldAvatar != newAvatar) {
            filesManager.deleteChatFiles(listOf(oldAvatar.url.toUri()))
        }
    }

    // 聊天页对助手的修改：新会话直接改助手，会话开始后只改当前会话
    fun updateAssistant(assistant: Assistant) {
        viewModelScope.launch {
            chatService.updateChatAssistant(_conversationId) { assistant }
        }
    }

    // 新会话开始前归入抽屉里选中的文件夹
    suspend fun updateDraftFolder(folderId: Uuid?) {
        chatService.updateDraftConversationFolder(_conversationId, folderId)
    }

    // 设置聊天模型
    fun setChatModel(model: Model) {
        viewModelScope.launch {
            chatService.updateChatAssistant(_conversationId) { it.copy(chatModelId = model.id) }
        }
    }

    // 切换搜索方式
    fun updateSearch(enableWebSearch: Boolean, builtInSearch: Boolean) {
        viewModelScope.launch {
            chatService.updateChatSearch(_conversationId, enableWebSearch, builtInSearch)
        }
    }

    // Update checker
    val updateState = settingsStore.settingsFlow
        .map { settings ->
            !BuildConfig.IS_PLAY_STORE &&
                !settings.init &&
                settings.displaySetting.updateCheckDisabledUntilEpochMillis <= System.currentTimeMillis()
        }
        .distinctUntilChanged()
        .flatMapLatest { enabled ->
            if (enabled) updateChecker.updateState else flowOf(UiState.Loading)
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
            UiState.Loading,
        )

    /**
     * 处理消息发送
     *
     * @param content 消息内容
     * @param answer 是否触发消息生成，如果为false，则仅添加消息到消息列表中
     */
    fun handleMessageSend(content: List<UIMessagePart>,answer: Boolean = true) {
        if (content.isEmptyInputMessage()) return
        analytics.logEvent("ai_send_message", null)

        chatService.sendMessage(_conversationId, content, answer)
    }

    private val _commandResult = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val commandResult = _commandResult.asStateFlow()

    fun dismissCommandResult() {
        _commandResult.value = null
    }

    /** 执行输入框命令（以 // 开头）。结果通过 [commandResult] 展示，不会发给 AI。 */
    fun runCommand(raw: String) {
        _commandResult.value = executeCommand(raw)
    }

    private fun executeCommand(raw: String): String {
        val cmd = raw.removePrefix("//").trim()
        val lower = cmd.lowercase()
        return when {
            lower.isEmpty() || lower == "help" -> me.rerere.rikkahub.utils.ChatCommandSpecs.helpText()

            lower == "tasks" -> {
                val tasks = me.rerere.rikkahub.service.ChatTaskManager.activeTasks()
                val background = backgroundTaskManager.list()
                val lines = buildList {
                    tasks.forEach { add("${it.id}  ·  生成  ·  会话 ${it.conversationId.toString().take(8)}") }
                    background.forEach {
                        add("${it.id}  ·  后台  ·  ${it.state.name.lowercase()}  ·  ${it.command.take(40)}")
                    }
                }
                if (lines.isEmpty()) "当前没有进行中的任务" else lines.joinToString("\n")
            }

            lower == "killall" -> {
                val generation = me.rerere.rikkahub.service.ChatTaskManager.killAll()
                val background = backgroundTaskManager.killAll()
                "已请求停止 $generation 个生成任务、$background 个后台任务"
            }

            lower == "kill" -> {
                me.rerere.rikkahub.service.ChatTaskManager.markCutOff(_conversationId.toString())
                me.rerere.rikkahub.service.ChatTaskManager.killByArg(_conversationId.toString())
                stopGeneration()
                me.rerere.rikkahub.data.phone.PhoneControlManager.stopAll()
                "已彻底终止当前会话：生成、后台任务与手机控制均已切断"
            }

            lower.startsWith("kill-") || lower.startsWith("kill ") -> {
                val id = me.rerere.rikkahub.utils.parseChatCommandArg(cmd)
                when {
                    id.isBlank() -> "用法：//kill <任务ID 或 会话ID>（用 //tasks 查看）"
                    me.rerere.rikkahub.service.ChatTaskManager.killByArg(id) -> "已请求停止生成任务：$id"
                    backgroundTaskManager.kill(id) -> "已停止后台任务：$id"
                    else -> "未找到进行中的任务：$id（用 //tasks 查看可用 ID）"
                }
            }

            lower == "bg" || lower.startsWith("bg-") || lower.startsWith("bg ") -> {
                val arg = me.rerere.rikkahub.utils.parseChatCommandArg(cmd)
                val target = if (arg.isBlank()) backgroundTaskManager.list().lastOrNull()
                else backgroundTaskManager.find(arg)
                when {
                    target == null -> "没有可转后台的任务（用 //tasks 查看）"
                    !target.isRunning -> "任务 ${target.id} 已结束，可用 //logs ${target.id} 查看输出"
                    backgroundTaskManager.detach(target.id) -> {
                        runCatching { me.rerere.rikkahub.service.WorkspaceTaskService.sync(context) }
                        "任务 ${target.id} 已转入后台，进度见通知栏；用 //logs ${target.id} 查看输出"
                    }
                    else -> "转后台失败：${target.id}"
                }
            }

            lower.startsWith("logs-") || lower.startsWith("logs ") -> {
                val id = me.rerere.rikkahub.utils.parseChatCommandArg(cmd)
                if (id.isBlank()) {
                    "用法：//logs <任务ID>（用 //tasks 查看）"
                } else {
                    val task = backgroundTaskManager.find(id)
                    if (task == null) {
                        "未找到任务：$id（可用：${backgroundTaskManager.list().joinToString(", ") { it.id }.ifBlank { "无" }}）"
                    } else {
                        val output = backgroundTaskManager.readOutput(id, 50).orEmpty()
                        buildString {
                            appendLine("${task.id} · ${task.state.name.lowercase()} · ${task.command}")
                            appendLine("—".repeat(20))
                            append(output.ifBlank { "(暂无输出)" })
                        }.trim()
                    }
                }
            }

            lower.startsWith("recover-") || lower.startsWith("recover ") -> {
                val id = me.rerere.rikkahub.utils.parseChatCommandArg(cmd)
                val task = if (id.isBlank()) null
                else me.rerere.rikkahub.service.ChatTaskManager.findByArg(id)
                if (task == null) {
                    "未找到任务：$id（用 //tasks 查看可用 ID）"
                } else {
                    chatService.recoverTask(task.conversationId)
                    "已尝试恢复任务 ${task.id}"
                }
            }

            lower == "stop" -> {
                stopGeneration()
                "已停止当前会话的生成"
            }

            lower == "status" -> buildString {
                append("LiquidHub ${me.rerere.rikkahub.BuildConfig.APP_VERSION} (${me.rerere.rikkahub.BuildConfig.VERSION_NAME})\n")
                append("进行中的任务：${me.rerere.rikkahub.service.ChatTaskManager.activeTasks().size}\n")
                append("后台任务：${backgroundTaskManager.list().count { it.isRunning }}\n")
                append("手机控制：${if (me.rerere.rikkahub.data.phone.PhoneControlManager.active.value) "开启" else "关闭"}")
            }

            else -> "未知命令：$cmd\n（输入 //help 查看全部命令）"
        }
    }

    fun handleMessageEdit(parts: List<UIMessagePart>, messageId: Uuid) {
        if (parts.isEmptyInputMessage()) return
        analytics.logEvent("ai_edit_message", null)

        viewModelScope.launch {
            chatService.editMessage(_conversationId, messageId, parts)
        }
    }

    fun handleCompressContext(additionalPrompt: String, keepRecentMessages: Int): Job {
        return viewModelScope.launch {
            chatService.compressConversation(_conversationId, additionalPrompt, keepRecentMessages).onFailure {
                chatService.addError(it, title = context.getString(R.string.error_title_compress_conversation))
            }
        }
    }

    suspend fun forkMessage(message: UIMessage): Conversation {
        return chatService.forkConversationAtMessage(_conversationId, message.id)
    }

    fun deleteMessage(message: UIMessage) {
        viewModelScope.launch {
            chatService.deleteMessage(_conversationId, message)
        }
    }

    fun showDeleteBlockedWhileGeneratingError() {
        chatService.addError(
            error = IllegalStateException("请先停止生成再删除消息"),
            conversationId = _conversationId,
            title = context.getString(R.string.error_title_operation)
        )
    }

    fun regenerateAtMessage(message: UIMessage) {
        analytics.logEvent("ai_regenerate_at_message", null)
        chatService.regenerateAtMessage(_conversationId, message.id)
    }

    fun handleToolApproval(
        toolCallId: String,
        approved: Boolean,
        reason: String = ""
    ) {
        analytics.logEvent("ai_tool_approval", null)
        chatService.handleToolApproval(_conversationId, toolCallId, approved, reason)
    }

    fun handleToolAnswer(
        toolCallId: String,
        answer: String,
    ) {
        analytics.logEvent("ai_tool_answer", null)
        chatService.handleToolApproval(_conversationId, toolCallId, approved = true, answer = answer)
    }

    fun stopGeneration() {
        viewModelScope.launch {
            chatService.stopGeneration(_conversationId)
        }
    }

    fun updateTitle(title: String) {
        viewModelScope.launch {
            chatService.updateTitle(_conversationId, title)
        }
    }

    fun deleteConversation(conversation: Conversation): Job =
        viewModelScope.launch {
            chatService.deleteConversation(conversation.id)
        }

    fun updatePinnedStatus(conversation: Conversation) {
        viewModelScope.launch {
            chatService.toggleConversationPinned(conversation.id)
        }
    }

    fun moveConversationToAssistant(conversation: Conversation, targetAssistantId: Uuid) {
        viewModelScope.launch {
            chatService.moveConversationToAssistant(conversation.id, targetAssistantId)
            if (conversation.id == _conversationId) {
                settingsStore.selectAssistant(targetAssistantId)
            }
        }
    }

    fun translateMessage(message: UIMessage, targetLanguage: Locale) {
        chatService.translateMessage(_conversationId, message, targetLanguage)
    }

    fun generateTitle(conversation: Conversation, force: Boolean = false) {
        viewModelScope.launch {
            chatService.generateTitle(conversation.id, force)
        }
    }

    fun clearTranslationField(messageId: Uuid) {
        viewModelScope.launch {
            chatService.clearTranslationField(_conversationId, messageId)
        }
    }

    fun selectMessageNode(nodeId: Uuid, selectIndex: Int) {
        viewModelScope.launch {
            try {
                chatService.selectMessageNode(_conversationId, nodeId, selectIndex)
            } catch (e: ApiException) {
                // 界面上的分支已经过时（比如刚被清理掉），切换不了就算了
                Log.w(TAG, "selectMessageNode: ${e.message}")
            }
        }
    }

    fun updateConversationSystemPrompt(systemPrompt: String?) {
        viewModelScope.launch {
            chatService.updateConversationSystemPrompt(_conversationId, systemPrompt)
        }
    }

    fun updateWorkspaceCwd(workspaceCwd: String?) {
        viewModelScope.launch {
            chatService.updateWorkspaceCwd(_conversationId, workspaceCwd)
        }
    }

    fun toggleMessageFavorite(node: MessageNode) {
        viewModelScope.launch {
            val currentlyFavorited = favoriteRepository.isNodeFavorited(_conversationId, node.id)
            if (currentlyFavorited) {
                favoriteRepository.removeNodeFavorite(_conversationId, node.id)
            } else {
                favoriteRepository.addNodeFavorite(
                    NodeFavoriteTarget(
                        conversationId = _conversationId,
                        conversationTitle = conversation.value.title,
                        nodeId = node.id,
                        node = node
                    )
                )
            }

            chatService.setNodeFavorite(_conversationId, node.id, favorite = !currentlyFavorited)
        }
    }

}

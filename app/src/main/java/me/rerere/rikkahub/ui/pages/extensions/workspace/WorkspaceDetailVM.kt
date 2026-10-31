package me.rerere.rikkahub.ui.pages.extensions.workspace

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.workspace.TemplateInstallNotifier
import me.rerere.workspace.TemplateApplyProgress
import me.rerere.workspace.TemplateApplyResult
import me.rerere.workspace.TemplateScript
import me.rerere.workspace.WorkspaceTemplate
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.RootfsInstallStage
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceLayout
import me.rerere.workspace.WorkspaceStorageArea
import java.io.File
import java.io.InputStream

class WorkspaceDetailVM(
    private val id: String,
    private val repository: WorkspaceRepository,
    private val terminalSessionManager: WorkspaceTerminalSessionManager,
) : ViewModel() {
    private val _state = MutableStateFlow(WorkspaceDetailState())
    val state = _state.asStateFlow()

    private var pendingExport: Pair<WorkspaceStorageArea, List<WorkspaceFileEntry>>? = null

    fun prepareBatchExport(entries: List<WorkspaceFileEntry>): Boolean {
        val files = entries.filterNot { it.isDirectory }
        if (pendingExport != null || state.value.exporting || files.isEmpty()) return false
        pendingExport = state.value.area to files
        return true
    }

    fun dismissExportResult() {
        _state.update { it.copy(exportResult = null) }
    }

    fun exportFilesToDirectory(treeUri: Uri?, resolver: ContentResolver) {
        val (area, entries) = pendingExport.also { pendingExport = null } ?: return
        if (treeUri == null) return
        _state.update {
            it.copy(
                exporting = true,
                exportCompleted = 0,
                exportTotal = entries.size,
                exportResult = null
            )
        }
        viewModelScope.launch {
            var succeeded = 0
            val failures = mutableListOf<String>()
            try {
                withContext(Dispatchers.IO) {
                    val parent = DocumentsContract.buildDocumentUriUsingTree(
                        treeUri,
                        DocumentsContract.getTreeDocumentId(treeUri)
                    )
                    entries.forEachIndexed { index, entry ->
                        ensureActive()
                        var destination: Uri? = null
                        try {
                            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                                entry.name.substringAfterLast('.', "").lowercase()
                            ) ?: "application/octet-stream"
                            val document = DocumentsContract.createDocument(resolver, parent, mime, entry.name)
                                ?: error("无法创建目标文件")
                            destination = document
                            val output = resolver.openOutputStream(document) ?: error("无法打开目标文件")
                            output.use { repository.exportFile(id, area, entry.path, it) }
                            succeeded++
                            destination = null
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            failures += "${entry.name}：${error.message ?: "导出失败"}"
                        } finally {
                            // 只清理本次创建但未完整写入的文件。
                            destination?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
                        }
                        _state.update { it.copy(exportCompleted = index + 1) }
                    }
                }
                _state.update {
                    it.copy(exportResult = buildString {
                        append("已导出 $succeeded/${entries.size} 个文件")
                        if (failures.isNotEmpty()) append("\n\n" + failures.joinToString("\n"))
                    })
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(exportResult = "导出失败：${error.message}") }
            } finally {
                _state.update { it.copy(exporting = false) }
            }
        }
    }

    private val _terminalState = MutableStateFlow(WorkspaceTerminalState())
    val terminalState = _terminalState.asStateFlow()

    private val _installProgress = MutableStateFlow<RootfsInstallProgress?>(null)
    val installProgress = _installProgress.asStateFlow()

    private val _installError = MutableStateFlow<String?>(null)
    val installError = _installError.asStateFlow()

    private val _templateProgress = MutableStateFlow<TemplateApplyProgress?>(null)
    val templateProgress = _templateProgress.asStateFlow()

    private val _templateError = MutableStateFlow<String?>(null)
    val templateError = _templateError.asStateFlow()

    private val _applyingTemplate = MutableStateFlow(false)
    val applyingTemplate = _applyingTemplate.asStateFlow()

    private val _templateMessage = MutableStateFlow<String?>(null)
    val templateMessage = _templateMessage.asStateFlow()

    /** 用户自定义环境模板（来自 Settings）。 */
    val customTemplates = repository.customTemplatesFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 应用环境模板：rootfs 缺失时先装 rootfs，然后顺序执行模板脚本。
     * 进度同时推送到页面与通知栏。
     */
    fun applyTemplate(
        template: WorkspaceTemplate,
        rootfsUrl: String?,
        context: Context,
    ) {
        if (_applyingTemplate.value) return
        viewModelScope.launch {
            val workspace = state.value.workspace ?: return@launch
            _installError.value = null
            _templateError.value = null
            _applyingTemplate.value = true
            TemplateInstallNotifier.start(context, workspace.id, workspace.name, template.name)
            try {
                terminalSessionManager.closeWorkspace(workspace.root)
                val result = repository.applyTemplate(
                    id = workspace.id,
                    template = template,
                    rootfsUrl = rootfsUrl,
                    onRootfsProgress = { _installProgress.value = it },
                    onScriptProgress = { progress ->
                        _templateProgress.value = progress
                        TemplateInstallNotifier.progress(context, workspace.id, workspace.name, progress)
                    },
                )
                when (result) {
                    TemplateApplyResult.Success -> {
                        TemplateInstallNotifier.success(context, workspace.id, template.name)
                        _templateMessage.value = "模板「${template.name}」配置完成"
                    }

                    is TemplateApplyResult.Failed -> {
                        TemplateInstallNotifier.fail(
                            context = context,
                            workspaceId = workspace.id,
                            templateName = template.name,
                            scriptName = result.script.name,
                            logTail = result.output.takeLast(2_000),
                        )
                        _templateError.value =
                            "步骤「${result.script.name}」失败（退出码 ${result.exitCode}）\n\n${result.output.takeLast(1_500)}"
                    }
                }
                loadWorkspace()
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (error: Throwable) {
                _installError.value = error.message ?: "模板应用失败"
            } finally {
                _installProgress.value = null
                _templateProgress.value = null
                _applyingTemplate.value = false
            }
        }
    }

    /** 把当前工作区保存为自定义模板；[scriptsText] 每行一条命令。 */
    fun saveAsTemplate(name: String, description: String, scriptsText: String, rootfsUrl: String?) {
        viewModelScope.launch {
            val finalName = name.trim().ifBlank { "My Template" }
            val scripts = scriptsText.lines()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .mapIndexed { index, command ->
                    TemplateScript(
                        name = "Step ${index + 1}: ${command.take(40)}",
                        command = command,
                    )
                }
            val template = WorkspaceTemplate(
                id = "custom-${java.util.UUID.randomUUID()}",
                name = finalName,
                description = description.trim(),
                icon = "Terminal",
                builtin = false,
                rootfsUrl = rootfsUrl,
                scripts = scripts,
                createdAt = System.currentTimeMillis(),
            )
            runCatching { repository.saveCustomTemplate(template) }
                .onSuccess { _templateMessage.value = "已保存模板「$finalName」" }
                .onFailure { _templateError.value = "保存模板失败：${it.message}" }
        }
    }

    fun deleteTemplate(templateId: String) {
        viewModelScope.launch {
            runCatching { repository.deleteCustomTemplate(templateId) }
                .onFailure { _templateError.value = "删除模板失败：${it.message}" }
        }
    }

    fun consumeTemplateMessage() {
        _templateMessage.value = null
    }

    fun dismissTemplateError() {
        _templateError.value = null
    }

    private val _settingsError = MutableStateFlow<String?>(null)
    val settingsError = _settingsError.asStateFlow()

    fun dismissSettingsError() {
        _settingsError.value = null
    }

    init {
        loadWorkspace()
        refresh()
    }

    fun selectArea(area: WorkspaceStorageArea) {
        _state.update {
            it.copy(
                area = area,
                path = "",
                entries = emptyList(),
                error = null,
            )
        }
        refresh()
    }

    fun open(entry: WorkspaceFileEntry) {
        if (!entry.isDirectory) return
        _state.update { it.copy(path = entry.path, entries = emptyList(), error = null) }
        refresh()
    }

    fun goUp() {
        val path = state.value.path
        if (path.isBlank()) return
        _state.update {
            it.copy(
                path = path.substringBeforeLast('/', missingDelimiterValue = ""),
                entries = emptyList(),
                error = null,
            )
        }
        refresh()
    }

    private var refreshJob: Job? = null

    fun refresh() {
        // 远程工作区列目录要走网络, 快速切换目录时旧请求可能晚到, 只保留最新一次
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val entries = repository.listFiles(
                    id = id,
                    area = state.value.area,
                    path = state.value.path,
                )
                _state.update { it.copy(entries = entries, loading = false) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update {
                    it.copy(
                        entries = emptyList(),
                        loading = false,
                        error = error.message ?: "加载工作区文件失败",
                    )
                }
            }
        }
    }

    fun delete(entry: WorkspaceFileEntry) {
        viewModelScope.launch {
            runCatching {
                repository.deleteFile(
                    id = id,
                    area = state.value.area,
                    path = entry.path,
                    recursive = entry.isDirectory,
                )
            }.onSuccess {
                refresh()
            }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: "删除失败") }
            }
        }
    }

    fun importFile(inputStream: InputStream, fileName: String) {
        viewModelScope.launch {
            runCatching {
                repository.importFile(
                    id = id,
                    area = state.value.area,
                    destinationPath = state.value.path,
                    fileName = fileName,
                    inputStream = inputStream,
                )
            }.onSuccess {
                refresh()
            }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: "导入文件失败") }
            }
        }
    }

    fun exportFile(entry: WorkspaceFileEntry, uri: Uri, resolver: ContentResolver) {
        viewModelScope.launch {
            runCatching {
                // 部分 ROM 的 SAF 返回的 Uri 并没有真正创建文件, 打开时会抛 FileNotFoundException
                val outputStream = withContext(Dispatchers.IO) { resolver.openOutputStream(uri) }
                    ?: error("无法打开目标文件")
                repository.exportFile(
                    id = id,
                    area = state.value.area,
                    path = entry.path,
                    outputStream = outputStream,
                )
            }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: "导出文件失败") }
            }
        }
    }

    suspend fun resolveImageFile(
        entry: WorkspaceFileEntry,
        area: WorkspaceStorageArea,
    ): File? = repository.resolveFile(id, area, entry.path)

    /**
     * 把当前区域下的文件导出到 cacheDir 的临时文件, 完成后回调 [onReady].
     * 供分享 / 图片预览 / 交给系统应用打开等复用 (它们都需要一个 FileProvider 可访问的真实 File).
     */
    fun exportToCacheFile(entry: WorkspaceFileEntry, cacheDir: File, onReady: (File) -> Unit) {
        viewModelScope.launch {
            runCatching {
                val dir = File(cacheDir, "workspace_share").apply { mkdirs() }
                val file = File(dir, entry.name)
                file.outputStream().use { output ->
                    repository.exportFile(
                        id = id,
                        area = state.value.area,
                        path = entry.path,
                        outputStream = output,
                    )
                }
                file
            }.onSuccess(onReady).onFailure { error ->
                _state.update { it.copy(error = error.message ?: "导出文件失败") }
            }
        }
    }

    fun setShellCompatibilityMode(enabled: Boolean) {
        viewModelScope.launch {
            try {
                repository.setShellCompatibilityMode(id, enabled)
                val workspace = repository.getById(id)
                _state.update { it.copy(workspace = workspace) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _settingsError.value = error.message.orEmpty()
            }
        }
    }

    fun setToolApproval(toolName: String, needsApproval: Boolean) {
        viewModelScope.launch {
            val workspace = state.value.workspace ?: return@launch
            repository.setToolApproval(workspace.id, toolName, needsApproval)
            loadWorkspace()
        }
    }

    fun installRootfs(url: String) {
        viewModelScope.launch {
            _installError.value = null
            val workspace = state.value.workspace ?: return@launch
            _installProgress.value = RootfsInstallProgress(stage = RootfsInstallStage.DOWNLOADING)
            try {
                terminalSessionManager.closeWorkspace(workspace.root)
                repository.installRootfs(workspace.id, url) { progress ->
                    _installProgress.value = progress
                }
                loadWorkspace()
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (error: Throwable) {
                _installError.value = error.message ?: "Rootfs 安装失败"
            } finally {
                _installProgress.value = null
            }
        }
    }

    fun dismissInstallError() {
        _installError.value = null
    }

    fun executeTerminalCommand(command: String) {
        val trimmed = command.trim()
        if (trimmed.isBlank()) return
        // 原子地完成「检查 running」与「置 running=true」, 避免两次快速提交并发启动两条命令
        val previous = _terminalState.getAndUpdate { state ->
            if (state.running) {
                state
            } else {
                state.copy(
                    running = true,
                    input = "",
                    history = state.history + WorkspaceTerminalEntry.Command(trimmed),
                )
            }
        }
        if (previous.running) return
        viewModelScope.launch {
            runCatching {
                repository.executeCommand(id, trimmed)
            }.onSuccess { result ->
                _terminalState.update {
                    it.copy(
                        running = false,
                        history = it.history + WorkspaceTerminalEntry.Result(result),
                    )
                }
            }.onFailure { error ->
                _terminalState.update {
                    it.copy(
                        running = false,
                        history = it.history + WorkspaceTerminalEntry.Error(error.message ?: "命令执行失败"),
                    )
                }
            }
        }
    }

    fun updateTerminalInput(input: String) {
        _terminalState.update { it.copy(input = input) }
    }

    fun clearTerminal() {
        _terminalState.update { it.copy(history = emptyList()) }
    }

    private fun loadWorkspace() {
        viewModelScope.launch {
            val workspace = repository.getById(id)
            val layout = repository.layout(id)
            _state.update { it.copy(workspace = workspace, layout = layout) }
        }
    }
}

data class WorkspaceDetailState(
    val workspace: WorkspaceEntity? = null,
    val layout: WorkspaceLayout? = null,
    val area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    val path: String = "",
    val entries: List<WorkspaceFileEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val exporting: Boolean = false,
    val exportCompleted: Int = 0,
    val exportTotal: Int = 0,
    val exportResult: String? = null,
)

data class WorkspaceTerminalState(
    val input: String = "",
    val running: Boolean = false,
    val history: List<WorkspaceTerminalEntry> = emptyList(),
)

sealed interface WorkspaceTerminalEntry {
    data class Command(val command: String) : WorkspaceTerminalEntry
    data class Result(val result: WorkspaceCommandResult) : WorkspaceTerminalEntry
    data class Error(val message: String) : WorkspaceTerminalEntry
}

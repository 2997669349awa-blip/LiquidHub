package me.rerere.rikkahub.ui.pages.extensions.knowledge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.db.rag.RagSearchHit
import me.rerere.rikkahub.data.db.rag.RagStats
import me.rerere.rikkahub.data.repository.RagRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository

data class KnowledgeState(
    val stats: RagStats = RagStats(0, 0, emptyMap(), 0),
    val sources: List<Pair<String, Int>> = emptyList(),
    val indexing: Boolean = false,
    val progressIndex: Int = 0,
    val progressTotal: Int = 0,
    val progressLabel: String = "",
    val searchQuery: String = "",
    val searching: Boolean = false,
    val results: List<RagSearchHit> = emptyList(),
    val message: String? = null,
)

class KnowledgeVM(
    private val ragRepository: RagRepository,
    private val workspaceRepository: WorkspaceRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(KnowledgeState())
    val state = _state.asStateFlow()

    val workspaces = workspaceRepository.listFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            runCatching {
                val stats = ragRepository.stats()
                val sources = ragRepository.sources()
                _state.update { it.copy(stats = stats, sources = sources) }
            }.onFailure { _state.update { s -> s.copy(message = "读取索引失败：${it.message}") } }
        }
    }

    fun indexUploads() {
        if (_state.value.indexing) return
        viewModelScope.launch {
            _state.update { it.copy(indexing = true, progressLabel = "正在索引上传文档", progressIndex = 0, progressTotal = 0) }
            try {
                val indexed = ragRepository.indexUploads { done, total ->
                    _state.update { it.copy(progressIndex = done, progressTotal = total) }
                }
                _state.update { it.copy(message = "已索引 $indexed 个文档") }
            } catch (e: CancellationException) {
                throw e
            } catch (error: Throwable) {
                _state.update { it.copy(message = "索引失败：${error.message}") }
            } finally {
                _state.update { it.copy(indexing = false) }
                refresh()
            }
        }
    }

    fun indexWorkspace(workspaceId: String) {
        if (_state.value.indexing) return
        viewModelScope.launch {
            _state.update { it.copy(indexing = true, progressLabel = "正在索引工作区文件", progressIndex = 0, progressTotal = 0) }
            try {
                val indexed = ragRepository.indexWorkspace(workspaceId) { done, total ->
                    _state.update { it.copy(progressIndex = done, progressTotal = total) }
                }
                _state.update { it.copy(message = "已索引 $indexed 个工作区文件") }
            } catch (e: CancellationException) {
                throw e
            } catch (error: Throwable) {
                _state.update { it.copy(message = "索引失败：${error.message}") }
            } finally {
                _state.update { it.copy(indexing = false) }
                refresh()
            }
        }
    }

    fun clearIndex() {
        viewModelScope.launch {
            runCatching { ragRepository.clearAll() }
                .onSuccess {
                    _state.update { it.copy(message = "索引已清空", results = emptyList()) }
                    refresh()
                }
                .onFailure { _state.update { s -> s.copy(message = "清空失败：${it.message}") } }
        }
    }

    fun updateSearchQuery(query: String) {
        _state.update { it.copy(searchQuery = query) }
    }

    fun search() {
        val query = _state.value.searchQuery.trim()
        if (query.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(searching = true) }
            runCatching { ragRepository.search(query, 20) }
                .onSuccess { hits -> _state.update { it.copy(results = hits, searching = false) } }
                .onFailure { error ->
                    _state.update { it.copy(searching = false, message = "搜索失败：${error.message}") }
                }
        }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }
}

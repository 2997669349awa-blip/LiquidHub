package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.repository.RagRepository

/**
 * Local knowledge base tools: search indexed documents/workspace files and
 * trigger indexing. Works fully offline (FTS5 + jieba tokenizer).
 */
fun createKnowledgeTools(ragRepository: RagRepository): List<Tool> = listOf(
    Tool(
        name = "knowledge_search",
        description = "Search the local knowledge base built from uploaded documents and workspace text files. " +
            "Returns the most relevant passages with their source title. Use this before asking the user to paste file content.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("query", buildJsonObject {
                        put("type", "string")
                        put("description", "What to look for, natural language or keywords")
                    })
                    put("limit", buildJsonObject {
                        put("type", "integer")
                        put("description", "Max results, default 8, max 20")
                    })
                    put("source", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional filter: document or workspace")
                    })
                },
                required = listOf("query"),
            )
        },
        needsApproval = { false },
        execute = {
            val params = it.jsonObject
            val query = params.string("query") ?: error("query is required")
            val limit = params.string("limit")?.toIntOrNull()?.coerceIn(1, 20) ?: 8
            val source = params.string("source")?.lowercase()?.takeIf {
                it == "document" || it == "workspace"
            }
            val hits = ragRepository.search(query, limit).filter { source == null || it.sourceType == source }
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("query", query)
                        put("results", buildJsonArray {
                            hits.forEach { hit ->
                                add(buildJsonObject {
                                    put("sourceType", hit.sourceType)
                                    put("title", hit.title)
                                    hit.locator?.let { put("locator", it) }
                                    put("snippet", hit.snippet)
                                })
                            }
                        })
                        if (hits.isEmpty()) {
                            put("hint", "No indexed content matched. The user can index files in Extensions > Knowledge base.")
                        }
                    }.toString()
                )
            )
        },
    ),
    Tool(
        name = "knowledge_index",
        description = "Build or refresh the local knowledge index. source=uploads indexes uploaded documents; " +
            "source=workspace indexes text files in the given workspace. Indexing is incremental.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("source", buildJsonObject {
                        put("type", "string")
                        put("description", "uploads or workspace")
                    })
                    put("workspaceId", buildJsonObject {
                        put("type", "string")
                        put("description", "Workspace ID, required when source=workspace")
                    })
                },
                required = listOf("source"),
            )
        },
        needsApproval = { false },
        execute = {
            val params = it.jsonObject
            val source = params.string("source")?.lowercase() ?: "uploads"
            val indexed = when (source) {
                "uploads" -> ragRepository.indexUploads()
                "workspace" -> {
                    val workspaceId = params.string("workspaceId")
                        ?: error("workspaceId is required when source=workspace")
                    ragRepository.indexWorkspace(workspaceId)
                }

                else -> error("Unknown source: $source (expected uploads or workspace)")
            }
            val stats = ragRepository.stats()
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("indexedSources", indexed)
                        put("totalChunks", stats.totalChunks)
                        put("sourceCount", stats.sourceCount)
                        put("byType", buildJsonObject {
                            stats.byType.forEach { (type, count) -> put(type, count) }
                        })
                    }.toString()
                )
            )
        },
    ),
)

private fun kotlinx.serialization.json.JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.rikkahub.utils.toLocalString
import me.rerere.search.SearchService
import me.rerere.search.SearchServiceOptions
import java.time.LocalDate
import kotlin.time.Clock
import kotlin.uuid.Uuid

fun createSearchTools(settings: Settings): Set<Tool> {
    return buildSet {
        add(
            Tool(
                name = "search_web",
                description = """
                    Search the web for up-to-date or specific information.
                    Use this when the user asks for the latest news, current facts, or needs verification.
                    Do not treat result order as proof of freshness. Prefer primary sources and inspect
                    each result's title, URL, publication date, and content before making a current claim.
                    Use the optional publication-date and domain filters only when they match the question.
                    If a date or primary source is missing, or sources conflict, run another focused search
                    or use scrape_web to verify the most relevant source before answering.
                    Generate focused keywords and run multiple searches if needed.
                    Today is ${LocalDate.now().toLocalString(true)}.

                    Response format:
                    - retrievedAt is the local retrieval time, never a publication date
                    - items[].id (short id), index, title, url, sourceType, publishedDate (if supplied), ageDays (days since publication; null if unknown), highlights (if supplied), text
                    - sourceType is one of: official (official site, .gov/.edu or official domains), wiki, news, other, forum
                    - items are pre-sorted: official first, then wiki/news, then other, then forum; within a group newer items first
                    - images[]: image urls related to the query (may be empty)

                    Authority & recency:
                    - For products, games, software, policy, sports and other authoritative topics, rely on official sources (sourceType=official) first; treat wiki/news/forum/other as auxiliary, and never use them as the final authority when an official source exists.
                    - Distinguish retrievedAt (when you fetched it) from publishedDate/ageDays (when it was published). When you state a time, say which one and include the date/age (e.g. "published 2026-10-01, about 4 days ago").
                    - If a time-sensitive claim has no publishedDate (ageDays=null), explicitly say the date is unknown instead of guessing.

                    Citations:
                    - After using results, add `[citation,domain](id)` after the sentence.
                    - Multiple citations are allowed.
                    - If no results are cited, omit citations.

                    Images:
                    - When images help the user understand the answer, embed relevant ones using Markdown: `![](url)`.
                    - Embed 2 to 4 images, and only use urls from `images[]` (never fabricate or alter urls).
                    - Usually place the images at the very beginning of your reply; skip them entirely if none are relevant.

                    Example:
                    The capital of France is Paris. [citation,example.com](abc123)
                    The population is about 2.1 million. [citation,example.com](abc123) [citation,example2.com](def456)
                    """.trimIndent(),
                parameters = {
                    val options = settings.searchServices.getOrElse(
                        index = settings.searchServiceSelected,
                        defaultValue = { SearchServiceOptions.DEFAULT })
                    val service = SearchService.getService(options)
                    service.parameters(options)
                },
                execute = {
                    val options = settings.searchServices.getOrElse(
                        index = settings.searchServiceSelected,
                        defaultValue = { SearchServiceOptions.DEFAULT })
                    val service = SearchService.getService(options)
                    val result = service.search(
                        params = it.jsonObject,
                        commonOptions = settings.searchCommonOptions,
                        serviceOptions = options,
                    ).getOrThrow().copy(retrievedAt = Clock.System.now().toString())
                    val results =
                        JsonInstantPretty.encodeToJsonElement(result).jsonObject.let { json ->
                            val map = json.toMutableMap()
                            val enriched = map["items"]!!.jsonArray.map { item ->
                                val obj = item.jsonObject
                                val url = obj["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                val published = obj["publishedDate"]?.jsonPrimitive?.contentOrNull
                                EnrichedSearchItem(
                                    obj = obj,
                                    sourceType = classifySource(url),
                                    ageDays = parsePublishedAgeDays(published),
                                )
                            }.sortedWith(
                                compareBy({ sourceRank(it.sourceType) }, { it.ageDays ?: Int.MAX_VALUE })
                            )
                            map["items"] =
                                JsonArray(enriched.mapIndexed { index, item ->
                                    JsonObject(item.obj.toMutableMap().apply {
                                        put("id", JsonPrimitive(Uuid.random().toString().take(6)))
                                        put("index", JsonPrimitive(index + 1))
                                        put("sourceType", JsonPrimitive(item.sourceType))
                                        item.ageDays?.let { put("ageDays", JsonPrimitive(it)) }
                                    })
                                })
                            JsonObject(map)
                        }
                    listOf(UIMessagePart.Text(results.toString()))
                }
            )
        )

        val options = settings.searchServices.getOrElse(
            index = settings.searchServiceSelected,
            defaultValue = { SearchServiceOptions.DEFAULT })
        val service = SearchService.getService(options)
        if (service.scrapingParameters(options) != null) {
            add(
                Tool(
                    name = "scrape_web",
                    description = """
                        Scrape a URL for detailed page content.
                        Use this when the user requests content from a specific page, when search snippets are insufficient,
                        or when a current claim needs verification against a specific source.
                        Avoid using it for common questions unless the user asks.
                        """.trimIndent(),
                    parameters = {
                        val options = settings.searchServices.getOrElse(
                            index = settings.searchServiceSelected,
                            defaultValue = { SearchServiceOptions.DEFAULT })
                        val service = SearchService.getService(options)
                        service.scrapingParameters(options)
                    },
                    execute = {
                        val options = settings.searchServices.getOrElse(
                            index = settings.searchServiceSelected,
                            defaultValue = { SearchServiceOptions.DEFAULT })
                        val service = SearchService.getService(options)
                        val result = service.scrape(
                            params = it.jsonObject,
                            commonOptions = settings.searchCommonOptions,
                            serviceOptions = options,
                        ).getOrThrow().copy(retrievedAt = Clock.System.now().toString())
                        val payload = JsonInstantPretty.encodeToJsonElement(result).jsonObject
                        listOf(UIMessagePart.Text(payload.toString()))
                    }
                ))
        }
    }
}

private data class EnrichedSearchItem(
    val obj: JsonObject,
    val sourceType: String,
    val ageDays: Int?,
)

private fun sourceRank(sourceType: String): Int = when (sourceType) {
    "official" -> 0
    "wiki" -> 1
    "news" -> 2
    "other" -> 3
    "forum" -> 4
    else -> 3
}

private fun classifySource(url: String): String {
    val host = runCatching { java.net.URI(url).host ?: "" }.getOrDefault("").lowercase()
    if (host.isBlank()) return "other"
    return when {
        host.endsWith(".gov") || host.endsWith(".gov.cn") ||
            host.endsWith(".edu") || host.endsWith(".edu.cn") ||
            host.contains("official") -> "official"

        host.contains("wikipedia") || host.contains("wiki") ||
            host.contains("fandom") || host.contains("baike") -> "wiki"

        host.contains("news") || host.endsWith("ithome.com") ||
            host.contains("36kr") || host.contains("cnbeta") ||
            host.contains("gamersky") || host.contains("ign.com") -> "news"

        host.contains("reddit") || host.contains("tieba") ||
            host.contains("zhihu") || host.contains("bbs") ||
            host.contains("forum") || host.contains("stackoverflow") ||
            host.contains("quora") || host.contains("discord") -> "forum"

        else -> "other"
    }
}

/** 解析发布时间的“距今天数”，解析失败返回 null（避免模型臆测时间）。 */
private fun parsePublishedAgeDays(published: String?): Int? {
    if (published.isNullOrBlank()) return null
    val instant = runCatching { java.time.OffsetDateTime.parse(published).toInstant() }
        .recoverCatching { java.time.Instant.parse(published) }
        .recoverCatching {
            java.time.LocalDate.parse(published)
                .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant()
        }
        .getOrNull() ?: return null
    val days = java.time.Duration.between(instant, java.time.Instant.now()).toDays().toInt()
    return days.coerceAtLeast(0)
}

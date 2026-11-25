package com.aichat.app.core.tools

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 联网搜索（Tavily）。
 *
 * 用 ultra-fast 档：延迟最低。它的返回是「每个结果一个摘要片段」，
 * 不像 basic/advanced 那样返回多个语义片段。对「查个事实然后回答」够用，
 * 而且快 —— 用户等的是回答，不是一堆链接。
 *
 * 【为什么把 key 硬编码】
 * 本项目是自用的单用户 App，不走设置页，key 直接写在 Config 里。
 */
class SearchTool(
    private val apiKey: String,
    private val endpoint: String,
    private val searchDepth: String,
    private val maxResults: Int,
) : Tool {

    override val name = "web_search"

    override val description = """
        联网搜索。当用户问的是需要最新信息的问题（新闻、天气、实时数据、你不确定的事实），
        或者用户明确要求「搜一下」时使用。
        不需要搜索的问题（常识、闲聊、写代码）不要调用。
    """.trimIndent()

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("query", buildJsonObject {
                put("type", "string")
                put("description", "搜索关键词。用简洁的关键词，不要写成一句话")
            })
        })
        put("required", kotlinx.serialization.json.buildJsonArray {
            add(kotlinx.serialization.json.JsonPrimitive("query"))
        })
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val query = args["query"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (query.isEmpty()) {
            return@withContext ToolResult(false, "query 不能为空", "参数错误")
        }
        if (apiKey.isBlank() || apiKey.startsWith("tvly-REPLACE")) {
            return@withContext ToolResult(
                false,
                "搜索功能未配置 API key。",
                "搜索未配置",
            )
        }

        try {
            val body = buildJsonObject {
                put("query", query)
                put("search_depth", searchDepth)
                put("max_results", maxResults)
                put("include_answer", false)
                // 不抓原始页面内容：ultra-fast 的价值就是快，抓正文会把延迟拉回 basic 水平
                put("include_raw_content", false)
            }.toString()

            val request = Request.Builder()
                .url(endpoint)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.e("SearchTool", "HTTP ${resp.code}: $text")
                    return@withContext ToolResult(
                        false,
                        "搜索失败（HTTP ${resp.code}）。可以告诉用户搜索暂时不可用，或直接回答。",
                        "搜索失败",
                    )
                }

                val parsed = try {
                    json.decodeFromString(TavilyResponse.serializer(), text)
                } catch (e: Exception) {
                    Log.e("SearchTool", "解析失败", e)
                    return@withContext ToolResult(
                        false,
                        "搜索结果解析失败。",
                        "解析失败",
                    )
                }

                if (parsed.results.isEmpty()) {
                    return@withContext ToolResult(
                        true,
                        "没有搜到关于「$query」的结果。",
                        "搜索「$query」无结果",
                    )
                }

                // 把结果整理成模型好读的格式。带上 URL 让它能引用来源。
                val sb = StringBuilder("关于「$query」的搜索结果：\n")
                parsed.results.forEachIndexed { i, r ->
                    sb.append("\n[${i + 1}] ${r.title}\n")
                    sb.append("${r.content}\n")
                    if (!r.url.isNullOrBlank()) sb.append("来源：${r.url}\n")
                }

                ToolResult(
                    true,
                    sb.toString().trim(),
                    "搜索「$query」",
                )
            }
        } catch (e: Exception) {
            Log.e("SearchTool", "搜索异常", e)
            ToolResult(false, "搜索异常：${e.message}", "搜索异常")
        }
    }

    @Serializable
    private data class TavilyResponse(
        val query: String? = null,
        val answer: String? = null,
        val results: List<TavilyResult> = emptyList(),
    )

    @Serializable
    private data class TavilyResult(
        val title: String = "",
        val url: String? = null,
        val content: String = "",
        val score: Double = 0.0,
    )
}

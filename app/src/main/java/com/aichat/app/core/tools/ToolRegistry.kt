package com.aichat.app.core.tools

import android.content.Context
import com.aichat.app.core.network.Config
import com.aichat.app.core.network.FunctionSpec
import com.aichat.app.core.network.ToolSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 工具注册表：把 [Tool] 实例转成模型认识的 schema，并按名字分发调用。
 */
class ToolRegistry(context: Context) {

    private val tools: Map<String, Tool> = listOf(
        CallTool(context),
        OpenAppTool(context),
        SearchTool(
            apiKey = Config.TAVILY_API_KEY,
            endpoint = Config.TAVILY_ENDPOINT,
            searchDepth = Config.TAVILY_SEARCH_DEPTH,
            maxResults = Config.TAVILY_MAX_RESULTS,
        ),
    ).associateBy { it.name }

    /** 给模型看的所有工具定义。 */
    fun specs(): List<ToolSpec> = tools.values.map { t ->
        ToolSpec(
            function = FunctionSpec(
                name = t.name,
                description = t.description,
                parameters = t.parameters,
            ),
        )
    }

    /**
     * 执行一个工具调用。
     *
     * 参数解析失败、未知工具名都返回 ok=false 的 ToolResult 而不是抛异常 ——
     * 模型看到失败原因后通常会自己改参数重试，比整个对话中断好。
     */
    suspend fun execute(name: String, argumentsJson: String): ToolResult {
        val tool = tools[name]
            ?: return ToolResult(false, "没有叫 $name 的工具。可用工具：${tools.keys.joinToString("、")}", "未知工具")

        val args: JsonObject = try {
            com.aichat.app.core.network.AppJson.decodeFromString(
                JsonObject.serializer(),
                argumentsJson.ifBlank { "{}" },
            )
        } catch (e: Exception) {
            return ToolResult(false, "参数不是合法 JSON：${e.message}", "参数解析失败")
        }

        return try {
            tool.execute(args)
        } catch (e: Exception) {
            ToolResult(false, "工具执行出错：${e.message}", "执行出错")
        }
    }

    /** 给 UI 用的展示标题（参数可能还没解析完，尽力而为）。 */
    fun describe(name: String, args: JsonObject): String = when (name) {
        "call_phone" -> {
            val n = args["name"]?.let { (it as? JsonPrimitive)?.contentOrNull }
                ?: args["number"]?.let { (it as? JsonPrimitive)?.contentOrNull }
            if (n != null) "拨打 $n" else "拨打电话"
        }
        "open_app" -> {
            val a = args["app"]?.let { (it as? JsonPrimitive)?.contentOrNull }
            val act = args["action"]?.let { (it as? JsonPrimitive)?.contentOrNull }
            when {
                act == "list" -> "查看应用列表"
                a != null -> "打开 $a"
                else -> "打开应用"
            }
        }
        "web_search" -> {
            val q = args["query"]?.let { (it as? JsonPrimitive)?.contentOrNull }
            if (q != null) "搜索：$q" else "联网搜索"
        }
        else -> name
    }
}

package com.aichat.app.core.tools

import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 打开已安装的软件。
 *
 * 一个工具两个动作，靠 action 参数区分：
 *   action=open（默认）：打开指定应用
 *   action=list：列出所有可打开的应用
 *
 * 【为什么合成一个工具而不是两个】
 * 模型经常先 list 再 open，分成两个工具它就要多记一个名字。
 * 合在一起、用 action 区分，模型只需要记一件事：「要操作软件就调这个」。
 *
 * 【应用名匹配】
 * 用户说「打开微信」，但包名是 com.tencent.mm，显示名是「微信」。
 * 匹配时同时比对：显示名（中文）、包名、以及启动 Activity 的类名。
 * 中文名用 contains 而不是全等 —— 用户可能说「打开哔哩」而显示名是「哔哩哔哩」。
 */
class OpenAppTool(private val context: Context) : Tool {

    override val name = "open_app"

    override val description = """
        打开手机上的应用，或查看装了哪些应用。
        action=open（默认）：打开应用，需要传 app 参数（应用名，中文名即可，如「微信」）。
        action=list：列出所有已安装的可打开应用。用户问「我装了哪些软件」「有没有装 XX」时用它。
    """.trimIndent()

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("action", buildJsonObject {
                put("type", "string")
                put("enum", buildJsonArray {
                    add(kotlinx.serialization.json.JsonPrimitive("open"))
                    add(kotlinx.serialization.json.JsonPrimitive("list"))
                })
                put("description", "open=打开应用（默认），list=列出所有应用")
            })
            put("app", buildJsonObject {
                put("type", "string")
                put("description", "应用名，如「微信」「支付宝」。action=open 时必填")
            })
        })
    }

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val action = args["action"]?.jsonPrimitive?.content?.trim().orEmpty().ifEmpty { "open" }

        if (action == "list") {
            return@withContext listApps()
        }

        val query = args["app"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (query.isEmpty()) {
            return@withContext ToolResult(false, "action=open 时必须提供 app 参数", "参数错误")
        }

        val apps = loadApps()
        if (apps.isEmpty()) {
            return@withContext ToolResult(false, "没有查到任何可启动的应用", "应用列表为空")
        }

        val matched = findBest(query, apps)
        when {
            matched.isEmpty() -> {
                val hint = apps.take(15).joinToString("、") { it.label }
                ToolResult(
                    false,
                    "没找到叫「$query」的应用。当前装的部分应用有：$hint。可以用 action=list 查看完整列表。",
                    "找不到「$query」",
                )
            }

            matched.size > 1 -> {
                val names = matched.take(5).joinToString("、") { it.label }
                ToolResult(
                    false,
                    "「$query」匹配到多个应用：$names。请询问用户要打开哪一个。",
                    "匹配到 ${matched.size} 个应用",
                )
            }

            else -> launch(matched.first())
        }
    }

    private fun listApps(): ToolResult {
        val apps = loadApps()
        if (apps.isEmpty()) {
            return ToolResult(false, "没有查到可启动的应用", "应用列表为空")
        }
        // 按名字排序，方便模型和用户浏览
        val list = apps.sortedBy { it.label }
            .joinToString("\n") { "- ${it.label}（${it.packageName}）" }
        return ToolResult(
            true,
            "已安装 ${apps.size} 个可打开的应用：\n$list",
            "列出 ${apps.size} 个应用",
        )
    }

    private fun launch(app: AppEntry): ToolResult = try {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setClassName(app.packageName, app.activityName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }
        context.startActivity(intent)
        ToolResult(true, "已打开「${app.label}」。", "打开 ${app.label}")
    } catch (e: Exception) {
        Log.e("OpenAppTool", "启动失败", e)
        ToolResult(false, "启动「${app.label}」失败：${e.message}", "打开失败")
    }

    private data class AppEntry(
        val label: String,
        val packageName: String,
        val activityName: String,
    )

    /**
     * 查询所有带启动图标的应用。
     *
     * Android 11+ 有包可见性限制：manifest 里必须声明 <queries>，
     * 否则 queryIntentActivities 只会返回自己和少数系统应用。
     * 已在 AndroidManifest.xml 里声明了 MAIN/LAUNCHER 的 queries。
     */
    private fun loadApps(): List<AppEntry> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved: List<ResolveInfo> = try {
            pm.queryIntentActivities(intent, 0)
        } catch (e: Exception) {
            Log.e("OpenAppTool", "查询应用失败", e)
            return emptyList()
        }

        val out = mutableListOf<AppEntry>()
        val seen = mutableSetOf<String>()
        for (ri in resolved) {
            val pkg = ri.activityInfo?.packageName ?: continue
            val act = ri.activityInfo?.name ?: continue
            if (!seen.add("$pkg/$act")) continue
            val label = try {
                ri.loadLabel(pm)?.toString()?.trim().orEmpty()
            } catch (_: Exception) {
                ""
            }
            if (label.isEmpty()) continue
            out += AppEntry(label, pkg, act)
        }
        return out
    }

    /**
     * 按名字找应用。优先级：
     *   完全一致 > 名字以查询开头 > 名字包含查询 > 包名包含查询
     * 只在最高优先级那一层返回结果，避免「微信」同时匹配到「微信」和「微信读书」。
     */
    private fun findBest(query: String, apps: List<AppEntry>): List<AppEntry> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()

        val exact = apps.filter { it.label.lowercase() == q }
        if (exact.isNotEmpty()) return exact

        val startsWith = apps.filter { it.label.lowercase().startsWith(q) }
        if (startsWith.isNotEmpty()) return startsWith

        val contains = apps.filter { it.label.lowercase().contains(q) }
        if (contains.isNotEmpty()) return contains

        return apps.filter { it.packageName.lowercase().contains(q) }
    }
}

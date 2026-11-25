package com.aichat.app.core.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 会话与设置的本地存储。
 *
 * 【为什么用文件而不是 SQLite / Room】
 * 数据量很小（几十个会话、每个几百条消息），JSON 文件足够，而且可以直接用
 * 文本编辑器查看/备份。引入 Room 要 KSP 注解处理器，CI 构建时间翻倍，
 * 对一个单用户 App 不值。
 *
 * 【写入策略】
 * 每次变更整份重写。会话数量在几十这个量级时，重写一份几百 KB 的 JSON
 * 是毫秒级的事。原子性靠「先写临时文件再 rename」—— rename 在同一分区是原子操作，
 * 中途被杀进程不会留下半个文件。
 */
class Store(private val context: Context) {

    companion object {
        private const val TAG = "Store"
        private const val CONVERSATIONS_FILE = "conversations.json"
        private const val SETTINGS_FILE = "settings.json"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = false
    }

    // ─────────────────── 会话 ───────────────────

    suspend fun loadConversations(): List<Conversation> = withContext(Dispatchers.IO) {
        val f = File(context.filesDir, CONVERSATIONS_FILE)
        if (!f.exists()) return@withContext emptyList()
        try {
            json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(Conversation.serializer()),
                f.readText(),
            )
        } catch (e: Exception) {
            Log.e(TAG, "读取会话失败", e)
            emptyList()
        }
    }

    suspend fun saveConversations(list: List<Conversation>) = withContext(Dispatchers.IO) {
        val f = File(context.filesDir, CONVERSATIONS_FILE)
        atomicWrite(
            f,
            json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(Conversation.serializer()),
                list,
            ),
        )
    }

    // ─────────────────── 设置 ───────────────────

    suspend fun loadSettings(): AppSettings = withContext(Dispatchers.IO) {
        val f = File(context.filesDir, SETTINGS_FILE)
        if (!f.exists()) return@withContext AppSettings()
        try {
            json.decodeFromString(AppSettings.serializer(), f.readText())
        } catch (e: Exception) {
            Log.e(TAG, "读取设置失败", e)
            AppSettings()
        }
    }

    suspend fun saveSettings(s: AppSettings) = withContext(Dispatchers.IO) {
        val f = File(context.filesDir, SETTINGS_FILE)
        atomicWrite(f, json.encodeToString(AppSettings.serializer(), s))
    }

    /**
     * 先写 .tmp 再 rename。
     *
     * rename 在同一文件系统内是原子的，所以要么是完整的新文件、要么是完整的旧文件，
     * 不会出现「写到一半被杀进程，留下半个 JSON」。
     */
    private fun atomicWrite(target: File, content: String) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        try {
            tmp.writeText(content)
            if (!tmp.renameTo(target)) {
                // 有些文件系统 rename 会失败（目标存在时），退化成直接写
                target.writeText(content)
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "写入 ${target.name} 失败", e)
            runCatching { tmp.delete() }
        }
    }
}

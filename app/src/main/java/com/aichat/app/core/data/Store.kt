package com.aichat.app.core.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/**
 * 会话存储：JSONL 追加式（对齐 CCM CLI 的 SessionStore）。
 *
 * 【为什么不是整份重写】
 * 旧实现每个会话一个 .json，每次保存都要 stringify 整个历史再覆盖写。
 * 两个真实后果：
 *   1) **工具调用中途退出会丢数据** —— 只在「每轮结束」落盘，而一次工具调用
 *      可能跑很久，这期间 App 被杀整轮内容就没了。
 *   2) 重写成本随历史线性增长。几百条消息时每次都要序列化上百 KB 再写盘。
 *
 * 追加是 O(1)，与历史长度无关，所以能做得又勤又便宜：每条消息一产生就追加一行。
 *
 * 【格式】<会话id>.jsonl
 *   第 1 行：{"__meta":true,"id":...,"title":...,"createdAt":...}
 *   后续行 ：一条消息，原样一行 JSON
 * 元数据放首行而不是每行重复：省空间，且 load 时读一行就知道标题。
 */
class Store(private val context: Context) {

    companion object {
        private const val TAG = "Store"
        private const val SESSIONS_DIR = "sessions"
        private const val SETTINGS_FILE = "settings.json"

        /** 元数据行标记，load 时靠它区分首行和消息行。 */
        private const val META_KEY = "__meta"

        /**
         * 会话文件数上限。
         *
         * 只写不删会无限累积（CCM CLI 实测 10 个 1.2MB，长期挂着更胖）。
         * 超出时删 mtime 最旧的，但永不删当前活跃会话。
         */
        private const val MAX_SESSION_FILES = 30
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    /**
     * 会话写入串行化。
     *
     * appendMessage 是追加、rewriteSession 是整份覆盖，两者并发写同一文件会互相破坏：
     * 覆盖进行到一半时追加进来的行会被下一次覆盖丢掉，或者追加的内容夹在半个文件中间。
     * 用单线程上下文把所有写操作排队，代价是每次几毫秒，换来不会静默丢消息。
     */
    private val writeLock = kotlinx.coroutines.sync.Mutex()

    private val sessionsDir: File
        get() = File(context.filesDir, SESSIONS_DIR).also { if (!it.exists()) it.mkdirs() }

    private fun fileOf(id: String) = File(sessionsDir, "$id.jsonl")

    // ─────────────────── 会话写入 ───────────────────

    /**
     * 追加保存一条消息。
     *
     * 首次写入时先补一行 meta，否则 load 时首行会被当成消息。
     */
    suspend fun appendMessage(
        conversation: Conversation,
        message: Message,
    ) = withContext(Dispatchers.IO) { writeLock.withLock {
        val f = fileOf(conversation.id)
        try {
            if (!f.exists()) {
                val meta = buildJsonObject {
                    put(META_KEY, true)
                    put("id", conversation.id)
                    put("title", conversation.title)
                    put("createdAt", conversation.createdAt)
                    put("updatedAt", System.currentTimeMillis())
                }
                f.appendText(meta.toString() + "\n")
            }
            f.appendText(json.encodeToString(Message.serializer(), message) + "\n")
        } catch (e: Exception) {
            Log.e(TAG, "追加消息失败 ${conversation.id}", e)
        }
    } }

    /**
     * 重写整个会话文件（meta 行 + 全部消息）。
     *
     * 【什么时候用】助手消息会经历「正文 → 工具调用 → 再正文」多次变化，
     * 追加式写会留下多个半成品版本。对这种情况用整份重写：
     * 一次会话里最多每轮一次（工具轮数上限 8），成本可接受。
     *
     * 用户消息和工具消息仍然走 appendMessage（一次定型，追加即可）。
     */
    suspend fun rewriteSession(
        conversation: Conversation,
        messages: List<Message>,
    ) = withContext(Dispatchers.IO) { writeLock.withLock {
        val f = fileOf(conversation.id)
        try {
            val sb = StringBuilder()
            val meta = buildJsonObject {
                put(META_KEY, true)
                put("id", conversation.id)
                put("title", conversation.title)
                put("createdAt", conversation.createdAt)
                put("updatedAt", System.currentTimeMillis())
            }
            sb.append(meta.toString()).append('\n')
            for (m in messages) {
                sb.append(json.encodeToString(Message.serializer(), m)).append('\n')
            }
            atomicWrite(f, sb.toString())
        } catch (e: Exception) {
            Log.e(TAG, "重写会话失败 ${conversation.id}", e)
        }
    } }

    /**
     * 更新元数据（标题 / 时间），不动消息流。
     *
     * 做法：读首行、改字段、和剩余内容一起原子写回。
     * 只有标题变化时才需要，不是每条消息都做。
     */
    suspend fun updateMeta(
        id: String,
        title: String? = null,
        updatedAt: Long? = null,
    ) = withContext(Dispatchers.IO) {
        val f = fileOf(id)
        if (!f.exists()) return@withContext
        try {
            val raw = f.readText()
            val nl = raw.indexOf('\n')
            if (nl < 0) return@withContext

            val head = json.parseToJsonElement(raw.substring(0, nl)).jsonObject
            val merged = buildJsonObject {
                put(META_KEY, true)
                put("id", head["id"]?.jsonPrimitive?.content ?: id)
                put("title", title ?: head["title"]?.jsonPrimitive?.content ?: "新对话")
                put("createdAt", head["createdAt"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L)
                put("updatedAt", updatedAt ?: System.currentTimeMillis())
            }
            val rest = raw.substring(nl + 1)
            atomicWrite(f, merged.toString() + "\n" + rest)
        } catch (e: Exception) {
            Log.e(TAG, "更新元数据失败 $id", e)
        }
    }

    // ─────────────────── 会话读取 ───────────────────

    /** 会话摘要（列表用，不含消息）。 */
    data class SessionMeta(
        val id: String,
        val title: String,
        val createdAt: Long,
        val updatedAt: Long,
        val messageCount: Int,
    )

    /**
     * 列出所有会话摘要。
     *
     * 只读首行 + 数行数，不解析全部消息 —— 会话多了以后（几十个、每个几百条）
     * 全量解析会让启动明显变慢。
     */
    suspend fun listSessions(): List<SessionMeta> = withContext(Dispatchers.IO) {
        val files = sessionsDir.listFiles { f -> f.name.endsWith(".jsonl") } ?: return@withContext emptyList()
        files.mapNotNull { f ->
            try {
                val lines = f.readLines()
                if (lines.isEmpty()) return@mapNotNull null
                val head = json.parseToJsonElement(lines[0]).jsonObject
                SessionMeta(
                    id = head["id"]?.jsonPrimitive?.content ?: f.nameWithoutExtension,
                    title = head["title"]?.jsonPrimitive?.content ?: "新对话",
                    createdAt = head["createdAt"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                    updatedAt = head["updatedAt"]?.jsonPrimitive?.content?.toLongOrNull()
                        ?: f.lastModified(),
                    // 首行是 meta，消息行数 = 总行数 - 1
                    messageCount = (lines.size - 1).coerceAtLeast(0),
                )
            } catch (e: Exception) {
                Log.w(TAG, "读取会话摘要失败 ${f.name}", e)
                null
            }
        }.sortedByDescending { it.updatedAt }
    }

    /**
     * 读一个会话的完整消息列表。
     *
     * 单行损坏只跳过那一行，不整份作废 —— 这是 JSONL 相比整文件 JSON 的可靠性收益。
     * 但如果首行不是 meta，说明文件结构不可信，改名保留而不是静默丢弃。
     */
    suspend fun loadMessages(id: String): List<Message> = withContext(Dispatchers.IO) {
        val f = fileOf(id)
        if (!f.exists()) return@withContext emptyList()
        try {
            val all = f.readLines()
            if (all.isEmpty()) return@withContext emptyList()

            val firstIsMeta = runCatching {
                json.parseToJsonElement(all[0]).jsonObject[META_KEY]?.jsonPrimitive?.content
                    ?.toBooleanStrictOrNull() == true
            }.getOrDefault(false)

            if (!firstIsMeta) {
                val orphan = File(f.parentFile, "${f.name}.orphaned-${System.currentTimeMillis()}")
                f.renameTo(orphan)
                Log.w(TAG, "会话 $id 首行不是元数据，已保留为 ${orphan.name}")
                return@withContext emptyList()
            }

            all.drop(1).mapNotNull { line ->
                if (line.isBlank()) return@mapNotNull null
                try {
                    json.decodeFromString(Message.serializer(), line)
                } catch (e: Exception) {
                    // 单行损坏不该让整个会话读不出来
                    Log.w(TAG, "跳过损坏的消息行", e)
                    null
                }
            }
            // 注意：这里**不能**过滤 Role.TOOL。
            // OpenAI 协议要求「带 tool_calls 的 assistant 消息」后面必须紧跟
            // 对应的 role=tool 消息，否则服务端会报错或忽略那些 tool_calls ——
            // 模型于是看不到自己调用过什么，重启后会重复调用同一个工具。
            // UI 那端的过滤放在渲染层做（ChatScreen 的 items）。
        } catch (e: Exception) {
            Log.e(TAG, "读取会话失败 $id", e)
            emptyList()
        }
    }

    /**
     * 会话文件超限时裁掉最旧的。
     *
     * @param keepId 当前活跃会话，永不删除
     */
    suspend fun prune(keepId: String?) = withContext(Dispatchers.IO) {
        try {
            val files = sessionsDir.listFiles { f -> f.name.endsWith(".jsonl") } ?: return@withContext
            if (files.size <= MAX_SESSION_FILES) return@withContext

            val drop = files.sortedBy { it.lastModified() }
                .take(files.size - MAX_SESSION_FILES)
            for (f in drop) {
                val id = f.nameWithoutExtension
                if (id == keepId) continue
                f.delete()
            }
        } catch (e: Exception) {
            // 清理失败不该阻塞保存
            Log.w(TAG, "裁剪会话失败", e)
        }
    }

    /** 删除一个会话文件。 */
    suspend fun deleteSession(id: String) = withContext(Dispatchers.IO) {
        try {
            fileOf(id).delete()
        } catch (e: Exception) {
            Log.e(TAG, "删除会话失败 $id", e)
        }
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
     * 先写 .tmp 再 rename —— 同分区 rename 是原子的，
     * 中途被杀进程不会留下半个文件。
     */
    private fun atomicWrite(target: File, content: String) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        try {
            tmp.writeText(content)
            if (!tmp.renameTo(target)) {
                target.writeText(content)
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "写入 ${target.name} 失败", e)
            runCatching { tmp.delete() }
        }
    }
}

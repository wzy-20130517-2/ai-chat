package com.aichat.app.core.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 拨打电话。直接调系统 ACTION_CALL（不经确认弹窗）。
 *
 * 【权限前提】
 * CALL_PHONE 是运行时权限，用户首次使用会弹一次系统授权框。这是系统强制的，
 * 应用内无法绕过 —— 授权之后所有拨打都不再弹任何确认。
 *
 * 【名字纠错】
 * 模型传下来的名字可能和通讯录不一致（同音不同字、只记得读音）。
 * 匹配逻辑见 ContactMatcher，层层放宽直到找到人。
 *
 * 【为什么要把「匹配过程」告诉模型】
 * 如果模型说「王方」而实际拨给了「王芳」，模型需要知道这件事，
 * 才能在回复里说清楚「拨给了王芳（你说的可能是她）」，
 * 而不是理直气壮地说「已拨打王方」。所以返回值里带上候选和理由。
 */
class CallTool(private val context: Context) : Tool {

    override val name = "call_phone"

    override val description = """
        拨打电话。可以按联系人姓名或电话号码拨打。
        如果用户说「打电话给某某」，优先传姓名，系统会自动在通讯录里查找
        （支持同音字容错，比如「王方」能找到「王芳」）。
        如果用户直接给了号码，就传号码。
        如果通讯录里有多个人匹配，会返回候选列表，此时应该询问用户要拨给谁，不要擅自选一个。
    """.trimIndent()

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("name", buildJsonObject {
                put("type", "string")
                put("description", "联系人姓名，如「王芳」。和 number 二选一，优先用这个")
            })
            put("number", buildJsonObject {
                put("type", "string")
                put("description", "电话号码。用户直接给出号码时用这个")
            })
        })
        put("required", kotlinx.serialization.json.buildJsonArray {
            // 两个都不是必填（二选一），但至少要有一个 —— 运行时会校验
        })
    }

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val rawName = args["name"]?.jsonPrimitive?.contentOrNullSafe()?.trim().orEmpty()
        val rawNumber = args["number"]?.jsonPrimitive?.contentOrNullSafe()?.trim().orEmpty()

        if (rawName.isEmpty() && rawNumber.isEmpty()) {
            return@withContext ToolResult(false, "必须提供 name 或 number", "参数错误")
        }

        // 直接给了号码 —— 不用查通讯录
        if (rawNumber.isNotEmpty() && rawName.isEmpty()) {
            return@withContext dial(rawNumber, rawNumber)
        }

        if (!hasPermission(Manifest.permission.READ_CONTACTS)) {
            return@withContext ToolResult(
                false,
                "缺少读取通讯录权限。请让用户在系统设置里授予通讯录权限后重试，或直接提供电话号码。",
                "缺少通讯录权限",
            )
        }

        val candidates = loadContacts()
        if (candidates.isEmpty()) {
            return@withContext ToolResult(
                false,
                "通讯录里没有任何联系人。可以让用户直接提供电话号码。",
                "通讯录为空",
            )
        }

        val matches = ContactMatcher.match(rawName, candidates)

        if (matches.isEmpty()) {
            return@withContext ToolResult(
                false,
                "通讯录里找不到「$rawName」。可以询问用户正确的名字或电话号码。",
                "找不到「$rawName」",
            )
        }

        // 有多个同分候选 → 不擅自选，交回模型问用户
        val top = matches.first()
        val tied = matches.filter { it.score == top.score }
        if (tied.size > 1) {
            val list = tied.joinToString("、") { m ->
                "${m.candidate.name}（${m.candidate.numbers.firstOrNull() ?: "无号码"}）"
            }
            return@withContext ToolResult(
                false,
                "「$rawName」匹配到多个联系人：$list。请询问用户要拨给哪一个，不要自己选。",
                "匹配到 ${tied.size} 个「${rawName}」",
            )
        }

        val number = top.candidate.numbers.firstOrNull()
        if (number.isNullOrBlank()) {
            return@withContext ToolResult(
                false,
                "联系人「${top.candidate.name}」没有可拨打的号码。",
                "${top.candidate.name} 无号码",
            )
        }

        val note = if (top.candidate.name != rawName) {
            "（你说的「$rawName」匹配到了「${top.candidate.name}」，${top.reason}）"
        } else ""

        val result = dial(number, top.candidate.name)
        result.copy(
            content = result.content + note,
            displayTitle = "拨打 ${top.candidate.name}",
        )
    }

    private fun dial(number: String, displayName: String): ToolResult {
        if (!hasPermission(Manifest.permission.CALL_PHONE)) {
            return ToolResult(
                false,
                "缺少拨打电话权限。请让用户在系统设置里授予电话权限后重试。",
                "缺少电话权限",
            )
        }
        return try {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(number)}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ToolResult(
                true,
                "已拨打 $displayName（$number）。电话已经在拨出，用户能听到拨号音。",
                "拨打 $displayName",
            )
        } catch (e: Exception) {
            Log.e("CallTool", "拨号失败", e)
            ToolResult(false, "拨号失败：${e.message}", "拨号失败")
        }
    }

    private fun hasPermission(p: String): Boolean =
        ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    /**
     * 读通讯录。
     *
     * 用 Phone.CONTENT_URI 而不是 ContactsContract.Contacts：
     * 前者一次查询就带出号码，后者每个联系人都要再查一次号码（N+1 查询，
     * 几百个联系人时要几秒）。这里把 display name 和 number 一次捞完再按名字聚合。
     */
    private fun loadContacts(): List<ContactMatcher.Candidate> {
        val byName = mutableMapOf<String, MutableList<String>>()

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection, null, null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(projection[0])
                val numIdx = cursor.getColumnIndex(projection[1])
                while (cursor.moveToNext()) {
                    val n = cursor.getString(nameIdx)?.trim().orEmpty()
                    val num = cursor.getString(numIdx)?.trim().orEmpty()
                    if (n.isEmpty() || num.isEmpty()) continue
                    byName.getOrPut(n) { mutableListOf() }
                    if (num !in byName[n]!!) byName[n]!!.add(num)
                }
            }
        } catch (e: Exception) {
            Log.e("CallTool", "读通讯录失败", e)
        }

        return byName.map { (name, numbers) ->
            ContactMatcher.Candidate(
                name = name,
                numbers = numbers,
                pinyinName = Pinyin.fullOf(name.replace(" ", "")).orEmpty(),
            )
        }
    }

    /** jsonPrimitive 在字段是数字类型时会抛，这里统一转字符串。 */
    private fun JsonPrimitive.contentOrNullSafe(): String? =
        try {
            content
        } catch (_: Exception) {
            null
        }
}

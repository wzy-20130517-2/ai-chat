package com.aichat.app.core.network

/**
 * 硬编码的模型与工具配置（不走设置页，换模型直接改这里重新编译）。
 *
 * 【LLM 三级 fallback】
 * 按顺序尝试，前一个失败（网络错误 / 5xx / 429 / 超时 / 空回答）就换下一个。
 * 每级内部的超时重试见 [LLM_TIMEOUT_SEC] 与 [LLM_TIMEOUT_RETRIES]。
 */
object Config {

    // ─────────────── 对话模型（三级） ───────────────

    /**
     * 一级：主用站。
     * rgzzapi 上用 deepseek-v4.1-flash。
     */
    data class LlmEndpoint(
        val name: String,
        val baseUrl: String,
        val apiKey: String,
        val model: String,
    )

    val LLM_ENDPOINTS: List<LlmEndpoint> = listOf(
        LlmEndpoint(
            name = "主站",
            baseUrl = "https://rgzzapi.weko143.cn/v1",
            apiKey = "sk-fWhOVkAZGSerqjZsEU0isFVs2LFn0dUZi1R3Yk0k1Ltwid4G",
            model = "deepseek-v4.1-flash",
        ),
        LlmEndpoint(
            name = "备用1",
            baseUrl = "https://gs.mfawa.top/v1",
            apiKey = "sk-VIVEu7MQwv3loXXKA7aqS4u6R0dvAAff8Q9CMOQqqo33GNTi",
            model = "glm-5.3-flash",
        ),
        LlmEndpoint(
            name = "备用2",
            baseUrl = "https://gs.mfawa.top/v1",
            apiKey = "sk-VIVEu7MQwv3loXXKA7aqS4u6R0dvAAff8Q9CMOQqqo33GNTi",
            model = "deepseek-v4.1-flash",
        ),
    )

    /** 首字超时（秒）—— 20 秒没有任何输出就判定卡死。 */
    const val FIRST_TOKEN_TIMEOUT_SEC = 20L

    /** 首字超时后的重试次数（同一端点上）。 */
    const val FIRST_TOKEN_RETRIES = 2

    /** 空回答（有 finish 但正文和工具调用都为空）的重试次数。 */
    const val EMPTY_REPLY_RETRIES = 2

    /** 连接超时（秒）。比首字超时短，连接阶段不该拖那么久。 */
    const val CONNECT_TIMEOUT_SEC = 15L

    /** 整条流的总超时（秒）。防止一直不断但极慢地吐字。 */
    const val STREAM_TOTAL_TIMEOUT_SEC = 300L

    /**
     * 单次回复的 max_tokens。
     *
     * 32768：长回答（含代码）留足空间，同时不至于把中转站的额度一次吃光。
     */
    const val MAX_OUTPUT_TOKENS = 32_768

    /** 单轮对话最多几轮工具调用（防止模型死循环调工具）。 */
    const val MAX_TOOL_ROUNDS = 8

    // ─────────────── 语音识别（ASR） ───────────────

    const val ASR_BASE_URL = "https://gs.mfawa.top/v1"
    const val ASR_API_KEY = "sk-hMvp8fotnJ8p2PeEyQudW9XjhYJmXPhukCZP2AZ2r46ioH4s"

    /**
     * ASR 型号按顺序轮换。
     * 实测四个型号都在线；ultra 最快，diarize 带说话人分离但更慢，放最后当兜底。
     */
    val ASR_MODELS = listOf(
        "xingchenasr-v3.2-ultra",
        "xingchenasr-v3.2",
        "xingchenasr-diarize-v3.0",
        "xingchengsr-v1.0",
    )

    // ─────────────── 联网搜索（Tavily） ───────────────

    const val TAVILY_API_KEY = "tvly-REPLACE_ME"
    const val TAVILY_ENDPOINT = "https://api.tavily.com/search"

    /** ultra-fast：延迟最低，每个结果一个摘要片段。 */
    const val TAVILY_SEARCH_DEPTH = "ultra-fast"
    const val TAVILY_MAX_RESULTS = 5
}

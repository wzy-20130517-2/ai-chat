package com.aichat.app.core.network

/**
 * 硬编码的 LLM 与工具配置。
 *
 * 这些值按需求是写死的（不走设置页）。换模型 / 换 key 直接改这里重新编译。
 */
object Config {

    // ─────────────── 对话模型 ───────────────

    /**
     * 用 OpenAI 兼容协议（/chat/completions）。
     * 换成 Anthropic 原生协议要改 LlmClient 的请求体与解析，不是改一个字符串就行。
     */
    const val LLM_BASE_URL = "https://api.deepseek.com"
    const val LLM_API_KEY = "sk-REPLACE_ME"
    const val LLM_MODEL = "deepseek-chat"

    /** 是否支持 reasoning（深度思考）。关掉时按钮禁用。 */
    const val LLM_SUPPORTS_REASONING = true

    // ─────────────── 联网搜索（Tavily） ───────────────

    const val TAVILY_API_KEY = "tvly-REPLACE_ME"
    const val TAVILY_ENDPOINT = "https://api.tavily.com/search"

    /**
     * ultra-fast：延迟最低，返回单个摘要片段。
     * 另外两档：basic（默认，多片段）、advanced（2 credits，带更深的抓取）。
     */
    const val TAVILY_SEARCH_DEPTH = "ultra-fast"

    const val TAVILY_MAX_RESULTS = 5

    // ─────────────── 语音识别（ASR） ───────────────

    /**
     * 与 deepseek chat-demo 相同的接口形状：
     * POST /v1/tools/asr，multipart 上传 WAV，返回 {"text": "..."}
     */
    const val ASR_BASE_URL = "https://api.deepseek.com"
    const val ASR_API_KEY = "sk-REPLACE_ME"
    const val ASR_PATH = "/v1/tools/asr"

    // ─────────────── 网络行为 ───────────────

    /** 单次请求的连接/读超时（秒）。流式读超时要长，不然长回复会被掐断。 */
    const val CONNECT_TIMEOUT_SEC = 20L
    const val READ_TIMEOUT_SEC = 300L

    /** 自动重试：最多重试次数（不含首次）。 */
    const val MAX_RETRIES = 4

    /** 首次重试等待毫秒，之后指数退避（1s → 2s → 4s → 8s）。 */
    const val RETRY_BASE_DELAY_MS = 1000L

    /** 单轮对话最多几轮工具调用（防止模型死循环调工具）。 */
    const val MAX_TOOL_ROUNDS = 8
}

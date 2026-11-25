package com.aichat.app.core.audio

/**
 * 把流式文本切成适合逐段合成的片段。
 *
 * 【为什么要分段，而不是整段合成】
 * Edge TTS 是「发 SSML → 收 MP3 分片」的网络请求。整段合成必须等模型把话说完
 * 才能发出请求，用户要等到最后才听到第一个字。分段后可以「边生成边合成边播放」，
 * 第一段到达就能出声，体感延迟从「等全文」降到「等第一句」。
 *
 * 【为什么不能按字数硬切】
 * 硬切会把词切断（"人工" | "智能"），合成出来两个片段各自朗读、中间有明显停顿。
 * 必须在句读处切：句号、问号、叹号、分号，其次是逗号、顿号。
 *
 * 【为什么首段要有最小长度】
 * 实测首段太短（比如只有「好的」两个字）时，网络合成速度比播放速度快完 ——
 * 第一个片段 0.3 秒就播完了，第二段还没到，中间反而出现空档。
 * 所以首段至少攒够约 12 个中文字再切，之后的段落可以更短。
 */
object TtsSegmenter {

    /** 一级边界：句末标点，切在这里停顿时长最自然。 */
    private val PRIMARY = charArrayOf('。', '！', '？', '.', '!', '?', '；', ';', '\n')

    /** 二级边界：句中标点，一级边界迟迟不出现时兜底。 */
    private val SECONDARY = charArrayOf('，', '、', ',', '：', ':', '—', '…')

    /** 首段最少字符数（约 12 个中文字）。 */
    private const val MIN_FIRST_CHUNK = 12

    /** 后续片段最少字符数。首段之后播放已经跟上，可以更碎。 */
    private const val MIN_CHUNK = 6

    /** 单段上限，防止模型一口气写极长句子（没有标点）时攒出超大请求。 */
    private const val MAX_CHUNK = 120

    /**
     * 从缓冲区头部切出可以送去合成的片段。
     *
     * @param buffer 累积的待合成文本
     * @param flush 流是否已结束。true 时把剩余内容全部吐出（不管够不够长）
     * @param isFirst 是否是本次朗读的第一个片段（首段用更严格的最小长度）
     * @return 切出的片段列表 + 剩余缓冲
     */
    fun take(buffer: String, flush: Boolean, isFirst: Boolean): Pair<List<String>, String> {
        val parts = mutableListOf<String>()
        var rest = clean(buffer)
        val minLen = if (isFirst) MIN_FIRST_CHUNK else MIN_CHUNK

        while (true) {
            val cut = findCut(rest, minLen)
            if (cut <= 0) break
            val piece = rest.substring(0, cut).trim()
            if (piece.isNotEmpty()) parts += piece
            rest = rest.substring(cut)
        }

        if (flush && rest.isNotBlank()) {
            parts += rest.trim()
            rest = ""
        }

        return parts to rest
    }

    /**
     * 找切点。返回切分位置（该位置之前的字符成为一段），0 表示还不够长、不切。
     *
     * 策略：先在 minLen 之后找一级边界；找不到就找二级边界；
     * 都找不到但已经超过 MAX_CHUNK，就强制在 MAX_CHUNK 处切（宁可断词也不能攒无限大）。
     */
    private fun findCut(text: String, minLen: Int): Int {
        if (text.length < minLen) return 0

        // 一级边界：切在标点之后（标点留在前一段，合成时语气更完整）
        for (i in minLen - 1 until text.length) {
            if (text[i] in PRIMARY) {
                return i + 1
            }
        }
        // 二级边界
        for (i in minLen - 1 until text.length) {
            if (text[i] in SECONDARY) {
                return i + 1
            }
        }
        // 兜底：没有标点但已经很长了
        if (text.length >= MAX_CHUNK) {
            // 尽量在空格处切（英文句子）
            val space = text.lastIndexOf(' ', MAX_CHUNK)
            return if (space > minLen) space + 1 else MAX_CHUNK
        }
        return 0
    }

    /**
     * 清掉不该朗读的内容。
     *
     * 代码块、表格、URL 念出来毫无意义且极其冗长（一个代码块能念两分钟）。
     * 这里在分段前先剥掉，而不是交给服务端过滤 —— 服务端的 filter_markdown
     * 是整段处理，分段发送时每段都要重新判断，不如本地一次清干净。
     */
    fun clean(text: String): String {
        var s = text
        // 围栏代码块
        s = s.replace(Regex("```[\\s\\S]*?```"), " ")
        // 行内代码保留内容但去掉反引号
        s = s.replace(Regex("`([^`]*)`"), "$1")
        // 图片
        s = s.replace(Regex("!\\[[^\\]]*]\\([^)]*\\)"), " ")
        // 链接只留文字
        s = s.replace(Regex("\\[([^\\]]*)]\\([^)]*\\)"), "$1")
        // 裸 URL
        s = s.replace(Regex("https?://\\S+"), " ")
        // 表格分隔行
        s = s.replace(Regex("^\\s*\\|?[\\s:-]*\\|[\\s:|-]*$", RegexOption.MULTILINE), " ")
        // markdown 标记符号
        s = s.replace(Regex("[#*_~>]+"), " ")
        // 多余空白
        s = s.replace(Regex("\\s+"), " ")
        return s.trim()
    }
}

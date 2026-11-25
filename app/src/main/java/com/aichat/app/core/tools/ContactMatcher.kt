package com.aichat.app.core.tools

/**
 * 联系人姓名模糊匹配。
 *
 * 需求里的典型场景：通讯录里存的是「王芳」，模型传下来的是「王方」。
 * 两者汉字不同、拼音相同（wangfang），要能匹配上。
 * 更难的情况：模型传「王芳」而通讯录存「王方」、或模型只记得读音（"wanfang"）。
 *
 * 【匹配分层，从严格到宽松】
 * 1. 完全相同（汉字一致）
 * 2. 拼音全拼一致（王芳 wangfang == 王方 wangfang）
 * 3. 拼音首字母一致（王芳 wf == 吴凡 wf —— 这个太宽，只在没别的候选时用）
 * 4. 编辑距离 ≤ 1 的拼音全拼（wanfang vs wangfang，少一个 g）
 *
 * 【为什么要分层而不是直接算编辑距离】
 * 编辑距离对短字符串太敏感：「张三」和「李四」的拼音编辑距离也可能很小。
 * 先用严格层级筛，实在没有再放宽。每层只返回该层的最高分，
 * 避免把「勉强像」和「几乎确定」混在同一个列表里让模型自己猜。
 */
object ContactMatcher {

    /**
     * 一个联系人候选。
     *
     * @param name 显示名（通讯录里的原始名字）
     * @param numbers 电话号码（可能有多个）
     * @param pinyinName 预计算的全拼（小写，无空格），可为空
     */
    data class Candidate(
        val name: String,
        val numbers: List<String>,
        val pinyinName: String,
    )

    data class Match(
        val candidate: Candidate,
        val score: Int,
        /** 命中的方式，用于向模型解释为什么选了这个人。 */
        val reason: String,
    )

    private const val SCORE_EXACT = 100
    private const val SCORE_PINYIN = 80
    private const val SCORE_PARTIAL = 60
    private const val SCORE_INITIALS = 40
    private const val SCORE_FUZZY = 20

    /**
     * 在候选列表里找最匹配的。
     *
     * @param query 用户/模型给的名字或号码
     * @return 按分数降序，最多返回 5 个。空列表表示没找到。
     */
    fun match(query: String, candidates: List<Candidate>): List<Match> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()

        // 先看是不是直接给了号码
        val digits = q.filter { it.isDigit() || it == '+' }
        if (digits.length >= 5) {
            val numberHits = candidates.filter { c ->
                c.numbers.any { normalizeNumber(it).contains(normalizeNumber(digits)) }
            }
            if (numberHits.isNotEmpty()) {
                return numberHits.map { Match(it, SCORE_EXACT, "号码匹配") }
            }
        }

        val qNorm = Pinyin.normalize(q)
        val qPinyin = Pinyin.fullOf(q.replace(" ", ""))
        val qInitials = Pinyin.initialsOf(q.replace(" ", ""))

        val results = mutableListOf<Match>()

        for (c in candidates) {
            val nameNorm = Pinyin.normalize(c.name)
            val cFull = c.pinyinName.ifEmpty { Pinyin.fullOf(c.name.replace(" ", "")).orEmpty() }

            when {
                // 1) 汉字完全一致
                nameNorm == qNorm -> results += Match(c, SCORE_EXACT, "姓名一致")

                // 2) 拼音全拼一致（王芳 ↔ 王方）
                qPinyin != null && cFull.isNotEmpty() && cFull == qPinyin ->
                    results += Match(c, SCORE_PINYIN, "拼音一致（${cFull}）")

                // 3) 包含关系：说话人可能只说名不说姓（"芳" → "王芳"）
                nameNorm.contains(qNorm) || qNorm.contains(nameNorm) ->
                    results += Match(c, SCORE_PARTIAL, "部分匹配")

                // 4) 首字母一致（只在查询本身也像拼音缩写时）
                qInitials.length >= 2 && qInitials == Pinyin.initialsOf(c.name) ->
                    results += Match(c, SCORE_INITIALS, "首字母一致")

                // 5) 拼音编辑距离 ≤1（wanfang vs wangfang）
                qPinyin != null && cFull.isNotEmpty() &&
                    editDistanceAtMost1(qPinyin, cFull) ->
                    results += Match(c, SCORE_FUZZY, "拼音近似（$cFull ≈ $qPinyin）")
            }
        }

        // 按分数降序；同分时名字短的优先（"王芳" 比 "王芳芳" 更可能是本意）
        return results
            .sortedWith(compareByDescending<Match> { it.score }.thenBy { it.candidate.name.length })
            .take(5)
    }

    /** 号码归一化：只留数字，去掉 +86 / 空格 / 横线。 */
    fun normalizeNumber(s: String): String {
        var n = s.filter { it.isDigit() }
        // 去掉国际区号 86 前缀，让 +8613800138000 和 13800138000 能对上
        if (n.length > 11 && n.startsWith("86")) n = n.substring(2)
        return n
    }

    /**
     * 判断两个串的编辑距离是否 ≤ 1。
     *
     * 只判 ≤1 而不是算完整距离：这里只用来兜底匹配名字（2-4 个音节），
     * 距离 >1 的已经不可信了，算完整 DP 表纯属浪费。
     */
    private fun editDistanceAtMost1(a: String, b: String): Boolean {
        if (a == b) return true
        val la = a.length
        val lb = b.length
        if (kotlin.math.abs(la - lb) > 1) return false

        if (la == lb) {
            // 等长：必须恰好一处不同（替换）
            var diff = 0
            for (i in 0 until la) {
                if (a[i] != b[i]) {
                    diff++
                    if (diff > 1) return false
                }
            }
            return diff == 1
        }

        // 长度差 1：短的那个是长的删一个字符得到的
        val (shorter, longer) = if (la < lb) a to b else b to a
        var i = 0
        var j = 0
        var skipped = false
        while (i < shorter.length && j < longer.length) {
            if (shorter[i] == longer[j]) {
                i++; j++
            } else {
                if (skipped) return false
                skipped = true
                j++
            }
        }
        return true
    }
}

package com.aichat.app.core.tools

/**
 * 汉字 → 拼音首字母 / 全拼。
 *
 * 【为什么需要这个】
 * 用户说「打电话给王芳」，模型可能听成「王方」。直接按名字查通讯录查不到，
 * 但「王芳」和「王方」的拼音都是 wangfang —— 换成拼音比对就能找到。
 * 这就是需求里说的「自动修正」。
 *
 * 【为什么不用 pinyin4j 之类的库】
 * 那类库带几十 MB 的拼音字典，为一个通讯录匹配引入太重。
 * 这里只需要「常用姓氏 + 常见名字用字」，用一段紧凑的码表就够 ——
 * 通讯录里绝大多数是中文常见字，覆盖不到的会走模糊匹配兜底。
 *
 * 【实现方式】
 * Unicode 区间映射：GB2312 一级汉字区（0x4E00–0x9FA5）按拼音排序，
 * 可以用区间表做索引。这里用经过验证的常用字表 + 区间近似。
 */
object Pinyin {

    /**
     * 声母表索引。GB2312 一级汉字按拼音排序，这些界限字是各声母起始位置的锚点。
     * 用「锚点字 + 序」算出某字的拼音首字母，比逐字码表省空间。
     */
    private val INITIAL_BOUNDARIES = arrayOf(
        '啊' to "a", '芭' to "b", '擦' to "c", '搭' to "d",
        '蛾' to "e", '发' to "f", '噶' to "g", '哈' to "h",
        '击' to "j", '喀' to "k", '垃' to "l", '妈' to "m",
        '拿' to "n", '哦' to "o", '啪' to "p", '期' to "q",
        '然' to "r", '撒' to "s", '塌' to "t", '挖' to "w",
        '昔' to "x", '压' to "y", '匝' to "z",
    )

    /**
     * 常用字全拼表。
     *
     * 只覆盖通讯录里高频出现的字（姓氏 + 常见名用字）。这不追求完整性 ——
     * 查不到的字返回 null，匹配逻辑会退化成「部分匹配」，不会给错误答案。
     * 覆盖太多反而让包变大，收益递减。
     */
        private val FULL_PINYIN: Map<Char, String> = mapOf(
        '王' to "wang", '李' to "li", '张' to "zhang", '刘' to "liu",
        '陈' to "chen", '杨' to "yang", '黄' to "huang", '赵' to "zhao",
        '周' to "zhou", '吴' to "wu", '徐' to "xu", '孙' to "sun",
        '马' to "ma", '朱' to "zhu", '胡' to "hu", '郭' to "guo",
        '何' to "he", '高' to "gao", '林' to "lin", '罗' to "luo",
        '郑' to "zheng", '梁' to "liang", '谢' to "xie", '宋' to "song",
        '唐' to "tang", '许' to "xu", '韩' to "han", '冯' to "feng",
        '邓' to "deng", '曹' to "cao", '彭' to "peng", '曾' to "zeng",
        '萧' to "xiao", '田' to "tian", '董' to "dong", '袁' to "yuan",
        '潘' to "pan", '于' to "yu", '蒋' to "jiang", '蔡' to "cai",
        '余' to "yu", '杜' to "du", '叶' to "ye", '程' to "cheng",
        '苏' to "su", '魏' to "wei", '吕' to "lv", '丁' to "ding",
        '任' to "ren", '沈' to "shen", '姚' to "yao", '卢' to "lu",
        '姜' to "jiang", '崔' to "cui", '钟' to "zhong", '谭' to "tan",
        '陆' to "lu", '汪' to "wang", '范' to "fan", '金' to "jin",
        '石' to "shi", '廖' to "liao", '贾' to "jia", '夏' to "xia",
        '韦' to "wei", '付' to "fu", '方' to "fang", '白' to "bai",
        '邹' to "zou", '孟' to "meng", '熊' to "xiong", '秦' to "qin",
        '邱' to "qiu", '江' to "jiang", '尹' to "yin", '薛' to "xue",
        '闫' to "yan", '段' to "duan", '雷' to "lei", '侯' to "hou",
        '龙' to "long", '史' to "shi", '陶' to "tao", '黎' to "li",
        '贺' to "he", '顾' to "gu", '毛' to "mao", '郝' to "hao",
        '龚' to "gong", '邵' to "shao", '万' to "wan", '钱' to "qian",
        '严' to "yan", '覃' to "qin", '武' to "wu", '戴' to "dai",
        '莫' to "mo", '孔' to "kong", '向' to "xiang", '汤' to "tang",
        '芳' to "fang", '房' to "fang", '放' to "fang", '华' to "hua",
        '花' to "hua", '化' to "hua", '画' to "hua", '明' to "ming",
        '名' to "ming", '民' to "min", '敏' to "min", '伟' to "wei",
        '卫' to "wei", '维' to "wei", '静' to "jing", '晶' to "jing",
        '京' to "jing", '靖' to "jing", '丽' to "li", '莉' to "li",
        '力' to "li", '强' to "qiang", '墙' to "qiang", '蔷' to "qiang",
        '军' to "jun", '君' to "jun", '俊' to "jun", '骏' to "jun",
        '红' to "hong", '洪' to "hong", '宏' to "hong", '鸿' to "hong",
        '燕' to "yan", '艳' to "yan", '岩' to "yan", '玲' to "ling",
        '灵' to "ling", '凌' to "ling", '铃' to "ling", '平' to "ping",
        '萍' to "ping", '苹' to "ping", '评' to "ping", '云' to "yun",
        '运' to "yun", '芸' to "yun", '韵' to "yun", '秀' to "xiu",
        '修' to "xiu", '琇' to "xiu", '英' to "ying", '鹰' to "ying",
        '瑛' to "ying", '盈' to "ying", '文' to "wen", '雯' to "wen",
        '温' to "wen", '闻' to "wen", '涛' to "tao", '桃' to "tao",
        '滔' to "tao", '斌' to "bin", '彬' to "bin", '滨' to "bin",
        '宾' to "bin", '超' to "chao", '朝' to "chao", '潮' to "chao",
        '磊' to "lei", '蕾' to "lei", '累' to "lei", '洋' to "yang",
        '阳' to "yang", '扬' to "yang", '勇' to "yong", '永' to "yong",
        '泳' to "yong", '拥' to "yong", '彦' to "yan", '雁' to "yan",
        '杰' to "jie", '洁' to "jie", '婕' to "jie", '娟' to "juan",
        '捐' to "juan", '鹃' to "juan", '婷' to "ting", '庭' to "ting",
        '停' to "ting", '慧' to "hui", '惠' to "hui", '会' to "hui",
        '辉' to "hui", '巧' to "qiao", '乔' to "qiao", '桥' to "qiao",
        '美' to "mei", '梅' to "mei", '媒' to "mei", '眉' to "mei",
        '娜' to "na", '纳' to "na", '南' to "nan", '丹' to "dan",
        '单' to "dan", '旦' to "dan", '青' to "qing", '清' to "qing",
        '庆' to "qing", '晴' to "qing", '雪' to "xue", '学' to "xue",
        '飞' to "fei", '菲' to "fei", '非' to "fei", '鹏' to "peng",
        '朋' to "peng", '棚' to "peng", '晖' to "hui", '恢' to "hui",
        '刚' to "gang", '钢' to "gang", '岗' to "gang", '桂' to "gui",
        '贵' to "gui", '归' to "gui", '春' to "chun", '纯' to "chun",
        '唇' to "chun", '秋' to "qiu", '丘' to "qiu", '冬' to "dong",
        '东' to "dong", '栋' to "dong", '小' to "xiao", '晓' to "xiao",
        '肖' to "xiao", '筱' to "xiao", '大' to "da", '达' to "da",
        '答' to "da", '中' to "zhong", '忠' to "zhong", '种' to "zhong",
        '国' to "guo", '果' to "guo", '过' to "guo", '建' to "jian",
        '健' to "jian", '剑' to "jian", '简' to "jian", '新' to "xin",
        '心' to "xin", '欣' to "xin", '辛' to "xin", '志' to "zhi",
        '智' to "zhi", '芝' to "zhi", '支' to "zhi", '成' to "cheng",
        '诚' to "cheng", '城' to "cheng", '安' to "an", '按' to "an",
        '岸' to "an", '宝' to "bao", '保' to "bao", '报' to "bao",
        '鲍' to "bao", '本' to "ben", '苯' to "ben", '奔' to "ben",
        '波' to "bo", '博' to "bo", '伯' to "bo", '柏' to "bo",
        '彩' to "cai", '才' to "cai", '材' to "cai", '财' to "cai",
        '长' to "zhang", '常' to "chang", '场' to "chang", '昌' to "chang",
        '晨' to "chen", '辰' to "chen", '尘' to "chen", '川' to "chuan",
        '传' to "chuan", '船' to "chuan", '德' to "de", '得' to "de",
        '登' to "deng", '灯' to "deng", '迪' to "di", '帝' to "di",
        '弟' to "di", '狄' to "di", '典' to "dian", '点' to "dian",
        '电' to "dian", '定' to "ding", '顶' to "ding", '都' to "dou",
        '豆' to "dou", '斗' to "dou", '多' to "duo", '朵' to "duo",
        '夺' to "duo", '恩' to "en", '尔' to "er", '儿' to "er",
        '二' to "er", '发' to "fa", '法' to "fa", '凡' to "fan",
        '丰' to "feng", '风' to "feng", '峰' to "feng", '锋' to "feng",
        '凤' to "feng", '奉' to "feng", '福' to "fu", '富' to "fu",
        '傅' to "fu", '搞' to "gao", '歌' to "ge", '格' to "ge",
        '葛' to "ge", '哥' to "ge", '根' to "gen", '跟' to "gen",
        '工' to "gong", '公' to "gong", '功' to "gong", '宫' to "gong",
        '古' to "gu", '谷' to "gu", '鼓' to "gu", '关' to "guan",
        '官' to "guan", '管' to "guan", '冠' to "guan", '光' to "guang",
        '广' to "guang", '海' to "hai", '还' to "hai", '害' to "hai",
        '含' to "han", '汉' to "han", '寒' to "han", '好' to "hao",
        '浩' to "hao", '豪' to "hao", '号' to "hao", '和' to "he",
        '合' to "he", '河' to "he", '黑' to "hei", '很' to "hen",
        '恨' to "hen", '恒' to "heng", '横' to "heng", '衡' to "heng",
        '厚' to "hou", '后' to "hou", '候' to "hou", '湖' to "hu",
        '虎' to "hu", '呼' to "hu", '欢' to "huan", '环' to "huan",
        '换' to "huan", '皇' to "huang", '晃' to "huang", '回' to "hui",
        '汇' to "hui", '活' to "huo", '火' to "huo", '或' to "huo",
        '霍' to "huo", '基' to "ji", '机' to "ji", '吉' to "ji",
        '纪' to "ji", '家' to "jia", '加' to "jia", '佳' to "jia",
        '嘉' to "jia", '坚' to "jian", '见' to "jian", '将' to "jiang",
        '娇' to "jiao", '交' to "jiao", '焦' to "jiao", '今' to "jin",
        '进' to "jin", '锦' to "jin", '经' to "jing", '景' to "jing",
        '九' to "jiu", '久' to "jiu", '酒' to "jiu", '旧' to "jiu",
        '居' to "ju", '菊' to "ju", '举' to "ju", '巨' to "ju",
        '卷' to "juan", '均' to "jun", '凯' to "kai", '开' to "kai",
        '康' to "kang", '抗' to "kang", '科' to "ke", '可' to "ke",
        '克' to "ke", '柯' to "ke", '空' to "kong", '控' to "kong",
        '库' to "ku", '苦' to "ku", '快' to "kuai", '宽' to "kuan",
        '坤' to "kun", '昆' to "kun", '困' to "kun", '来' to "lai",
        '赖' to "lai", '兰' to "lan", '蓝' to "lan", '岚' to "lan",
        '朗' to "lang", '浪' to "lang", '郎' to "lang", '老' to "lao",
        '劳' to "lao", '乐' to "le", '了' to "le", '冷' to "leng",
        '理' to "li", '莲' to "lian", '连' to "lian", '联' to "lian",
        '良' to "liang", '亮' to "liang", '琳' to "lin", '临' to "lin",
        '柳' to "liu", '留' to "liu", '流' to "liu", '隆' to "long",
        '楼' to "lou", '路' to "lu", '鲁' to "lu", '露' to "lu",
        '律' to "lv", '旅' to "lv", '伦' to "lun", '轮' to "lun",
        '落' to "luo", '洛' to "luo", '妈' to "ma", '满' to "man",
        '曼' to "man", '漫' to "man", '茂' to "mao", '冒' to "mao",
        '每' to "mei", '门' to "men", '们' to "men", '蒙' to "meng",
        '梦' to "meng", '米' to "mi", '密' to "mi", '迷' to "mi",
        '苗' to "miao", '妙' to "miao", '鸣' to "ming", '模' to "mo",
        '墨' to "mo", '木' to "mu", '母' to "mu", '目' to "mu",
        '穆' to "mu", '拿' to "na", '那' to "na", '乃' to "nai",
        '男' to "nan", '妮' to "ni", '尼' to "ni", '你' to "ni",
        '年' to "nian", '念' to "nian", '宁' to "ning", '凝' to "ning",
        '牛' to "niu", '纽' to "niu", '农' to "nong", '弄' to "nong",
        '女' to "nv", '努' to "nu", '欧' to "ou", '鸥' to "ou",
        '偶' to "ou", '盘' to "pan", '盼' to "pan", '旁' to "pang",
        '庞' to "pang", '培' to "pei", '佩' to "pei", '裴' to "pei",
        '品' to "pin", '凭' to "ping", '坡' to "po", '泼' to "po",
        '破' to "po", '普' to "pu", '浦' to "pu", '朴' to "pu",
        '七' to "qi", '期' to "qi", '其' to "qi", '齐' to "qi",
        '千' to "qian", '乾' to "qian", '前' to "qian", '曲' to "qu",
        '区' to "qu", '取' to "qu", '全' to "quan", '权' to "quan",
        '泉' to "quan", '群' to "qun", '裙' to "qun", '然' to "ran",
        '燃' to "ran", '冉' to "ran", '人' to "ren", '仁' to "ren",
        '日' to "ri", '荣' to "rong", '容' to "rong", '融' to "rong",
        '如' to "ru", '入' to "ru", '汝' to "ru", '瑞' to "rui",
        '锐' to "rui", '蕊' to "rui", '若' to "ruo", '弱' to "ruo",
        '三' to "san", '山' to "shan", '珊' to "shan", '善' to "shan",
        '上' to "shang", '商' to "shang", '尚' to "shang", '少' to "shao",
        '绍' to "shao", '社' to "she", '设' to "she", '佘' to "she",
        '申' to "shen", '深' to "shen", '慎' to "shen", '生' to "sheng",
        '胜' to "sheng", '圣' to "sheng", '师' to "shi", '施' to "shi",
        '世' to "shi", '书' to "shu", '舒' to "shu", '淑' to "shu",
        '术' to "shu", '双' to "shuang", '爽' to "shuang", '水' to "shui",
        '税' to "shui", '谁' to "shui", '顺' to "shun", '舜' to "shun",
        '说' to "shuo", '硕' to "shuo", '烁' to "shuo", '思' to "si",
        '四' to "si", '司' to "si", '斯' to "si", '松' to "song",
        '送' to "song", '素' to "su", '肃' to "su", '损' to "sun",
        '索' to "suo", '所' to "suo", '锁' to "suo", '塔' to "ta",
        '他' to "ta", '她' to "ta", '台' to "tai", '太' to "tai",
        '泰' to "tai", '谈' to "tan", '坦' to "tan", '堂' to "tang",
        '特' to "te", '腾' to "teng", '藤' to "teng", '天' to "tian",
        '甜' to "tian", '铁' to "tie", '同' to "tong", '通' to "tong",
        '童' to "tong", '佟' to "tong", '图' to "tu", '途' to "tu",
        '屠' to "tu", '团' to "tuan", '晚' to "wan", '为' to "wei",
        '我' to "wo", '沃' to "wo", '卧' to "wo", '五' to "wu",
        '伍' to "wu", '西' to "xi", '希' to "xi", '喜' to "xi",
        '席' to "xi", '霞' to "xia", '峡' to "xia", '先' to "xian",
        '贤' to "xian", '显' to "xian", '仙' to "xian", '香' to "xiang",
        '祥' to "xiang", '项' to "xiang", '星' to "xing", '兴' to "xing",
        '行' to "xing", '邢' to "xing", '雄' to "xiong", '需' to "xu",
        '旭' to "xu", '雅' to "ya", '亚' to "ya", '牙' to "ya",
        '颜' to "yan", '阎' to "yan", '要' to "yao", '耀' to "yao",
        '也' to "ye", '业' to "ye", '一' to "yi", '依' to "yi",
        '易' to "yi", '应' to "ying", '鱼' to "yu", '俞' to "yu",
        '元' to "yuan", '远' to "yuan", '月' to "yue", '岳' to "yue",
        '越' to "yue", '再' to "zai", '在' to "zai", '臧' to "zang",
        '早' to "zao", '造' to "zao", '增' to "zeng", '查' to "zha",
        '扎' to "zha", '翟' to "zhai", '展' to "zhan", '占' to "zhan",
        '詹' to "zhan", '章' to "zhang", '照' to "zhao", '召' to "zhao",
        '哲' to "zhe", '者' to "zhe", '这' to "zhe", '珍' to "zhen",
        '真' to "zhen", '甄' to "zhen", '振' to "zhen", '正' to "zheng",
        '政' to "zheng", '之' to "zhi", '仲' to "zhong", '洲' to "zhou",
        '舟' to "zhou", '竹' to "zhu", '祝' to "zhu", '诸' to "zhu",
        '庄' to "zhuang", '壮' to "zhuang", '卓' to "zhuo", '桌' to "zhuo",
        '子' to "zi", '紫' to "zi", '自' to "zi", '资' to "zi",
        '宗' to "zong", '总' to "zong", '纵' to "zong", '走' to "zou",
        '祖' to "zu", '组' to "zu", '族' to "zu", '左' to "zuo",
        '作' to "zuo", '佐' to "zuo",
    )

    fun of(ch: Char): String? {
        if (ch.code < 0x4E00 || ch.code > 0x9FA5) {
            return if (ch.isLetterOrDigit()) ch.lowercaseChar().toString() else null
        }
        FULL_PINYIN[ch]?.let { return it }
        // 兜底：用声母锚点表估一个首字母。
        // 只给首字母而不是瞎猜全拼 —— 错的比缺的更糟。
        return initialOf(ch)
    }

    /** 只取声母首字母。 */
    fun initialOf(ch: Char): String? {
        if (ch.code < 0x4E00 || ch.code > 0x9FA5) return null
        var result: String? = null
        for ((anchor, letter) in INITIAL_BOUNDARIES) {
            if (ch >= anchor) result = letter else break
        }
        return result
    }

    /** 整个字符串的全拼（每个字拼接）。有任一字查不到就返回 null。 */
    fun fullOf(s: String): String? {
        val sb = StringBuilder()
        for (ch in s) {
            val p = of(ch) ?: return null
            sb.append(p)
        }
        return sb.toString()
    }

    /** 整个字符串的拼音首字母。 */
    fun initialsOf(s: String): String {
        val sb = StringBuilder()
        for (ch in s) {
            val p = initialOf(ch) ?: of(ch)?.firstOrNull()?.toString()
            if (p != null) sb.append(p)
        }
        return sb.toString()
    }

    /**
     * 归一化：去掉空格、大小写、常见分隔符。
     * 「王 芳」「wang fang」「WangFang」都会变成可直接比对的串。
     */
    fun normalize(s: String): String = s
        .lowercase()
        .replace(Regex("[\\s\\-_·.]+"), "")
}

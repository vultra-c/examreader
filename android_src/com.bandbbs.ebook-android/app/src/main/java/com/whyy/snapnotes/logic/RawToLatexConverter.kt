package com.whyy.snapnotes.logic

/**
 * raw(Unicode 公式文本) → LaTeX 转换器。
 *
 * 与手环内置公式图链路同源（`_latex_table.js` 168 条精确映射 + KaTeX 0.18.1 渲染）。
 * 精确表命中直接返回；未命中走兜底规约转换，让用户自定义公式也能渲染成与内置
 * 视觉一致的公式图。KaTeX 0.18.1 对常见 Unicode 数学符号（∪ ∩ ∈ ± ≥ ≤ → ⇒ 等）
 * 自带支持，兜底只需处理 KaTeX 不认的写法：根号/上标括号、中文、½ 等。
 */
object RawToLatexConverter {

    fun convert(raw: String): String {
        if (raw.isBlank()) return raw
        LATEX_TABLE[raw]?.let { return it }
        return fallbackConvert(raw)
    }

    private fun fallbackConvert(raw: String): String {
        var s = raw
        s = replaceSqrt(s)
        s = replaceSupSubParen(s)
        // 上标 Unicode（² ³ ⁰ ¹ ⁴ ⁻ 等）KaTeX 0.18 原生支持渲染，不做转换，避免拆坏 10⁻¹⁴ 这类组合。
        s = s.replace("½", "\\frac{1}{2}")
        s = wrapCjkAsText(s)
        return s
    }

    /**
     * √(…) → \sqrt{…}；³√(…) → \sqrt[3]{…}。括号须配对，未配对原样保留（交给 KaTeX 报错/兜底）。
     */
    private fun replaceSqrt(input: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < input.length) {
            val c = input[i]
            val isCube = c == '³' && i + 1 < input.length && input[i + 1] == '√'
            val isPlain = c == '√'
            if ((isCube || isPlain) && i + 1 < input.length && input[i + 1] == '(') {
                val start = if (isCube) i + 2 else i + 1
                val end = findMatchingParen(input, start)
                if (end >= 0) {
                    val inner = input.substring(start + 1, end)
                    if (isCube) out.append("\\sqrt[3]{").append(inner).append('}')
                    else out.append("\\sqrt{").append(inner).append('}')
                    i = end + 1
                    continue
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    /**
     * ^(…) → ^{…}、_(…) → _{…}。括号须配对；单字符上标（^x / _x）KaTeX 原生支持，不动。
     */
    private fun replaceSupSubParen(input: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if ((c == '^' || c == '_') && i + 1 < input.length && input[i + 1] == '(') {
                val end = findMatchingParen(input, i + 1)
                if (end >= 0) {
                    val inner = input.substring(i + 2, end)
                    if (inner.isNotEmpty()) {
                        if (c == '^') out.append("^{").append(inner).append('}')
                        else out.append("_{").append(inner).append('}')
                        i = end + 1
                        continue
                    }
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    /**
     * 连续 CJK 片段（中文/日文/韩文）包成 \text{…}，避免 math mode 中文告警。
     * 与内置风格一致：`\text{ 或 }`（保留原文本，不加空格）。
     */
    private fun wrapCjkAsText(input: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < input.length) {
            val cp = input.codePointAt(i)
            val ch = String(Character.toChars(cp))
            val isCjk = isCjkCodePoint(cp)
            if (isCjk) {
                val start = i
                var j = i + Character.charCount(cp)
                while (j < input.length && isCjkCodePoint(input.codePointAt(j))) {
                    j += Character.charCount(input.codePointAt(j))
                }
                out.append("\\text{").append(input.substring(start, j)).append('}')
                i = j
            } else {
                out.append(ch)
                i += Character.charCount(cp)
            }
        }
        return out.toString()
    }

    private fun isCjkCodePoint(cp: Int): Boolean = when {
        cp in 0x4E00..0x9FFF -> true   // CJK 统一表意文字
        cp in 0x3400..0x4DBF -> true   // 扩展 A
        cp in 0x3000..0x303F -> true   // CJK 标点
        cp in 0x3040..0x30FF -> true   // 假名
        cp in 0xAC00..0xD7AF -> true   // 谚文
        else -> false
    }

    /**
     * 从 [start]（指向 '('）找配对的 ')' 下标；无配对返回 -1。
     */
    private fun findMatchingParen(input: String, start: Int): Int {
        var depth = 0
        for (j in start until input.length) {
            when (input[j]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return j
                }
            }
        }
        return -1
    }
}

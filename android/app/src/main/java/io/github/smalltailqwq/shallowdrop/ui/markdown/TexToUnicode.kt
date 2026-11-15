package io.github.smalltailqwq.shallowdrop.ui.markdown

/**
 * 一段公式文字及其样式：scripts 依次记录嵌套的上下标层级（'^' 上标，'_' 下标）。
 */
data class MathSpan(
    val text: String,
    val italic: Boolean = false,
    val bold: Boolean = false,
    val scripts: String = "",
)

/**
 * TeX → 带样式的 Unicode 文字，纯 Kotlin、同步、可选择复制。
 *
 * 用于行内公式（随正文排版、可选中），以及块级公式在 WebView 不可用（预览、KaTeX 尚未就绪或报错）时的兜底。
 * 只是近似显示：希腊字母、运算符、关系符、箭头等换成 Unicode；上下标交给渲染层用基线偏移；
 * `\frac{a}{b}` → a/b，`\sqrt{x}` → √x，矩阵/cases/aligned 按行用分号（块级为换行）拼接。
 * 不认识的命令原样保留 `\name`。任何异常都退回原文，不会抛出。
 */
object TexToUnicode {
    fun convert(tex: String, display: Boolean = false): List<MathSpan> = try {
        val box = TexBoxParser(tex).parseAll()
        val out = ArrayList<MathSpan>()
        Flattener(display).flatten(box, "", Font.Normal, out)
        merge(out).ifEmpty { listOf(MathSpan(tex)) }
    } catch (_: Exception) {
        listOf(MathSpan(tex))
    } catch (_: StackOverflowError) {
        listOf(MathSpan(tex))
    }

    fun plainText(tex: String, display: Boolean = false): String = convert(tex, display).joinToString("") { it.text }

    private fun merge(spans: List<MathSpan>): List<MathSpan> {
        val out = ArrayList<MathSpan>(spans.size)
        for (span in spans) {
            if (span.text.isEmpty()) continue
            val prev = out.lastOrNull()
            if (prev != null && prev.italic == span.italic && prev.bold == span.bold && prev.scripts == span.scripts) {
                out[out.size - 1] = prev.copy(text = prev.text + span.text)
            } else {
                out += span
            }
        }
        return out
    }
}

// ------------------------------------------------------------------ 盒子模型

private enum class Cls { Ord, Var, Bin, Rel, Open, Close, Punct, Op, Func, Text }

private enum class Font { Normal, Roman, Bold, BlackboardBold }

private sealed interface Box {
    data class Atom(val text: String, val cls: Cls) : Box
    data class Row(val items: List<Box>) : Box
    data class Scripts(val base: Box, val sup: Box?, val sub: Box?) : Box
    data class Frac(val num: Box, val den: Box) : Box
    data class Binom(val n: Box, val k: Box) : Box
    data class Sqrt(val index: Box?, val body: Box) : Box
    data class Accent(val body: Box, val mark: Char, val wide: Boolean) : Box
    data class Styled(val body: Box, val font: Font) : Box
    data class Env(val name: String, val rows: List<List<Box>>) : Box
}

private val EMPTY = Box.Row(emptyList())

// ------------------------------------------------------------------ 符号表

private val GREEK = mapOf(
    "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ϵ", "varepsilon" to "ε",
    "zeta" to "ζ", "eta" to "η", "theta" to "θ", "vartheta" to "ϑ", "iota" to "ι", "kappa" to "κ",
    "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ", "omicron" to "ο", "pi" to "π", "varpi" to "ϖ",
    "rho" to "ρ", "varrho" to "ϱ", "sigma" to "σ", "varsigma" to "ς", "tau" to "τ", "upsilon" to "υ",
    "phi" to "ϕ", "varphi" to "φ", "chi" to "χ", "psi" to "ψ", "omega" to "ω",
    "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ", "Xi" to "Ξ", "Pi" to "Π",
    "Sigma" to "Σ", "Upsilon" to "Υ", "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω",
)

private val BIN = mapOf(
    "times" to "×", "cdot" to "⋅", "div" to "÷", "pm" to "±", "mp" to "∓", "ast" to "∗", "star" to "⋆",
    "circ" to "∘", "bullet" to "∙", "oplus" to "⊕", "ominus" to "⊖", "otimes" to "⊗", "odot" to "⊙",
    "cup" to "∪", "cap" to "∩", "setminus" to "∖", "land" to "∧", "wedge" to "∧", "lor" to "∨", "vee" to "∨",
    "sqcup" to "⊔", "sqcap" to "⊓", "uplus" to "⊎", "amalg" to "⨿", "dagger" to "†", "ddagger" to "‡",
    "cdotp" to "⋅", "bmod" to "mod",
)

private val REL = mapOf(
    "leq" to "≤", "le" to "≤", "geq" to "≥", "ge" to "≥", "neq" to "≠", "ne" to "≠", "leqslant" to "⩽",
    "geqslant" to "⩾", "approx" to "≈", "equiv" to "≡", "sim" to "∼", "simeq" to "≃", "cong" to "≅",
    "propto" to "∝", "in" to "∈", "notin" to "∉", "ni" to "∋", "subset" to "⊂", "subseteq" to "⊆",
    "supset" to "⊃", "supseteq" to "⊇", "subsetneq" to "⊊", "supsetneq" to "⊋", "ll" to "≪", "gg" to "≫",
    "perp" to "⊥", "parallel" to "∥", "mid" to "∣", "nmid" to "∤", "to" to "→", "rightarrow" to "→",
    "leftarrow" to "←", "gets" to "←", "Rightarrow" to "⇒", "Leftarrow" to "⇐", "leftrightarrow" to "↔",
    "Leftrightarrow" to "⇔", "iff" to "⟺", "implies" to "⟹", "impliedby" to "⟸", "mapsto" to "↦",
    "longrightarrow" to "⟶", "longleftarrow" to "⟵", "Longrightarrow" to "⟹", "Longleftarrow" to "⟸",
    "longleftrightarrow" to "⟷", "Longleftrightarrow" to "⟺", "uparrow" to "↑", "downarrow" to "↓",
    "Uparrow" to "⇑", "Downarrow" to "⇓", "updownarrow" to "↕", "nearrow" to "↗", "searrow" to "↘",
    "rightleftharpoons" to "⇌", "leftrightharpoons" to "⇋", "hookrightarrow" to "↪", "hookleftarrow" to "↩",
    "longmapsto" to "⟼", "prec" to "≺", "succ" to "≻", "preceq" to "⪯", "succeq" to "⪰", "vdash" to "⊢",
    "dashv" to "⊣", "models" to "⊨", "asymp" to "≍", "doteq" to "≐", "triangleq" to "≜", "coloneqq" to "≔",
    "lessgtr" to "≶", "nleq" to "≰", "ngeq" to "≱", "lesssim" to "≲", "gtrsim" to "≳", "colon" to ":",
    "sqsubseteq" to "⊑", "sqsupseteq" to "⊒", "bowtie" to "⋈", "smile" to "⌣", "frown" to "⌢",
    "xrightarrow" to "→", "xleftarrow" to "←", "therefore" to "∴", "because" to "∵",
)

private val ORD = mapOf(
    "infty" to "∞", "partial" to "∂", "nabla" to "∇", "forall" to "∀", "exists" to "∃", "nexists" to "∄",
    "neg" to "¬", "lnot" to "¬", "emptyset" to "∅", "varnothing" to "∅", "angle" to "∠", "measuredangle" to "∡",
    "triangle" to "△", "square" to "□", "Box" to "□", "blacksquare" to "■", "diamond" to "⋄", "Diamond" to "◇",
    "degree" to "°", "prime" to "′", "hbar" to "ℏ", "hslash" to "ℏ", "ell" to "ℓ", "Re" to "ℜ", "Im" to "ℑ",
    "aleph" to "ℵ", "beth" to "ℶ", "wp" to "℘", "ldots" to "…", "dots" to "…", "cdots" to "⋯", "vdots" to "⋮",
    "ddots" to "⋱", "dotsc" to "…", "dotsb" to "⋯", "top" to "⊤", "bot" to "⊥", "flat" to "♭", "sharp" to "♯",
    "natural" to "♮", "clubsuit" to "♣", "heartsuit" to "♡", "spadesuit" to "♠", "diamondsuit" to "♢",
    "checkmark" to "✓", "dag" to "†", "S" to "§", "P" to "¶", "copyright" to "©", "pounds" to "£",
    "imath" to "ı", "jmath" to "ȷ", "complement" to "∁", "surd" to "√", "lvert" to "|", "rvert" to "|",
    "vert" to "|", "Vert" to "‖", "lVert" to "‖", "rVert" to "‖", "backslash" to "\\", "mho" to "℧",
    "circledR" to "®", "yen" to "¥", "euro" to "€", "%" to "%", "#" to "#", "&" to "&", "_" to "_", "$" to "$",
    "|" to "‖", "textbackslash" to "\\",
)

private val OPEN = mapOf(
    "langle" to "⟨", "lfloor" to "⌊", "lceil" to "⌈", "{" to "{", "lbrace" to "{", "lbrack" to "[",
    "lparen" to "(", "ulcorner" to "⌜", "llcorner" to "⌞",
)

private val CLOSE = mapOf(
    "rangle" to "⟩", "rfloor" to "⌋", "rceil" to "⌉", "}" to "}", "rbrace" to "}", "rbrack" to "]",
    "rparen" to ")", "urcorner" to "⌝", "lrcorner" to "⌟",
)

private val BIG_OP = mapOf(
    "sum" to "∑", "prod" to "∏", "coprod" to "∐", "int" to "∫", "iint" to "∬", "iiint" to "∭", "oint" to "∮",
    "oiint" to "∯", "bigcup" to "⋃", "bigcap" to "⋂", "bigvee" to "⋁", "bigwedge" to "⋀", "bigoplus" to "⨁",
    "bigotimes" to "⨂", "bigodot" to "⨀", "biguplus" to "⨄", "bigsqcup" to "⨆", "smallint" to "∫",
)

private val FUNCTIONS = setOf(
    "sin", "cos", "tan", "cot", "sec", "csc", "arcsin", "arccos", "arctan", "arccot", "sinh", "cosh", "tanh",
    "coth", "sech", "csch", "log", "ln", "lg", "exp", "lim", "liminf", "limsup", "max", "min", "sup", "inf",
    "det", "arg", "deg", "dim", "gcd", "hom", "ker", "Pr", "mod", "argmax", "argmin", "tr", "rank", "sgn",
)

private val SPACES = mapOf(
    "," to " ", ":" to " ", ">" to " ", ";" to " ", "!" to "", " " to " ", "quad" to " ",
    "qquad" to "  ", "enspace" to " ", "thinspace" to " ", "medspace" to " ",
    "thickspace" to " ", "space" to " ", "nobreakspace" to " ", "negthinspace" to "",
)

/** 组合附加符 */
private val ACCENTS = mapOf(
    "hat" to ('̂' to false), "widehat" to ('̂' to false), "check" to ('̌' to false),
    "tilde" to ('̃' to false), "widetilde" to ('̃' to false), "acute" to ('́' to false),
    "grave" to ('̀' to false), "dot" to ('̇' to false), "ddot" to ('̈' to false),
    "breve" to ('̆' to false), "bar" to ('̄' to false), "vec" to ('⃗' to false),
    "overrightarrow" to ('⃗' to false), "overleftarrow" to ('⃖' to false),
    "overline" to ('̅' to true), "underline" to ('̲' to true), "mathring" to ('̊' to false),
)

/** 只取参数内容、忽略样式的命令 */
private val PASS_THROUGH = setOf(
    "boxed", "cancel", "bcancel", "xcancel", "sout", "underbrace", "overbrace", "mathop", "mathrel", "mathbin",
    "mathord", "mathpunct", "mathinner", "mathclose", "mathopen", "displaylines", "smash", "bbox", "mathstrut",
    "underbracket", "overbracket", "ensuremath", "htmlClass", "htmlId",
)

/** 无参数、直接忽略的命令 */
private val IGNORED = setOf(
    "left", "right", "middle", "big", "Big", "bigg", "Bigg", "bigl", "bigr", "Bigl", "Bigr", "biggl", "biggr",
    "Biggl", "Biggr", "bigm", "Bigm", "biggm", "Biggm", "limits", "nolimits", "displaystyle", "textstyle",
    "scriptstyle", "scriptscriptstyle", "nonumber", "notag", "hline", "centering", "small", "large", "Large",
    "tiny", "normalsize", "rm", "bf", "it", "sf", "tt", "cal", "allowbreak", "relax", "strut", "huge", "Huge",
    "footnotesize", "scriptsize", "cr", "newline", "hfill", "displaybreak",
)

/** 一个参数、整体忽略的命令 */
private val IGNORED_WITH_ARG = setOf(
    "label", "phantom", "hphantom", "vphantom", "hspace", "vspace", "kern", "mkern", "mskip", "hskip", "color",
)

private val FONT_COMMANDS = mapOf(
    "mathbb" to Font.BlackboardBold, "Bbb" to Font.BlackboardBold, "mathbf" to Font.Bold, "boldsymbol" to Font.Bold,
    "bm" to Font.Bold, "pmb" to Font.Bold, "mathrm" to Font.Roman, "mathsf" to Font.Roman, "mathtt" to Font.Roman,
    "mathcal" to Font.Normal, "mathscr" to Font.Normal, "mathfrak" to Font.Normal, "mathit" to Font.Normal,
    "mathnormal" to Font.Normal, "operatorname" to Font.Roman,
)

private val TEXT_COMMANDS = setOf(
    "text", "textrm", "textit", "textbf", "texttt", "textsf", "textnormal", "mbox", "hbox", "textup", "emph",
)

private val BLACKBOARD = mapOf(
    'C' to "ℂ", 'H' to "ℍ", 'N' to "ℕ", 'P' to "ℙ", 'Q' to "ℚ", 'R' to "ℝ", 'Z' to "ℤ", '1' to "𝟙",
)

// ------------------------------------------------------------------ 解析

private class TexBoxParser(private val s: String) {
    private var i = 0
    private var depth = 0

    fun parseAll(): Box = Box.Row(parseList(StopAt.End))

    private enum class StopAt { End, Brace, Bracket, EnvCell }

    private fun peekCommand(): String? {
        if (i >= s.length || s[i] != '\\') return null
        var j = i + 1
        if (j < s.length && s[j].isLetter()) {
            while (j < s.length && s[j].isLetter()) j++
            return s.substring(i + 1, j)
        }
        return if (j < s.length) s[j].toString() else ""
    }

    private fun skipSpaces() {
        while (i < s.length && s[i].isWhitespace()) i++
    }

    private fun parseList(stop: StopAt): List<Box> {
        if (++depth > 64) throw IllegalStateException("too deep")
        val items = ArrayList<Box>()
        while (true) {
            skipSpaces()
            if (i >= s.length) break
            val c = s[i]
            if (c == '}' && stop == StopAt.Brace) break
            if (c == ']' && stop == StopAt.Bracket) break
            if (stop == StopAt.EnvCell) {
                if (c == '&') break
                val cmd = peekCommand()
                if (cmd == "\\" || cmd == "end" || cmd == "cr") break
            }
            if (c == '}') { // 多余的右括号
                i++
                continue
            }
            if (c == '&') {
                i++
                continue
            }
            if (c == '^' || c == '_') { // 没有底的上下标，如 {}^{14}C
                items += parseScripts(EMPTY)
                continue
            }
            val atom = parseAtom() ?: continue
            items += parseScripts(atom)
        }
        depth--
        return items
    }

    private fun parseScripts(base: Box): Box {
        var sup: Box? = null
        var sub: Box? = null
        var primes = ""
        while (true) {
            skipSpaces()
            if (i >= s.length) break
            when (s[i]) {
                '^' -> {
                    i++
                    sup = parseArg()
                }
                '_' -> {
                    i++
                    sub = parseArg()
                }
                '\'' -> {
                    i++
                    primes += "′"
                }
                else -> break
            }
        }
        if (primes.isNotEmpty()) {
            val p = Box.Atom(primes, Cls.Ord)
            if (sup == null) return Box.Scripts(Box.Row(listOf(base, p)), null, sub).simplify()
            sup = Box.Row(listOf(p, sup))
        }
        return if (sup == null && sub == null) base else Box.Scripts(base, sup, sub)
    }

    private fun Box.Scripts.simplify(): Box = if (sup == null && sub == null) base else this

    /** 命令或上下标的参数：花括号分组、命令或单个字符 */
    private fun parseArg(): Box {
        skipSpaces()
        if (i >= s.length) return EMPTY
        if (s[i] == '{') {
            i++
            val list = parseList(StopAt.Brace)
            if (i < s.length && s[i] == '}') i++
            return Box.Row(list)
        }
        if (s[i] == '\\') return parseAtom() ?: EMPTY
        val c = s[i++]
        return charAtom(c)
    }

    /** 原样读取花括号参数的文字（\text 等） */
    private fun rawArg(): String {
        skipSpaces()
        if (i >= s.length) return ""
        if (s[i] != '{') return s[i++].toString()
        var level = 0
        val start = i + 1
        while (i < s.length) {
            when (s[i]) {
                '\\' -> i++
                '{' -> level++
                '}' -> {
                    level--
                    if (level == 0) {
                        val text = s.substring(start, i)
                        i++
                        return text
                    }
                }
            }
            i++
        }
        return s.substring(start.coerceAtMost(s.length))
    }

    private fun optionalArg(): Box? {
        skipSpaces()
        if (i >= s.length || s[i] != '[') return null
        i++
        val list = parseList(StopAt.Bracket)
        if (i < s.length && s[i] == ']') i++
        return Box.Row(list)
    }

    private fun charAtom(c: Char): Box = when {
        c.isLetter() -> Box.Atom(c.toString(), Cls.Var)
        c == '+' -> Box.Atom("+", Cls.Bin)
        c == '-' -> Box.Atom("−", Cls.Bin)
        c == '*' -> Box.Atom("∗", Cls.Bin)
        c == '=' || c == '<' || c == '>' -> Box.Atom(c.toString(), Cls.Rel)
        c == ':' -> Box.Atom(":", Cls.Rel)
        c == ',' || c == ';' -> Box.Atom(c.toString(), Cls.Punct)
        c == '(' || c == '[' -> Box.Atom(c.toString(), Cls.Open)
        c == ')' || c == ']' || c == '!' || c == '?' -> Box.Atom(c.toString(), Cls.Close)
        c == '~' -> Box.Atom(" ", Cls.Ord)
        else -> Box.Atom(c.toString(), Cls.Ord)
    }

    private fun parseAtom(): Box? {
        val c = s[i]
        if (c == '{') {
            i++
            val list = parseList(StopAt.Brace)
            if (i < s.length && s[i] == '}') i++
            return Box.Row(list)
        }
        if (c == '\\') return parseCommand()
        if (c.isDigit() || (c == '.' && i + 1 < s.length && s[i + 1].isDigit())) {
            val start = i
            while (i < s.length && (s[i].isDigit() || (s[i] == '.' && i + 1 < s.length && s[i + 1].isDigit()))) i++
            return Box.Atom(s.substring(start, i), Cls.Ord)
        }
        i++
        return charAtom(c)
    }

    private fun parseCommand(): Box? {
        val name = peekCommand() ?: return null
        i += 1 + name.length
        if (name.isEmpty()) return null
        SPACES[name]?.let { return Box.Atom(it, Cls.Text) }
        GREEK[name]?.let { return Box.Atom(it, if (name[0].isLowerCase()) Cls.Var else Cls.Ord) }
        BIN[name]?.let { return Box.Atom(it, Cls.Bin) }
        REL[name]?.let { return Box.Atom(it, Cls.Rel) }
        ORD[name]?.let { return Box.Atom(it, Cls.Ord) }
        OPEN[name]?.let { return Box.Atom(it, Cls.Open) }
        CLOSE[name]?.let { return Box.Atom(it, Cls.Close) }
        BIG_OP[name]?.let { return Box.Atom(it, Cls.Op) }
        if (name in FUNCTIONS) return Box.Atom(name, Cls.Func)
        ACCENTS[name]?.let { (mark, wide) -> return Box.Accent(parseArg(), mark, wide) }
        FONT_COMMANDS[name]?.let { font ->
            if (name == "operatorname" && i < s.length && s[i] == '*') i++
            val body = parseArg()
            return if (name == "operatorname") Box.Atom(plainOf(body), Cls.Func) else Box.Styled(body, font)
        }
        when (name) {
            "\\" -> return Box.Atom(" ", Cls.Text)
            "left", "right", "middle" -> {
                skipSpaces()
                if (i < s.length && s[i] == '.') {
                    i++
                    return null
                }
                if (i >= s.length) return null
                val delimiter = parseAtom() ?: return null
                return if (delimiter is Box.Atom) {
                    Box.Atom(delimiter.text, if (name == "left") Cls.Open else if (name == "right") Cls.Close else Cls.Ord)
                } else delimiter
            }
            in IGNORED -> return null
            in IGNORED_WITH_ARG -> {
                rawArg()
                return null
            }
            in TEXT_COMMANDS -> return Box.Styled(Box.Atom(rawArg(), Cls.Text), if (name == "textbf") Font.Bold else Font.Roman)
            in PASS_THROUGH -> {
                if (name == "bbox" || name == "htmlClass" || name == "htmlId") optionalArg()
                return parseArg()
            }
            "textcolor", "colorbox" -> {
                rawArg()
                return parseArg()
            }
            "frac", "dfrac", "tfrac", "cfrac" -> return Box.Frac(parseArg(), parseArg())
            "binom", "dbinom", "tbinom" -> return Box.Binom(parseArg(), parseArg())
            "sqrt" -> {
                val index = optionalArg()
                return Box.Sqrt(index, parseArg())
            }
            "overset", "stackrel" -> {
                val top = parseArg()
                return Box.Scripts(parseArg(), top, null)
            }
            "underset" -> {
                val bottom = parseArg()
                return Box.Scripts(parseArg(), null, bottom)
            }
            "not" -> {
                skipSpaces()
                if (i >= s.length) return Box.Atom("/", Cls.Rel)
                val next = parseAtom()
                return if (next is Box.Atom) next.copy(text = next.text + "̸") else next
            }
            "pmod" -> {
                val arg = parseArg()
                return Box.Row(listOf(Box.Atom(" (mod ", Cls.Text), arg, Box.Atom(")", Cls.Close)))
            }
            "tag" -> return Box.Atom(" (${rawArg()})", Cls.Text)
            "ce", "pu" -> return chem(rawArg())
            "begin" -> return environment(rawArg())
            "end" -> {
                rawArg()
                return null
            }
        }
        return Box.Atom("\\$name", Cls.Ord)
    }

    private fun environment(name: String): Box {
        val base = name.removeSuffix("*")
        if (base == "array" || base == "alignedat" || base == "alignat") rawArg()
        val rows = ArrayList<List<Box>>()
        var cells = ArrayList<Box>()
        while (true) {
            cells += Box.Row(parseList(StopAt.EnvCell))
            if (i >= s.length) {
                rows += cells
                break
            }
            val cmd = peekCommand()
            when {
                s[i] == '&' -> i++
                cmd == "\\" || cmd == "cr" -> {
                    i += 1 + cmd.length
                    optionalArg() // \\[2pt]
                    rows += cells
                    cells = ArrayList()
                }
                cmd == "end" -> {
                    i += 4
                    rawArg()
                    rows += cells
                    break
                }
                else -> i++
            }
        }
        // 去掉末尾的空行（`\\` 结尾）
        while (rows.isNotEmpty() && rows.last().all { it == EMPTY || (it is Box.Row && it.items.isEmpty()) }) rows.removeAt(rows.size - 1)
        return Box.Env(base, rows)
    }

    /** mhchem 的简化：元素后的数字为下标，`^` 为上标，箭头换符号 */
    private fun chem(text: String): Box {
        val items = ArrayList<Box>()
        var j = 0
        fun prevIsFormula(): Boolean {
            val last = items.lastOrNull() as? Box.Atom ?: return false
            return last.cls == Cls.Text && last.text.lastOrNull()?.let { it.isLetter() || it == ')' || it == ']' } == true
        }
        while (j < text.length) {
            val rest = text.substring(j)
            val arrow = listOf("<=>" to "⇌", "<->" to "↔", "->" to "→", "<-" to "←", "=" to "=").firstOrNull { rest.startsWith(it.first) }
            when {
                arrow != null -> {
                    items += Box.Atom(arrow.second, Cls.Text)
                    j += arrow.first.length
                }
                text[j].isDigit() && prevIsFormula() -> {
                    val start = j
                    while (j < text.length && text[j].isDigit()) j++
                    items += Box.Scripts(EMPTY, null, Box.Atom(text.substring(start, j), Cls.Text))
                }
                text[j] == '^' || text[j] == '_' -> {
                    val sup = text[j] == '^'
                    j++
                    val start = j
                    val content = if (j < text.length && text[j] == '{') {
                        val end = text.indexOf('}', j).let { if (it < 0) text.length else it }
                        j = (end + 1).coerceAtMost(text.length)
                        text.substring(start + 1, end)
                    } else {
                        while (j < text.length && (text[j].isLetterOrDigit() || text[j] == '+' || text[j] == '-')) j++
                        text.substring(start, j)
                    }
                    val atom = Box.Atom(content.replace('-', '−'), Cls.Text)
                    items += if (sup) Box.Scripts(EMPTY, atom, null) else Box.Scripts(EMPTY, null, atom)
                }
                else -> {
                    items += Box.Atom(text[j].toString(), Cls.Text)
                    j++
                }
            }
        }
        return Box.Styled(Box.Row(items), Font.Roman)
    }
}

private fun plainOf(box: Box): String {
    val out = ArrayList<MathSpan>()
    Flattener(false).flatten(box, "", Font.Roman, out)
    return out.joinToString("") { it.text }
}

// ------------------------------------------------------------------ 展开为文字

private class Flattener(private val display: Boolean) {
    fun flatten(box: Box, scripts: String, font: Font, out: MutableList<MathSpan>) {
        when (box) {
            is Box.Atom -> atom(box, scripts, font, out)
            is Box.Row -> row(box.items, scripts, font, out)
            is Box.Scripts -> {
                flatten(box.base, scripts, font, out)
                box.sub?.let { flatten(it, scripts + "_", font, out) }
                box.sup?.let { flatten(it, scripts + "^", font, out) }
            }
            is Box.Frac -> {
                wrapped(box.num, scripts, font, out)
                out += MathSpan("/", scripts = scripts)
                wrapped(box.den, scripts, font, out)
            }
            is Box.Binom -> {
                out += MathSpan("(", scripts = scripts)
                flatten(box.n, scripts + "^", font, out)
                flatten(box.k, scripts + "_", font, out)
                out += MathSpan(")", scripts = scripts)
            }
            is Box.Sqrt -> {
                box.index?.let { flatten(it, scripts + "^", font, out) }
                out += MathSpan("√", scripts = scripts)
                wrapped(box.body, scripts, font, out)
            }
            is Box.Accent -> {
                val inner = ArrayList<MathSpan>()
                flatten(box.body, scripts, font, inner)
                out += applyAccent(inner, box.mark, box.wide)
            }
            is Box.Styled -> flatten(box.body, scripts, box.font, out)
            is Box.Env -> env(box, scripts, font, out)
        }
    }

    private fun atom(box: Box.Atom, scripts: String, font: Font, out: MutableList<MathSpan>) {
        val text = if (font == Font.BlackboardBold) box.text.map { BLACKBOARD[it] ?: it.toString() }.joinToString("") else box.text
        // 没有对应双线体字符时用粗体代替
        val bold = font == Font.Bold || (font == Font.BlackboardBold && text == box.text)
        out += MathSpan(text, italic = box.cls == Cls.Var && font == Font.Normal, bold = bold, scripts = scripts)
    }

    /** 行内排版：二元运算符、关系符两侧留空，一元负号不留；上下标内不留空 */
    private fun row(items: List<Box>, scripts: String, font: Font, out: MutableList<MathSpan>, alignedCell: Boolean = false) {
        var prev: Box? = null
        for ((index, item) in items.withIndex()) {
            val cls = (item as? Box.Atom)?.cls
            val spaced = scripts.isEmpty()
            when {
                cls == Cls.Bin && spaced -> {
                    val prevCls = (prev as? Box.Atom)?.cls
                    val unary = prev == null || prevCls == Cls.Bin || prevCls == Cls.Rel || prevCls == Cls.Open ||
                        prevCls == Cls.Punct || prevCls == Cls.Op
                    if (unary) {
                        flatten(item, scripts, font, out)
                    } else {
                        out += MathSpan(" ", scripts = scripts)
                        flatten(item, scripts, font, out)
                        out += MathSpan(" ", scripts = scripts)
                    }
                }
                cls == Cls.Rel && spaced -> {
                    if (prev != null || alignedCell) out += MathSpan(" ", scripts = scripts)
                    flatten(item, scripts, font, out)
                    if (index < items.lastIndex) out += MathSpan(" ", scripts = scripts)
                }
                cls == Cls.Punct && spaced -> {
                    flatten(item, scripts, font, out)
                    if (index < items.lastIndex) out += MathSpan(" ", scripts = scripts)
                }
                else -> {
                    flatten(item, scripts, font, out)
                    // 函数名、大型运算符后与变量之间留细空
                    val headCls = headClass(item)
                    val next = items.getOrNull(index + 1)
                    if ((headCls == Cls.Func || headCls == Cls.Op) && next != null && headClass(next) !in setOf(Cls.Open, Cls.Bin, Cls.Rel, Cls.Punct)) {
                        out += MathSpan(" ", scripts = scripts)
                    }
                }
            }
            prev = item
        }
    }

    private fun headClass(box: Box): Cls? = when (box) {
        is Box.Atom -> box.cls
        is Box.Scripts -> headClass(box.base)
        else -> null
    }

    /** 多于一个元素的分子、分母、根号内容加括号 */
    private fun wrapped(box: Box, scripts: String, font: Font, out: MutableList<MathSpan>) {
        val needs = when (box) {
            is Box.Row -> box.items.size > 1 || (box.items.singleOrNull()?.let { it is Box.Frac || it is Box.Row && it.items.size > 1 } ?: false)
            is Box.Frac -> true
            else -> false
        }
        if (needs) out += MathSpan("(", scripts = scripts)
        flatten(box, scripts, font, out)
        if (needs) out += MathSpan(")", scripts = scripts)
    }

    private fun applyAccent(spans: List<MathSpan>, mark: Char, wide: Boolean): List<MathSpan> {
        if (spans.isEmpty()) return listOf(MathSpan(" $mark"))
        if (wide) return spans.map { span -> span.copy(text = span.text.map { "$it$mark" }.joinToString("")) }
        val last = spans.last()
        return spans.dropLast(1) + last.copy(text = last.text + mark)
    }

    private fun env(box: Box.Env, scripts: String, font: Font, out: MutableList<MathSpan>) {
        val (open, close) = when (box.name) {
            "pmatrix", "smallmatrix" -> "(" to ")"
            "bmatrix" -> "[" to "]"
            "Bmatrix" -> "{" to "}"
            "vmatrix" -> "|" to "|"
            "Vmatrix" -> "‖" to "‖"
            "cases", "dcases" -> "{" to ""
            "rcases" -> "" to "}"
            else -> "" to ""
        }
        val matrix = box.name.endsWith("matrix") || box.name == "array"
        val cellSeparator = if (matrix) "  " else if (box.name.endsWith("cases")) " " else ""
        val rowSeparator = if (display && !matrix) "\n" else "; "
        if (open.isNotEmpty()) out += MathSpan(open, scripts = scripts)
        box.rows.forEachIndexed { r, cells ->
            if (r > 0) out += MathSpan(rowSeparator, scripts = scripts)
            cells.forEachIndexed { c, cell ->
                if (c > 0 && cellSeparator.isNotEmpty()) out += MathSpan(cellSeparator, scripts = scripts)
                if (cell is Box.Row) row(cell.items, scripts, font, out, alignedCell = c > 0) else flatten(cell, scripts, font, out)
            }
        }
        if (close.isNotEmpty()) out += MathSpan(close, scripts = scripts)
    }
}

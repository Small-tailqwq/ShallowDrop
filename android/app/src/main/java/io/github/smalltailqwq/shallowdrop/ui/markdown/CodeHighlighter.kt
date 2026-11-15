package io.github.smalltailqwq.shallowdrop.ui.markdown

enum class CodeTokenKind { Keyword, Literal, Type, Function, String, Comment, Number, Tag, Attribute, Meta }

/** [start, end) 区间的高亮记号；未覆盖的部分按普通文字显示 */
data class CodeToken(val start: Int, val end: Int, val kind: CodeTokenKind)

/**
 * 轻量语法高亮：单遍线性扫描，按语言族识别注释、字符串、数字、关键字、字面量、类型与函数调用。
 * 不追求与 Prism 完全一致，只覆盖常用语言（语言名与别名对照 Web 版 `services/markdown/highlight.ts`）；
 * 不支持的语言返回空列表，按纯文本显示。
 */
object CodeHighlighter {
    private class Lang(
        val keywords: Set<String>,
        val literals: Set<String> = setOf("true", "false", "null"),
        val lineComments: List<String> = listOf("//"),
        val blockComment: Pair<String, String>? = "/*" to "*/",
        val quotes: String = "\"'",
        /** 三引号字符串（Python、Kotlin 等） */
        val tripleQuotes: Boolean = false,
        val caseInsensitive: Boolean = false,
        /** 首字母大写视为类型 */
        val capitalizedTypes: Boolean = true,
        /** `$var`、`@decorator` 之类的前缀符号 */
        val metaPrefixes: String = "",
    )

    private fun words(text: String) = text.trim().split(Regex("\\s+")).toSet()

    private val C_KEYWORDS = words(
        "auto break case char const continue default do double else enum extern float for goto if inline int long " +
            "register restrict return short signed sizeof static struct switch typedef union unsigned void volatile while " +
            "bool _Bool size_t uint8_t uint16_t uint32_t uint64_t int8_t int16_t int32_t int64_t NULL",
    )
    private val CPP_KEYWORDS = C_KEYWORDS + words(
        "alignas alignof and asm catch class constexpr const_cast decltype delete dynamic_cast explicit export friend " +
            "mutable namespace new noexcept not operator or override private protected public reinterpret_cast " +
            "static_assert static_cast template this thread_local throw try typeid typename using virtual final " +
            "concept requires co_await co_return co_yield nullptr std string vector",
    )
    private val JAVA_KEYWORDS = words(
        "abstract assert boolean break byte case catch char class const continue default do double else enum extends " +
            "final finally float for goto if implements import instanceof int interface long native new package private " +
            "protected public return short static strictfp super switch synchronized this throw throws transient try void " +
            "volatile while var record sealed permits yield",
    )
    private val JS_KEYWORDS = words(
        "async await break case catch class const continue debugger default delete do else export extends finally for " +
            "from function if import in instanceof let new of return static super switch this throw try typeof var void " +
            "while with yield as get set",
    )
    private val TS_KEYWORDS = JS_KEYWORDS + words(
        "abstract any boolean declare enum implements interface keyof module namespace never number object private " +
            "protected public readonly string symbol type unknown is infer satisfies override",
    )
    private val KOTLIN_KEYWORDS = words(
        "abstract annotation as break by catch class companion const constructor continue crossinline data do else enum " +
            "expect actual external final finally for fun get if import in infix init inline inner interface internal is " +
            "lateinit noinline object open operator out override package private protected public reified return sealed " +
            "set super suspend tailrec this throw try typealias val value var vararg when where while",
    )
    private val SWIFT_KEYWORDS = words(
        "associatedtype class deinit enum extension fileprivate func import init inout internal let open operator private " +
            "protocol public rethrows static struct subscript typealias var break case continue default defer do else " +
            "fallthrough for guard if in repeat return switch where while as catch is super self Self throw throws try " +
            "async await actor some any mutating nonmutating override final lazy weak unowned convenience required",
    )
    private val GO_KEYWORDS = words(
        "break case chan const continue default defer else fallthrough for func go goto if import interface map package " +
            "range return select struct switch type var int int8 int16 int32 int64 uint uint8 uint16 uint32 uint64 " +
            "float32 float64 string bool byte rune error any make len cap append new panic recover",
    )
    private val RUST_KEYWORDS = words(
        "as async await break const continue crate dyn else enum extern fn for if impl in let loop match mod move mut pub " +
            "ref return self Self static struct super trait type unsafe use where while i8 i16 i32 i64 i128 isize u8 u16 " +
            "u32 u64 u128 usize f32 f64 bool char str String Vec Option Result Some None Ok Err Box",
    )
    private val CSHARP_KEYWORDS = words(
        "abstract as base bool break byte case catch char checked class const continue decimal default delegate do double " +
            "else enum event explicit extern finally fixed float for foreach goto if implicit in int interface internal is " +
            "lock long namespace new object operator out override params private protected public readonly ref return " +
            "sbyte sealed short sizeof stackalloc static string struct switch this throw try typeof uint ulong unchecked " +
            "unsafe ushort using virtual void volatile while async await var get set init record yield where",
    )
    private val DART_KEYWORDS = words(
        "abstract as assert async await break case catch class const continue covariant default deferred do dynamic else " +
            "enum export extends extension external factory final finally for get hide if implements import in interface " +
            "is late library mixin new on operator part required rethrow return set show static super switch sync this " +
            "throw try typedef var void while with yield int double String bool List Map",
    )
    private val SCALA_KEYWORDS = words(
        "abstract case catch class def do else extends final finally for forSome if implicit import lazy match new object " +
            "override package private protected return sealed super this throw trait try type val var while with yield " +
            "given using enum then export",
    )
    private val PHP_KEYWORDS = words(
        "abstract and array as break callable case catch class clone const continue declare default do echo else elseif " +
            "empty enddeclare endfor endforeach endif endswitch endwhile extends final finally fn for foreach function " +
            "global goto if implements include include_once instanceof insteadof interface isset list match namespace new " +
            "or print private protected public readonly require require_once return static switch throw trait try unset " +
            "use var while xor yield",
    )
    private val PYTHON_KEYWORDS = words(
        "and as assert async await break class continue def del elif else except finally for from global if import in is " +
            "lambda nonlocal not or pass raise return try while with yield match case self print len range int str float " +
            "list dict set tuple bool",
    )
    private val RUBY_KEYWORDS = words(
        "alias and begin break case class def defined do else elsif end ensure for if in module next not or redo rescue " +
            "retry return self super then undef unless until when while yield require attr_accessor attr_reader puts",
    )
    private val BASH_KEYWORDS = words(
        "if then else elif fi case esac for select while until do done in function time return exit export local readonly " +
            "declare unset shift source alias echo cd ls cat grep sed awk set eval exec trap sudo apt pip npm git",
    )
    private val SQL_KEYWORDS = words(
        "select from where and or not insert into values update set delete create table drop alter add column primary key " +
            "foreign references index view join inner left right outer full cross on as group by order having limit offset " +
            "union all distinct case when then else end is null like in between exists asc desc default constraint unique " +
            "check database if begin commit rollback transaction with recursive returning int integer varchar text char " +
            "boolean date timestamp float double decimal serial count sum avg min max",
    )
    private val LUA_KEYWORDS = words(
        "and break do else elseif end for function goto if in local not or repeat return then until while",
    )
    private val HASKELL_KEYWORDS = words(
        "case class data default deriving do else foreign if import in infix infixl infixr instance let module newtype of " +
            "then type where qualified as hiding",
    )
    private val R_KEYWORDS = words("if else repeat while function for in next break library require return")
    private val PERL_KEYWORDS = words("my our local sub if elsif else unless while until for foreach last next redo return use package print")
    private val POWERSHELL_KEYWORDS = words(
        "begin break catch class continue data do dynamicparam else elseif end exit filter finally for foreach from function " +
            "if in param process return switch throw trap try until using while",
    )
    private val CSS_KEYWORDS = words("important media import keyframes font-face supports from to and not only")

    private val C_FAMILY_LITERALS = setOf("true", "false", "null", "nullptr", "NULL", "undefined", "NaN", "Infinity", "nil", "None", "True", "False")

    private val languages: Map<String, Lang> by lazy {
        val c = Lang(C_KEYWORDS, C_FAMILY_LITERALS, metaPrefixes = "#")
        val cpp = Lang(CPP_KEYWORDS, C_FAMILY_LITERALS, metaPrefixes = "#")
        val java = Lang(JAVA_KEYWORDS, C_FAMILY_LITERALS, metaPrefixes = "@")
        val js = Lang(JS_KEYWORDS, C_FAMILY_LITERALS, quotes = "\"'`")
        val ts = Lang(TS_KEYWORDS, C_FAMILY_LITERALS, quotes = "\"'`", metaPrefixes = "@")
        val hash = listOf("#")
        mapOf(
            "c" to c, "cpp" to cpp, "objectivec" to cpp.let { Lang(it.keywords + words("interface implementation end property nonatomic strong weak id self YES NO"), C_FAMILY_LITERALS + setOf("YES", "NO", "nil"), metaPrefixes = "#@") },
            "java" to java, "javascript" to js, "jsx" to js, "typescript" to ts, "tsx" to ts,
            "kotlin" to Lang(KOTLIN_KEYWORDS, C_FAMILY_LITERALS, tripleQuotes = true, metaPrefixes = "@"),
            "swift" to Lang(SWIFT_KEYWORDS, C_FAMILY_LITERALS + "nil", tripleQuotes = true, metaPrefixes = "@#"),
            "go" to Lang(GO_KEYWORDS, setOf("true", "false", "nil", "iota"), quotes = "\"'`"),
            "rust" to Lang(RUST_KEYWORDS, setOf("true", "false"), metaPrefixes = "#"),
            "csharp" to Lang(CSHARP_KEYWORDS, C_FAMILY_LITERALS, metaPrefixes = "#"),
            "dart" to Lang(DART_KEYWORDS, C_FAMILY_LITERALS, tripleQuotes = true, metaPrefixes = "@"),
            "scala" to Lang(SCALA_KEYWORDS, C_FAMILY_LITERALS + "Nil", tripleQuotes = true, metaPrefixes = "@"),
            "php" to Lang(PHP_KEYWORDS, C_FAMILY_LITERALS, lineComments = listOf("//", "#"), metaPrefixes = "$"),
            "python" to Lang(PYTHON_KEYWORDS, setOf("True", "False", "None"), lineComments = hash, blockComment = null, tripleQuotes = true, metaPrefixes = "@"),
            "ruby" to Lang(RUBY_KEYWORDS, setOf("true", "false", "nil"), lineComments = hash, blockComment = null, metaPrefixes = "@:$"),
            "bash" to Lang(BASH_KEYWORDS, setOf("true", "false"), lineComments = hash, blockComment = null, capitalizedTypes = false, metaPrefixes = "$"),
            "powershell" to Lang(POWERSHELL_KEYWORDS, setOf("\$true", "\$false", "\$null"), lineComments = hash, blockComment = "<#" to "#>", caseInsensitive = true, capitalizedTypes = false, metaPrefixes = "$"),
            "perl" to Lang(PERL_KEYWORDS, emptySet(), lineComments = hash, blockComment = null, metaPrefixes = "$@%"),
            "r" to Lang(R_KEYWORDS, setOf("TRUE", "FALSE", "NULL", "NA", "Inf", "NaN"), lineComments = hash, blockComment = null, capitalizedTypes = false),
            "sql" to Lang(SQL_KEYWORDS, setOf("true", "false", "null"), lineComments = listOf("--"), caseInsensitive = true, capitalizedTypes = false),
            "lua" to Lang(LUA_KEYWORDS, setOf("true", "false", "nil"), lineComments = listOf("--"), blockComment = "--[[" to "]]", capitalizedTypes = false),
            "haskell" to Lang(HASKELL_KEYWORDS, setOf("True", "False", "Nothing", "Just"), lineComments = listOf("--"), blockComment = "{-" to "-}"),
            "json" to Lang(emptySet(), setOf("true", "false", "null"), lineComments = emptyList(), blockComment = null, quotes = "\"", capitalizedTypes = false),
            "yaml" to Lang(emptySet(), setOf("true", "false", "null", "yes", "no", "on", "off", "~"), lineComments = hash, blockComment = null, capitalizedTypes = false),
            "toml" to Lang(emptySet(), setOf("true", "false"), lineComments = hash, blockComment = null, tripleQuotes = true, capitalizedTypes = false),
            "ini" to Lang(emptySet(), setOf("true", "false"), lineComments = listOf(";", "#"), blockComment = null, capitalizedTypes = false),
            "docker" to Lang(words("FROM RUN CMD LABEL EXPOSE ENV ADD COPY ENTRYPOINT VOLUME USER WORKDIR ARG ONBUILD STOPSIGNAL HEALTHCHECK SHELL AS"), emptySet(), lineComments = hash, blockComment = null, caseInsensitive = true, capitalizedTypes = false, metaPrefixes = "$"),
            "makefile" to Lang(words("ifeq ifneq ifdef ifndef else endif include define endef export override"), emptySet(), lineComments = hash, blockComment = null, capitalizedTypes = false, metaPrefixes = "$"),
            "nginx" to Lang(words("server location listen server_name root index proxy_pass return rewrite include http events upstream if set try_files"), setOf("on", "off"), lineComments = hash, blockComment = null, capitalizedTypes = false, metaPrefixes = "$"),
            "css" to Lang(CSS_KEYWORDS, emptySet(), lineComments = emptyList(), capitalizedTypes = false, metaPrefixes = "@#."),
            "scss" to Lang(CSS_KEYWORDS + words("mixin include extend if else each for function return use forward"), emptySet(), capitalizedTypes = false, metaPrefixes = "@$#."),
            "graphql" to Lang(words("query mutation subscription fragment on type interface union enum input scalar schema extend implements directive"), C_FAMILY_LITERALS, lineComments = hash, blockComment = null, metaPrefixes = "$@"),
            "protobuf" to Lang(words("syntax package import option message enum service rpc returns repeated optional required oneof map reserved string int32 int64 uint32 uint64 bool bytes double float"), setOf("true", "false")),
            "matlab" to Lang(words("function end if elseif else for while switch case otherwise break continue return try catch global persistent"), setOf("true", "false"), lineComments = listOf("%"), blockComment = "%{" to "%}", capitalizedTypes = false),
            "latex" to Lang(emptySet(), emptySet(), lineComments = listOf("%"), blockComment = null, quotes = "", capitalizedTypes = false, metaPrefixes = "\\"),
            "diff" to Lang(emptySet(), emptySet(), lineComments = emptyList(), blockComment = null, quotes = "", capitalizedTypes = false),
            "markup" to Lang(emptySet(), emptySet(), lineComments = emptyList(), blockComment = "<!--" to "-->", quotes = "\"'", capitalizedTypes = false),
        )
    }

    private val aliases = mapOf(
        "html" to "markup", "xml" to "markup", "vue" to "markup", "svg" to "markup", "js" to "javascript",
        "ts" to "typescript", "shell" to "bash", "sh" to "bash", "zsh" to "bash", "console" to "bash", "c++" to "cpp",
        "cc" to "cpp", "cplusplus" to "cpp", "h" to "c", "hpp" to "cpp", "cs" to "csharp", "c#" to "csharp",
        "dockerfile" to "docker", "golang" to "go", "hs" to "haskell", "kt" to "kotlin", "kts" to "kotlin",
        "tex" to "latex", "mk" to "makefile", "objc" to "objectivec", "py" to "python", "python3" to "python",
        "rb" to "ruby", "rs" to "rust", "yml" to "yaml", "ps1" to "powershell", "ps" to "powershell",
        "proto" to "protobuf", "jsonc" to "json", "json5" to "json", "mysql" to "sql", "postgresql" to "sql",
        "sqlite" to "sql", "patch" to "diff", "gradle" to "kotlin", "groovy" to "java", "less" to "scss",
    )

    /** 代码块语言名 → 内部语言名；不支持时为 null */
    fun resolveLanguage(info: String): String? {
        val lower = info.trim().lowercase()
        val name = aliases[lower] ?: lower
        return name.takeIf { it in languages }
    }

    fun highlight(code: String, language: String): List<CodeToken> {
        val name = resolveLanguage(language) ?: return emptyList()
        return when (name) {
            "markup" -> markup(code)
            "diff" -> diff(code)
            else -> scan(code, languages.getValue(name), name)
        }
    }

    private fun scan(code: String, lang: Lang, name: String): List<CodeToken> {
        val tokens = ArrayList<CodeToken>()
        val keywords = if (lang.caseInsensitive) lang.keywords.mapTo(HashSet()) { it.lowercase() } else lang.keywords
        val literals = if (lang.caseInsensitive) lang.literals.mapTo(HashSet()) { it.lowercase() } else lang.literals
        val n = code.length
        var i = 0
        var lineStart = true
        while (i < n) {
            val c = code[i]
            // 块注释
            val block = lang.blockComment
            if (block != null && code.startsWith(block.first, i)) {
                val end = code.indexOf(block.second, i + block.first.length).let { if (it < 0) n else it + block.second.length }
                tokens += CodeToken(i, end, CodeTokenKind.Comment)
                i = end
                continue
            }
            // 行注释（# 在 C 族里是预处理指令，由 metaPrefixes 处理）
            val line = lang.lineComments.firstOrNull { code.startsWith(it, i) }
            if (line != null && !(line == "#" && name == "php" && i + 1 < n && code[i + 1] == '[')) {
                val end = code.indexOf('\n', i).let { if (it < 0) n else it }
                tokens += CodeToken(i, end, CodeTokenKind.Comment)
                i = end
                continue
            }
            // 字符串
            if (c in lang.quotes) {
                // Rust 生命周期 `'a`、Haskell 撇号等：单引号只认字符字面量 'x' 与转义 '\n'
                if (c == '\'' && name in setOf("rust", "haskell", "scala") &&
                    !(i + 2 < n && code[i + 2] == '\'') && !(i + 1 < n && code[i + 1] == '\\')
                ) {
                    i++
                    continue
                }
                val triple = lang.tripleQuotes && i + 2 < n && code[i + 1] == c && code[i + 2] == c
                val end = if (triple) {
                    code.indexOf("$c$c$c", i + 3).let { if (it < 0) n else it + 3 }
                } else {
                    stringEnd(code, i, c, multiline = c == '`')
                }
                tokens += CodeToken(i, end, CodeTokenKind.String)
                i = end
                continue
            }
            // 预处理指令、注解、变量前缀
            if (c in lang.metaPrefixes && (c != '#' || lineStart || name != "c" && name != "cpp" && name != "objectivec")) {
                var end = i + 1
                if (c == '#' && (name == "c" || name == "cpp" || name == "objectivec")) {
                    end = code.indexOf('\n', i).let { if (it < 0) n else it }
                    // 指令行里的 <header> 与字符串不再细分
                } else {
                    if (end < n && code[end] == '{') {
                        i++
                        continue
                    }
                    while (end < n && (code[end].isLetterOrDigit() || code[end] == '_' || code[end] == '-' && name in setOf("css", "scss"))) end++
                }
                if (end > i + 1) {
                    tokens += CodeToken(i, end, CodeTokenKind.Meta)
                    i = end
                    lineStart = false
                    continue
                }
            }
            // 数字
            if (c.isDigit() && (i == 0 || !isIdentPart(code[i - 1]))) {
                var end = i + 1
                if (c == '0' && end < n && code[end] in "xXbBoO") end++
                while (end < n && (code[end].isLetterOrDigit() || code[end] == '_' || code[end] == '.' && end + 1 < n && code[end + 1].isDigit())) end++
                tokens += CodeToken(i, end, CodeTokenKind.Number)
                i = end
                lineStart = false
                continue
            }
            // 标识符
            if (isIdentStart(c)) {
                var end = i + 1
                while (end < n && (isIdentPart(code[end]) || (code[end] == '-' && name in setOf("css", "scss", "bash", "powershell") && end + 1 < n && isIdentPart(code[end + 1])))) end++
                val word = code.substring(i, end)
                val key = if (lang.caseInsensitive) word.lowercase() else word
                val kind = when {
                    key in keywords -> CodeTokenKind.Keyword
                    key in literals -> CodeTokenKind.Literal
                    name == "json" -> null
                    nextNonSpace(code, end) == '(' -> CodeTokenKind.Function
                    lang.capitalizedTypes && word[0].isUpperCase() && word.length > 1 && word.any { it.isLowerCase() } -> CodeTokenKind.Type
                    else -> null
                }
                if (kind != null) tokens += CodeToken(i, end, kind)
                i = end
                lineStart = false
                continue
            }
            if (c == '\n') lineStart = true else if (!c.isWhitespace()) lineStart = false
            i++
        }
        // JSON 的键名与字符串值统一为字符串；YAML / TOML / INI 的键名标为属性
        if (name == "yaml" || name == "toml" || name == "ini") return tokens + keys(code, tokens)
        return tokens
    }

    private fun nextNonSpace(code: String, from: Int): Char? {
        var i = from
        while (i < code.length && (code[i] == ' ' || code[i] == '\t')) i++
        return code.getOrNull(i)
    }

    private fun stringEnd(code: String, start: Int, quote: Char, multiline: Boolean): Int {
        var i = start + 1
        while (i < code.length) {
            val c = code[i]
            if (c == '\\') {
                i += 2
                continue
            }
            if (c == quote) return i + 1
            if (c == '\n' && !multiline) return i
            i++
        }
        return code.length
    }

    private fun isIdentStart(c: Char) = c.isLetter() || c == '_'

    private fun isIdentPart(c: Char) = c.isLetterOrDigit() || c == '_'

    private val KEY_LINE = Regex("""(?m)^[ \t]*(?:- )?([A-Za-z_][\w.\-]*)[ \t]*[:=]""")

    private fun keys(code: String, existing: List<CodeToken>): List<CodeToken> =
        KEY_LINE.findAll(code).mapNotNull { m ->
            val range = m.groups[1]!!.range
            val token = CodeToken(range.first, range.last + 1, CodeTokenKind.Attribute)
            token.takeIf { existing.none { it.start < token.end && token.start < it.end } }
        }.toList()

    /** HTML / XML：注释、标签名、属性名、属性值 */
    private fun markup(code: String): List<CodeToken> {
        val tokens = ArrayList<CodeToken>()
        val n = code.length
        var i = 0
        while (i < n) {
            if (code.startsWith("<!--", i)) {
                val end = code.indexOf("-->", i + 4).let { if (it < 0) n else it + 3 }
                tokens += CodeToken(i, end, CodeTokenKind.Comment)
                i = end
                continue
            }
            if (code[i] == '<' && i + 1 < n && (code[i + 1].isLetter() || code[i + 1] == '/' || code[i + 1] == '!' || code[i + 1] == '?')) {
                var j = i + 1
                if (code[j] == '/' || code[j] == '!' || code[j] == '?') j++
                val nameStart = j
                while (j < n && (code[j].isLetterOrDigit() || code[j] in "-:_.")) j++
                if (j > nameStart) tokens += CodeToken(nameStart, j, CodeTokenKind.Tag)
                while (j < n && code[j] != '>') {
                    val c = code[j]
                    when {
                        c == '"' || c == '\'' -> {
                            val end = code.indexOf(c, j + 1).let { if (it < 0) n else it + 1 }
                            tokens += CodeToken(j, end, CodeTokenKind.String)
                            j = end
                        }
                        c.isLetter() -> {
                            val start = j
                            while (j < n && (code[j].isLetterOrDigit() || code[j] in "-:_@.")) j++
                            tokens += CodeToken(start, j, CodeTokenKind.Attribute)
                        }
                        c == '<' -> break
                        else -> j++
                    }
                }
                i = if (j < n && code[j] == '>') j + 1 else j
                continue
            }
            i++
        }
        return tokens
    }

    /** diff：增加行、删除行、区块头 */
    private fun diff(code: String): List<CodeToken> {
        val tokens = ArrayList<CodeToken>()
        var start = 0
        while (start <= code.length) {
            val end = code.indexOf('\n', start).let { if (it < 0) code.length else it }
            if (end > start) {
                val kind = when {
                    code.startsWith("+++", start) || code.startsWith("---", start) || code.startsWith("@@", start) -> CodeTokenKind.Meta
                    code[start] == '+' -> CodeTokenKind.String
                    code[start] == '-' -> CodeTokenKind.Keyword
                    else -> null
                }
                if (kind != null) tokens += CodeToken(start, end, kind)
            }
            start = end + 1
        }
        return tokens
    }
}

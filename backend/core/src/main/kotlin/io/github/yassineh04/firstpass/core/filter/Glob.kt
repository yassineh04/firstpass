package io.github.yassineh04.firstpass.core.filter

/**
 * A path pattern with `.gitignore`-like rules, independent of the operating system:
 * - paths use `/` and are relative to the repository root;
 * - `*` matches any characters except `/`, and `?` exactly one character except `/`;
 * - `**` followed by `/` matches zero or more directories, and a trailing double star matches everything below;
 * - `{a,b}` matches one of the alternatives;
 * - a pattern without `/` matches the file name at any depth; a leading `/` anchors it at the root.
 */
class Glob(
    val pattern: String,
) {
    private val regex = Regex(translate(normalize(pattern)))

    fun matches(path: String): Boolean = regex.matches(path)

    private companion object {
        const val ANY_DIRECTORIES = "**/"
        const val ANYTHING = "**"

        fun normalize(pattern: String): String =
            when {
                pattern.startsWith("/") -> pattern.removePrefix("/")
                '/' !in pattern -> "$ANY_DIRECTORIES$pattern"
                else -> pattern
            }

        fun translate(glob: String): String {
            val regex = StringBuilder()
            var inBraces = false
            var index = 0
            while (index < glob.length) {
                val char = glob[index]
                val (fragment, length) =
                    when {
                        glob.startsWith(ANY_DIRECTORIES, index) -> "(?:.*/)?" to ANY_DIRECTORIES.length
                        glob.startsWith(ANYTHING, index) -> ".*" to ANYTHING.length
                        char == '*' -> "[^/]*" to 1
                        char == '?' -> "[^/]" to 1
                        char == '{' -> "(?:".also { inBraces = true } to 1
                        char == '}' && inBraces -> ")".also { inBraces = false } to 1
                        char == ',' && inBraces -> "|" to 1
                        else -> Regex.escape(char.toString()) to 1
                    }
                regex.append(fragment)
                index += length
            }
            return regex.toString()
        }
    }
}

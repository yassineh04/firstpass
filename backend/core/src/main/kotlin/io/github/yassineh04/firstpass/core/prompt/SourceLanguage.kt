package io.github.yassineh04.firstpass.core.prompt

/** Names the programming language of a file from its path, for the file headers of the prompt. */
object SourceLanguage {
    private val BY_EXTENSION =
        mapOf(
            "kt" to "kotlin",
            "kts" to "kotlin",
            "java" to "java",
            "scala" to "scala",
            "groovy" to "groovy",
            "gradle" to "groovy",
            "ts" to "typescript",
            "tsx" to "typescript",
            "mts" to "typescript",
            "cts" to "typescript",
            "js" to "javascript",
            "jsx" to "javascript",
            "mjs" to "javascript",
            "cjs" to "javascript",
            "vue" to "vue",
            "py" to "python",
            "go" to "go",
            "rb" to "ruby",
            "rs" to "rust",
            "cs" to "csharp",
            "php" to "php",
            "swift" to "swift",
            "c" to "c",
            "h" to "c",
            "cpp" to "cpp",
            "cc" to "cpp",
            "hpp" to "cpp",
            "sql" to "sql",
            "sh" to "shell",
            "bash" to "shell",
            "ps1" to "powershell",
            "tf" to "terraform",
            "hcl" to "terraform",
            "yml" to "yaml",
            "yaml" to "yaml",
            "json" to "json",
            "xml" to "xml",
            "html" to "html",
            "css" to "css",
            "scss" to "css",
            "toml" to "toml",
            "properties" to "properties",
        )

    fun of(path: String): String {
        val name = path.substringAfterLast('/').lowercase()
        return if (name == "dockerfile") {
            "dockerfile"
        } else {
            BY_EXTENSION[name.substringAfterLast('.', missingDelimiterValue = "")] ?: "text"
        }
    }
}

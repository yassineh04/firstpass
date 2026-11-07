package io.github.yassineh04.firstpass.core.filter

/** Classifies a changed file from its path, using common conventions of the main ecosystems. */
object FileClassifier {
    private val TEST_DIRECTORIES = setOf("test", "tests", "__tests__", "e2e", "spec", "specs")
    private val TEST_FILE_NAMES =
        listOf(
            Regex(""".*(Test|Tests|Spec|IT)\.(kt|kts|java|scala|groovy)"""),
            Regex(""".*\.(test|spec)\.[cm]?[jt]sx?"""),
            Regex("""test_.*\.py"""),
            Regex(""".*_test\.(py|go)"""),
        )

    private val CONFIG_FILE_NAMES =
        setOf(
            "dockerfile",
            "build.gradle.kts",
            "settings.gradle.kts",
            ".gitignore",
            ".gitattributes",
            ".editorconfig",
            ".dockerignore",
            ".nvmrc",
        )
    private val CONFIG_EXTENSIONS =
        setOf("yml", "yaml", "json", "json5", "toml", "properties", "xml", "ini", "cfg", "conf", "hcl", "gradle")
    private val CONFIG_FILE_PATTERN = Regex("""requirements.*\.txt""")

    private val DOCUMENTATION_EXTENSIONS = setOf("md", "mdx", "rst", "adoc", "txt")
    private val DOCUMENTATION_FILE_NAMES = setOf("readme", "license", "notice", "changelog", "authors", "contributing")

    fun kindOf(path: String): FileKind {
        val directories = path.split('/').dropLast(1)
        val name = path.substringAfterLast('/')
        val lowerName = name.lowercase()
        val extension = lowerName.substringAfterLast('.', missingDelimiterValue = "")
        return when {
            directories.any { it in TEST_DIRECTORIES } || TEST_FILE_NAMES.any { it.matches(name) } -> FileKind.TEST
            lowerName in CONFIG_FILE_NAMES || extension in CONFIG_EXTENSIONS -> FileKind.CONFIG
            CONFIG_FILE_PATTERN.matches(lowerName) -> FileKind.CONFIG
            extension in DOCUMENTATION_EXTENSIONS -> FileKind.DOCUMENTATION
            lowerName.substringBefore('.') in DOCUMENTATION_FILE_NAMES -> FileKind.DOCUMENTATION
            else -> FileKind.SOURCE
        }
    }
}

package io.github.yassineh04.firstpass.core.filter

/**
 * Built-in exclusions, applied before the repository settings and impossible to override:
 * files that may hold secrets are never sent to a model, and generated or binary files
 * only add noise.
 */
internal object DefaultExclusions {
    private val SECRET_EXTENSIONS = setOf("pem", "key", "p12", "pfx", "jks", "keystore")

    private val LOCKFILES =
        setOf(
            "package-lock.json",
            "yarn.lock",
            "pnpm-lock.yaml",
            "bun.lockb",
            "gradle.lockfile",
            "poetry.lock",
            "pipfile.lock",
            "uv.lock",
            "cargo.lock",
            "composer.lock",
            "gemfile.lock",
            "go.sum",
            ".terraform.lock.hcl",
        )

    private val BUILD_DIRECTORIES = setOf("build", "dist", "generated", "node_modules")

    private val BINARY_EXTENSIONS =
        setOf(
            "png",
            "jpg",
            "jpeg",
            "gif",
            "bmp",
            "webp",
            "ico",
            "svg",
            "pdf",
            "zip",
            "gz",
            "tgz",
            "tar",
            "jar",
            "war",
            "class",
            "wasm",
            "so",
            "dll",
            "dylib",
            "exe",
            "bin",
            "woff",
            "woff2",
            "ttf",
            "otf",
            "eot",
            "mp3",
            "mp4",
            "mov",
            "avi",
            "webm",
        )

    fun reasonFor(path: String): SkipReason? {
        val directories = path.split('/').dropLast(1)
        val name = path.substringAfterLast('/').lowercase()
        val extension = name.substringAfterLast('.', missingDelimiterValue = "")
        return when {
            name == ".env" || name.startsWith(".env.") || extension in SECRET_EXTENSIONS -> SkipReason.SENSITIVE
            name in LOCKFILES -> SkipReason.LOCKFILE
            directories.any { it in BUILD_DIRECTORIES } -> SkipReason.BUILD_OUTPUT
            name.endsWith(".min.js") || name.endsWith(".min.css") || extension == "map" -> SkipReason.MINIFIED
            extension in BINARY_EXTENSIONS -> SkipReason.BINARY
            else -> null
        }
    }
}

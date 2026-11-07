package io.github.yassineh04.firstpass.core.filter

import io.github.yassineh04.firstpass.core.diff.DiffFile
import io.github.yassineh04.firstpass.core.diff.FileStatus
import io.github.yassineh04.firstpass.core.settings.ReviewSettings
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.Test

class FileFilterTest {
    private fun file(
        path: String,
        status: FileStatus = FileStatus.MODIFIED,
        patch: String? = "@@ -1 +1 @@\n-a\n+b",
    ) = DiffFile(path, status, patch)

    private fun skippedBy(
        settings: ReviewSettings,
        vararg files: DiffFile,
    ) = FileFilter(settings).filter(files.toList()).skipped.map { it.path to it.reason }

    @Test
    fun `keeps source, test and config files, sources first`() {
        val result =
            FileFilter(ReviewSettings()).filter(
                listOf(file("build.gradle.kts"), file("src/test/kotlin/FooTest.kt"), file("src/main/kotlin/Foo.kt")),
            )

        result.reviewable.map { it.file.path to it.kind } shouldContainExactly
            listOf(
                "src/main/kotlin/Foo.kt" to FileKind.SOURCE,
                "src/test/kotlin/FooTest.kt" to FileKind.TEST,
                "build.gradle.kts" to FileKind.CONFIG,
            )
        result.skipped.shouldBeEmpty()
    }

    @Test
    fun `skips lockfiles, build output, minified files and binaries, with the reason`() {
        skippedBy(
            ReviewSettings(),
            file("frontend/package-lock.json"),
            file("yarn.lock"),
            file("frontend/dist/assets/index.js"),
            file("backend/core/build/generated/Foo.kt"),
            file("node_modules/vue/index.js"),
            file("src/generated/Api.kt"),
            file("public/app.min.js"),
            file("assets/LOGO.PNG"),
        ) shouldContainExactly
            listOf(
                "frontend/package-lock.json" to SkipReason.LOCKFILE,
                "yarn.lock" to SkipReason.LOCKFILE,
                "frontend/dist/assets/index.js" to SkipReason.BUILD_OUTPUT,
                "backend/core/build/generated/Foo.kt" to SkipReason.BUILD_OUTPUT,
                "node_modules/vue/index.js" to SkipReason.BUILD_OUTPUT,
                "src/generated/Api.kt" to SkipReason.BUILD_OUTPUT,
                "public/app.min.js" to SkipReason.MINIFIED,
                "assets/LOGO.PNG" to SkipReason.BINARY,
            )
    }

    @Test
    fun `never sends files that may hold secrets, even when they are included`() {
        skippedBy(
            ReviewSettings(include = listOf("**")),
            file(".env"),
            file("config/.env.local"),
            file("certs/server.pem"),
            file("keys/app.key"),
        ) shouldContainExactly
            listOf(
                ".env" to SkipReason.SENSITIVE,
                "config/.env.local" to SkipReason.SENSITIVE,
                "certs/server.pem" to SkipReason.SENSITIVE,
                "keys/app.key" to SkipReason.SENSITIVE,
            )
    }

    @Test
    fun `skips documentation by default`() {
        skippedBy(ReviewSettings(), file("README.md"), file("docs/guide.md"), file("LICENSE")) shouldContainExactly
            listOf(
                "README.md" to SkipReason.DOCUMENTATION,
                "docs/guide.md" to SkipReason.DOCUMENTATION,
                "LICENSE" to SkipReason.DOCUMENTATION,
            )
    }

    @Test
    fun `skips deleted files and files without a patch`() {
        skippedBy(
            ReviewSettings(),
            file("src/Old.kt", status = FileStatus.REMOVED),
            file("src/Huge.kt", patch = null),
        ) shouldContainExactly
            listOf("src/Old.kt" to SkipReason.DELETED, "src/Huge.kt" to SkipReason.NO_PATCH)
    }

    @Test
    fun `applies the repository exclude patterns`() {
        skippedBy(
            ReviewSettings(exclude = listOf("src/legacy/**")),
            file("src/legacy/Old.kt"),
            file("src/New.kt"),
        ) shouldContainExactly
            listOf("src/legacy/Old.kt" to SkipReason.EXCLUDED_BY_CONFIG)
    }

    @Test
    fun `keeps only included files when include patterns are set`() {
        val result =
            FileFilter(ReviewSettings(include = listOf("backend/**"))).filter(
                listOf(file("backend/Foo.kt"), file("frontend/src/App.vue")),
            )

        result.reviewable.map { it.file.path } shouldContainExactly listOf("backend/Foo.kt")
        result.skipped.map { it.path to it.reason } shouldContainExactly
            listOf("frontend/src/App.vue" to SkipReason.NOT_INCLUDED)
    }

    @Test
    fun `reports the built-in reason before the repository patterns`() {
        skippedBy(ReviewSettings(exclude = listOf("**/*.lock")), file("yarn.lock")) shouldContainExactly
            listOf("yarn.lock" to SkipReason.LOCKFILE)
    }
}

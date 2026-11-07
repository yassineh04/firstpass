package io.github.yassineh04.firstpass.core.prompt

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SourceLanguageTest {
    @Test
    fun `names the language of common file types`() {
        mapOf(
            "src/Main.kt" to "kotlin",
            "build.gradle.kts" to "kotlin",
            "src/Main.java" to "java",
            "src/app.ts" to "typescript",
            "src/App.vue" to "vue",
            "scripts/tool.py" to "python",
            "infra/providers.tf" to "terraform",
            "Dockerfile" to "dockerfile",
            ".github/workflows/ci.yml" to "yaml",
            "notes.unknown" to "text",
        ).forEach { (path, language) -> SourceLanguage.of(path) shouldBe language }
    }
}

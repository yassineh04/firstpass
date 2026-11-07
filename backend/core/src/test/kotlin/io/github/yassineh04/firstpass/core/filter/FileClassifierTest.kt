package io.github.yassineh04.firstpass.core.filter

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class FileClassifierTest {
    @Test
    fun `recognises test files from common conventions`() {
        listOf(
            "backend/core/src/test/kotlin/FooTest.kt",
            "src/main/java/FooIT.java",
            "frontend/src/components/__tests__/Switch.spec.ts",
            "frontend/e2e/home.spec.ts",
            "app/utils.test.js",
            "tests/test_api.py",
            "pkg/handler_test.go",
        ).forEach { FileClassifier.kindOf(it) shouldBe FileKind.TEST }
    }

    @Test
    fun `recognises configuration files`() {
        listOf(
            "src/main/resources/application.yml",
            "frontend/package.json",
            "frontend/tsconfig.app.json",
            "backend/settings.gradle.kts",
            "backend/core/build.gradle.kts",
            "gradle.properties",
            "Dockerfile",
            ".github/workflows/ci.yml",
            "requirements.txt",
        ).forEach { FileClassifier.kindOf(it) shouldBe FileKind.CONFIG }
    }

    @Test
    fun `recognises documentation`() {
        listOf("README.md", "docs/adr/0001-function-urls.md", "LICENSE", "docs/guide.rst", "notes.txt")
            .forEach { FileClassifier.kindOf(it) shouldBe FileKind.DOCUMENTATION }
    }

    @Test
    fun `treats everything else as source code`() {
        listOf("backend/core/src/main/kotlin/Foo.kt", "infra/providers.tf", "frontend/src/App.vue", "scripts/deploy.sh")
            .forEach { FileClassifier.kindOf(it) shouldBe FileKind.SOURCE }
    }
}

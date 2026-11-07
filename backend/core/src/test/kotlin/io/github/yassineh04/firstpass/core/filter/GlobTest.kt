package io.github.yassineh04.firstpass.core.filter

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class GlobTest {
    private fun matches(
        pattern: String,
        path: String,
    ): Boolean = Glob(pattern).matches(path)

    @Test
    fun `a pattern without a slash matches the file name at any depth`() {
        matches("*.kt", "Main.kt") shouldBe true
        matches("*.kt", "src/main/Main.kt") shouldBe true
        matches("*.kt", "Main.kts") shouldBe false
    }

    @Test
    fun `a single star does not cross directories`() {
        matches("src/*.kt", "src/Main.kt") shouldBe true
        matches("src/*.kt", "src/app/Main.kt") shouldBe false
    }

    @Test
    fun `a double star matches any number of directories, including none`() {
        matches("src/**", "src/app/deep/Main.kt") shouldBe true
        matches("src/**", "srcx/Main.kt") shouldBe false
        matches("**/generated/**", "generated/Api.kt") shouldBe true
        matches("**/generated/**", "backend/generated/Api.kt") shouldBe true
        matches("**/*.lock", "yarn.lock") shouldBe true
        matches("**/*.lock", "frontend/yarn.lock") shouldBe true
    }

    @Test
    fun `a question mark matches exactly one character`() {
        matches("file?.txt", "file1.txt") shouldBe true
        matches("file?.txt", "file10.txt") shouldBe false
    }

    @Test
    fun `braces list alternatives`() {
        matches("**/*.{js,ts}", "app/main.ts") shouldBe true
        matches("**/*.{js,ts}", "main.js") shouldBe true
        matches("**/*.{js,ts}", "main.tsx") shouldBe false
    }

    @Test
    fun `other characters are matched literally`() {
        matches("a+b(1).kt", "a+b(1).kt") shouldBe true
        matches("a+b(1).kt", "aab1.kt") shouldBe false
        matches("docs/**", "docs/adr/0001.md") shouldBe true
    }

    @Test
    fun `a leading slash anchors the pattern at the repository root`() {
        matches("/Main.kt", "Main.kt") shouldBe true
        matches("/Main.kt", "src/Main.kt") shouldBe false
    }
}

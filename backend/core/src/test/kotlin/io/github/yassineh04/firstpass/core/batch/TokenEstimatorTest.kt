package io.github.yassineh04.firstpass.core.batch

import io.github.yassineh04.firstpass.core.diff.DiffAnnotator
import io.github.yassineh04.firstpass.core.diff.DiffFile
import io.github.yassineh04.firstpass.core.diff.FileStatus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TokenEstimatorTest {
    @Test
    fun `counts 4 characters per token plus a 50 percent margin, about 2_7 characters per token, rounded up`() {
        TokenEstimator.estimate("") shouldBe 0
        TokenEstimator.estimate("abcd") shouldBe 2
        TokenEstimator.estimate("x".repeat(10)) shouldBe 4
        TokenEstimator.estimate("x".repeat(400)) shouldBe 150
    }

    @Test
    fun `stays at least 5 percent above the counts Gemini reported for code-heavy prompts`() {
        // Three real prompts made mostly of annotated Kotlin diffs (pull requests 24, 26 and 27 of this
        // repository), measured on 2026-11-08: characters (rounded down) and Gemini's input token counts.
        val measured = mapOf(49_700 to 16_962, 57_960 to 19_671, 41_835 to 14_475)

        for ((chars, reported) in measured) {
            TokenEstimator.estimate(chars).toDouble() shouldBeGreaterThan reported * 1.05
        }
    }

    @Test
    fun `the file cost covers the longest header line the prompt writes around a file`() {
        for (path in listOf("a.ts", "src/main/kotlin/io/github/example/VeryLongServiceName.kt")) {
            val header = "=== File: $path (modified, typescript)\n"

            TokenEstimator.estimate(header) shouldBeLessThanOrEqual TokenEstimator.fileOverhead(path)
        }
    }

    @Test
    fun `gives the largest text length that fits a number of tokens`() {
        TokenEstimator.maxChars(150) shouldBe 400
        for (tokens in 0..500) {
            TokenEstimator.estimate(TokenEstimator.maxChars(tokens)) shouldBeLessThanOrEqual tokens
            TokenEstimator.estimate(TokenEstimator.maxChars(tokens) + 1) shouldBeGreaterThan tokens
        }
    }

    @Test
    fun `counts a file header on top of the annotated hunks`() {
        val file =
            DiffAnnotator
                .annotate(
                    DiffFile("src/Main.kt", FileStatus.MODIFIED, "@@ -1 +1 @@\n-a\n+b"),
                ).shouldNotBeNull()

        TokenEstimator.estimate(file) shouldBeGreaterThan TokenEstimator.estimate(file.render())
    }
}

package io.github.yassineh04.firstpass.core.batch

import io.github.yassineh04.firstpass.core.diff.DiffAnnotator
import io.github.yassineh04.firstpass.core.diff.DiffFile
import io.github.yassineh04.firstpass.core.diff.FileStatus
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TokenEstimatorTest {
    @Test
    fun `counts about 4 characters per token, plus a 20 percent margin, rounded up`() {
        TokenEstimator.estimate("") shouldBe 0
        TokenEstimator.estimate("abcd") shouldBe 2
        TokenEstimator.estimate("x".repeat(10)) shouldBe 3
        TokenEstimator.estimate("x".repeat(400)) shouldBe 120
    }

    @Test
    fun `gives the largest text length that fits a number of tokens`() {
        TokenEstimator.maxChars(120) shouldBe 400
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

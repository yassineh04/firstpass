package io.github.yassineh04.firstpass.eval

import io.github.yassineh04.firstpass.core.settings.ReviewLanguage
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.nio.file.Path

class ReviewArgumentsTest {
    @Test
    fun `reads the patch file and every option`() {
        ReviewArguments.parse(
            listOf(
                "--provider",
                "gemini",
                "changes.patch",
                "--model",
                "gemini-x",
                "--title",
                "Add a cache",
                "--language",
                "FR",
                "--output",
                "build/review.json",
            ),
        ) shouldBe
            ReviewArguments(
                patch = Path.of("changes.patch"),
                provider = "gemini",
                model = "gemini-x",
                title = "Add a cache",
                language = ReviewLanguage.FR,
                output = Path.of("build/review.json"),
            )
    }

    @Test
    fun `uses defaults for the options left out`() {
        ReviewArguments.parse(listOf("changes.patch")) shouldBe ReviewArguments(patch = Path.of("changes.patch"))
    }

    @Test
    fun `explains what is wrong with the arguments`() {
        mapOf(
            emptyList<String>() to "patch file",
            listOf("a.patch", "b.patch") to "one patch file",
            listOf("a.patch", "--colour", "red") to "Unknown option: --colour",
            listOf("a.patch", "--model") to "Missing value after --model",
            listOf("a.patch", "--model", "--title", "x") to "Missing value after --model",
            listOf("a.patch", "--language", "de") to "en or fr",
        ).forEach { (args, message) ->
            shouldThrow<CliError> { ReviewArguments.parse(args) }.message shouldContain message
        }
    }
}

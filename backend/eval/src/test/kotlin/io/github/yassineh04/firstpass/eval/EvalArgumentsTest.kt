package io.github.yassineh04.firstpass.eval

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.nio.file.Path

class EvalArgumentsTest {
    @Test
    fun `reads every option, and uses defaults for the others`() {
        EvalArguments.parse(
            listOf(
                "--provider",
                "gemini",
                "--model",
                "m",
                "--runs",
                "3",
                "--cases",
                "x",
                "--output",
                "r.md",
                "--pause",
                "0",
            ),
        ) shouldBe EvalArguments("gemini", "m", 3, Path.of("x"), Path.of("r.md"), 0)
        EvalArguments.parse(emptyList()) shouldBe EvalArguments()
        EvalArguments().cases shouldBe Path.of("eval", "cases")
    }

    @Test
    fun `explains what is wrong with the arguments`() {
        mapOf(
            listOf("--runs", "0") to "--runs must be a whole number of at least 1",
            listOf("--runs", "two") to "--runs must be a whole number of at least 1",
            listOf("--pause", "-1") to "--pause must be a whole number of at least 0",
            listOf("--pause", "soon") to "--pause must be a whole number of at least 0",
            listOf("cases") to "takes no file",
        ).forEach { (args, message) ->
            shouldThrow<CliError> { EvalArguments.parse(args) }.message shouldContain message
        }
    }
}

package io.github.yassineh04.firstpass.eval

import io.github.yassineh04.firstpass.core.batch.ProviderBudget
import io.github.yassineh04.firstpass.core.finding.Category
import io.github.yassineh04.firstpass.core.finding.ModelComment
import io.github.yassineh04.firstpass.core.finding.ModelReview
import io.github.yassineh04.firstpass.core.finding.Severity
import io.github.yassineh04.firstpass.core.review.LlmFailure
import io.github.yassineh04.firstpass.core.review.LlmPort
import io.github.yassineh04.firstpass.core.review.LlmResult
import io.github.yassineh04.firstpass.core.review.ReviewProvider
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class CliTest {
    @TempDir
    lateinit var dir: Path

    private val out = ByteArrayOutputStream()
    private val err = ByteArrayOutputStream()

    @Test
    fun `reviews a patch with the fake provider and prints the report as JSON`() {
        val exit = cli().run(listOf("review", patch("val total = 1").toString(), "--provider", "fake"))

        exit shouldBe 0
        val json = JSON.readTree(stdout())
        json.path("outcome").asString() shouldBe "completed"
        json.path("report").path("inline").size() shouldBe 0
        json
            .path("report")
            .path("usage")
            .path(0)
            .path("provider")
            .asString() shouldBe "fake"
        stderr() shouldContain "fake"
    }

    @Test
    fun `reports a secret found in the patch without printing its value`() {
        val awsKey = listOf("AK", "IA", "Z".repeat(16)).joinToString("")

        cli().run(listOf("review", patch("val key = \"$awsKey\"").toString(), "--provider", "fake")) shouldBe 0

        val finding =
            JSON
                .readTree(stdout())
                .path("report")
                .path("inline")
                .path(0)
        finding.path("severity").asString() shouldBe "CRITICAL"
        finding.path("category").asString() shouldBe "SECURITY"
        stdout() shouldNotContain awsKey
    }

    @Test
    fun `takes the provider from the settings when no option names one`() {
        cli(settings = mapOf("FIRSTPASS_LLM_PROVIDER" to "gemini")).run(listOf("review", patch().toString())) shouldBe 1

        stderr() shouldContain "GEMINI_API_KEY"
    }

    @Test
    fun `writes the JSON to a UTF-8 file with --output and passes the title and language to the model`() {
        var userMessage = ""
        val llm =
            LlmPort { request ->
                userMessage = request.prompt.user
                LlmResult.Success(ModelReview("Résumé.", listOf(comment("Problème de nullité"))), "scripted", "m", 1, 1)
            }
        val output = dir.resolve("out/review.json")

        val exit =
            cli(provider = llm).run(
                listOf(
                    "review",
                    patch().toString(),
                    "--title",
                    "Add a cache",
                    "--language",
                    "fr",
                    "--output",
                    output.toString(),
                ),
            )

        exit shouldBe 0
        val json = JSON.readTree(Files.readString(output, Charsets.UTF_8))
        json
            .path("report")
            .path("inline")
            .path(0)
            .path("title")
            .asString() shouldBe "Problème de nullité"
        userMessage shouldContain "Add a cache"
        stdout() shouldBe ""
        stderr() shouldContain output.toString()
    }

    @Test
    fun `exits with 2 when no provider gives a valid answer`() {
        val failing = LlmPort { LlmResult.Failure(LlmFailure.UNAVAILABLE, "scripted", "connection refused") }

        cli(provider = failing).run(listOf("review", patch().toString())) shouldBe 2

        JSON.readTree(stdout()).path("outcome").asString() shouldBe "unavailable"
        stderr() shouldContain "UNAVAILABLE"
    }

    @Test
    fun `explains a missing patch file, a file without any diff, and an unknown command`() {
        val notADiff = dir.resolve("notes.txt").also { Files.writeString(it, "just some notes") }

        cli().run(listOf("review", dir.resolve("missing.patch").toString())) shouldBe 1
        cli().run(listOf("review", notADiff.toString())) shouldBe 1
        cli().run(listOf("frobnicate")) shouldBe 1

        stderr() shouldContain "not found"
        stderr() shouldContain "git diff"
        stderr() shouldContain "Unknown command: frobnicate"
    }

    @Test
    fun `evaluates the cases of a folder and writes the Markdown report`() {
        val cases = Files.createDirectories(dir.resolve("cases"))
        Files.createDirectories(cases.resolve("clean-one")).also {
            Files.writeString(it.resolve("diff.patch"), patchAdding(PATH, "val total = 1"))
            Files.writeString(it.resolve("expected.json"), """{"title": "T", "description": "D", "expected": []}""")
        }
        val report = dir.resolve("results/report.md")

        val exit =
            cli().run(
                listOf(
                    "eval",
                    "--provider",
                    "fake",
                    "--cases",
                    cases.toString(),
                    "--runs",
                    "1",
                    "--output",
                    report.toString(),
                ),
            )

        exit shouldBe 0
        Files.readString(report) shouldContain "# Evaluation of 2026-11-07"
        stderr() shouldContain "Precision n/a"
        stderr() shouldContain "noise 0.0"
    }

    @Test
    fun `prints the usage on request`() {
        cli().run(listOf("--help")) shouldBe 0

        stdout() shouldContain "Usage"
        stdout() shouldContain "--provider"
    }

    private fun cli(
        settings: Map<String, String> = emptyMap(),
        provider: LlmPort? = null,
    ): Cli {
        val clock = Clock.fixed(Instant.parse("2026-11-07T15:00:00Z"), ZoneOffset.UTC)
        val printOut = PrintStream(out, true, Charsets.UTF_8)
        val printErr = PrintStream(err, true, Charsets.UTF_8)
        return if (provider == null) {
            Cli(settings, printOut, printErr, clock)
        } else {
            Cli(settings, printOut, printErr, clock) { _, _, _ ->
                listOf(ReviewProvider("scripted", provider, ProviderBudget(20_000, 6), Int.MAX_VALUE))
            }
        }
    }

    /** A git diff that adds the given lines, numbered from 10, to one Kotlin file. */
    private fun patch(vararg added: String = arrayOf("val total = 1")): Path {
        val text =
            (
                listOf(
                    "diff --git a/$PATH b/$PATH",
                    "index 1111111..2222222 100644",
                    "--- a/$PATH",
                    "+++ b/$PATH",
                    "@@ -10,0 +10,${added.size} @@",
                ) + added.map { "+$it" }
            ).joinToString("\n", postfix = "\n")
        return dir.resolve("change-${text.hashCode()}.patch").also { Files.writeString(it, text) }
    }

    private fun comment(title: String) =
        ModelComment(PATH, null, 10, Severity.MAJOR, Category.BUG, title, "Expliqué.", null, 0.9)

    private fun stdout() = out.toString(Charsets.UTF_8)

    private fun stderr() = err.toString(Charsets.UTF_8)

    private companion object {
        const val PATH = "src/main/kotlin/Cart.kt"
    }
}

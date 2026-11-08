package io.github.yassineh04.firstpass.eval

import io.github.yassineh04.firstpass.core.finding.Category
import io.github.yassineh04.firstpass.core.finding.Severity
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class CaseLoaderTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `reads every case folder, sorted by id`() {
        write(
            "b-clean",
            patchAdding("src/B.kt", "val b = 2"),
            """{"title": "B", "description": "Clean.", "expected": []}""",
        )
        write(
            "a-bug",
            patchAdding("src/A.kt", "val a = 1"),
            """
            {"title": "Add a", "description": "A bug.",
             "expected": [{"path": "src/A.kt", "line": 10, "category": "security", "severity": "critical"}]}
            """.trimIndent(),
        )

        val cases = CaseLoader.load(dir)

        cases.map { it.id } shouldContainExactly listOf("a-bug", "b-clean")
        cases.first().title shouldBe "Add a"
        cases.first().description shouldBe "A bug."
        cases.first().patch shouldContain "+val a = 1"
        cases.first().expected shouldContainExactly
            listOf(ExpectedFinding("src/A.kt", 10, Category.SECURITY, Severity.CRITICAL))
        cases.last().clean shouldBe true
    }

    @Test
    fun `explains a missing folder, a missing file and an unknown category`() {
        shouldThrow<CliError> { CaseLoader.load(dir.resolve("absent")) }.message shouldContain "No evaluation case"

        Files.createDirectories(dir.resolve("no-expected")).also { Files.writeString(it.resolve("diff.patch"), "x") }
        shouldThrow<CliError> { CaseLoader.load(dir) }.message.let {
            it shouldContain "no-expected"
            it shouldContain "expected.json"
        }

        Files.writeString(
            dir.resolve("no-expected/expected.json"),
            """
            {"title": "T", "description": "D",
             "expected": [{"path": "a", "line": 1, "category": "style", "severity": "major"}]}
            """.trimIndent(),
        )
        shouldThrow<CliError> { CaseLoader.load(dir) }.message shouldContain "style"
    }

    private fun write(
        id: String,
        patch: String,
        expected: String,
    ) {
        val case = Files.createDirectories(dir.resolve(id))
        Files.writeString(case.resolve("diff.patch"), patch)
        Files.writeString(case.resolve("expected.json"), expected)
    }
}

package io.github.yassineh04.firstpass.local

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class LocalEnvironmentTest {
    @TempDir
    lateinit var folder: Path

    @Test
    fun `reads KEY=value lines, without comments, quotes or malformed lines`() {
        val bom = Char(0xFEFF)
        val lines =
            listOf(
                "$bom# Local configuration",
                "",
                "GITHUB_CLIENT_ID=Iv1.abc",
                "export FIRSTPASS_LLM_PROVIDER = gemini",
                "SMEE_URL=\"https://smee.io/channel\"",
                "QUOTED='a=b'",
                "EMPTY=",
                "not a setting",
                "1BAD=x",
            )

        LocalEnvironment.parse(lines) shouldBe
            mapOf(
                "GITHUB_CLIENT_ID" to "Iv1.abc",
                "FIRSTPASS_LLM_PROVIDER" to "gemini",
                "SMEE_URL" to "https://smee.io/channel",
                "QUOTED" to "a=b",
                "EMPTY" to "",
            )
    }

    @Test
    fun `lets the environment win over the file, and works without a file`() {
        val file = folder.resolve(".env").also { Files.writeString(it, "A=file\nB=file\n") }

        LocalEnvironment.load(file, environment = mapOf("B" to "env")) shouldBe mapOf("A" to "file", "B" to "env")
        LocalEnvironment.load(folder.resolve("missing.env"), environment = mapOf("C" to "env")) shouldBe
            mapOf("C" to "env")
    }
}

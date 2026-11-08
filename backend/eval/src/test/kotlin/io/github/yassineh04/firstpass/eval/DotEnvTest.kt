package io.github.yassineh04.firstpass.eval

import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class DotEnvTest {
    @Test
    fun `reads keys and values, and ignores comments, blank lines and malformed lines`() {
        DotEnv.parse(
            listOf(
                "﻿# Local configuration",
                "",
                "FIRSTPASS_LLM_PROVIDER=gemini",
                "  OLLAMA_MODEL = qwen-coder  ",
                "OLLAMA_BASE_URL=http://localhost:11434/v1/?a=b",
                "TITLE=\"quoted value\"",
                "OTHER='single quoted'",
                "export EXPORTED=yes",
                "GROQ_API_KEY=",
                "not a setting",
                "=no key",
            ),
        ) shouldBe
            mapOf(
                "FIRSTPASS_LLM_PROVIDER" to "gemini",
                "OLLAMA_MODEL" to "qwen-coder",
                "OLLAMA_BASE_URL" to "http://localhost:11434/v1/?a=b",
                "TITLE" to "quoted value",
                "OTHER" to "single quoted",
                "EXPORTED" to "yes",
                "GROQ_API_KEY" to "",
            )
    }

    @Test
    fun `reads a file, and returns nothing when there is no file`(
        @TempDir dir: Path,
    ) {
        val file = dir.resolve(".env")
        Files.writeString(file, "FIRSTPASS_LLM_PROVIDER=fake\n")

        DotEnv.load(file) shouldBe mapOf("FIRSTPASS_LLM_PROVIDER" to "fake")
        DotEnv.load(dir.resolve("missing.env")).shouldBeEmpty()
    }
}

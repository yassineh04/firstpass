package io.github.yassineh04.firstpass.core.prompt

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.security.MessageDigest

/**
 * Evaluation results are compared per prompt version. Any change to the prompt templates must
 * bump PROMPT_VERSION and record the new fingerprint here, otherwise this test fails.
 */
class PromptVersionTest {
    private val fingerprints =
        mapOf(
            "1" to "c579cd2c1356d2681170a5f5c185ae463c5c2e0a3fd4278fc11392b1d3853987",
        )

    @Test
    fun `the prompt templates match the fingerprint recorded for PROMPT_VERSION`() {
        val templates = PromptBuilder.SYSTEM_TEMPLATE + PromptBuilder.USER_TEMPLATE
        val actual =
            MessageDigest
                .getInstance("SHA-256")
                .digest(templates.toByteArray())
                .joinToString("") { "%02x".format(it) }

        withClue("Prompt changed: bump PROMPT_VERSION and record fingerprint $actual") {
            fingerprints[PromptBuilder.PROMPT_VERSION] shouldBe actual
        }
    }
}

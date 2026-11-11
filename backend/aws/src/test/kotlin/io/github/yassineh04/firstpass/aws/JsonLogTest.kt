package io.github.yassineh04.firstpass.aws

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class JsonLogTest {
    @Test
    fun `writes one JSON object per line, with typed fields and without the empty ones`() {
        val buffer = ByteArrayOutputStream()
        val log =
            JsonLog(
                PrintStream(buffer, true, Charsets.UTF_8),
                Clock.fixed(Instant.parse("2026-11-12T10:00:00Z"), ZoneOffset.UTC),
            )

        log.info(
            "Job done",
            mapOf(
                "pullRequest" to 3,
                "durationMs" to 1500L,
                "published" to true,
                "outcome" to Outcome,
                "none" to null,
            ),
        )
        log.error("Job failed")

        buffer.toString(Charsets.UTF_8).lines().filter { it.isNotEmpty() } shouldBe
            listOf(
                """{"timestamp":"2026-11-12T10:00:00Z","level":"INFO","message":"Job done","pullRequest":3,""" +
                    """"durationMs":1500,"published":true,"outcome":"Reviewed"}""",
                """{"timestamp":"2026-11-12T10:00:00Z","level":"ERROR","message":"Job failed"}""",
            )
    }

    private object Outcome {
        override fun toString() = "Reviewed"
    }
}

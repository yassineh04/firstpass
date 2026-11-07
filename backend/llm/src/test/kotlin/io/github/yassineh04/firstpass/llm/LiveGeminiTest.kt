package io.github.yassineh04.firstpass.llm

import io.github.yassineh04.firstpass.core.diff.DiffAnnotator
import io.github.yassineh04.firstpass.core.diff.DiffFile
import io.github.yassineh04.firstpass.core.diff.FileStatus
import io.github.yassineh04.firstpass.core.prompt.PromptBuilder
import io.github.yassineh04.firstpass.core.review.LlmRequest
import io.github.yassineh04.firstpass.core.review.LlmResult
import io.github.yassineh04.firstpass.core.review.PullRequestInfo
import io.github.yassineh04.firstpass.core.settings.ReviewSettings
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.File

/**
 * One real call to Gemini, run by hand only: it is skipped unless GEMINI_API_KEY is set, so CI
 * never runs it. The code sent is a synthetic example. The result is written to
 * build/live-gemini-review.json for reading.
 */
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
class LiveGeminiTest {
    @Test
    fun `Gemini answers a real review request in the expected format`() {
        val config = Providers.gemini(ApiKey(System.getenv("GEMINI_API_KEY")))
        val file =
            DiffAnnotator
                .annotate(
                    DiffFile(
                        path = "src/main/kotlin/UserService.kt",
                        status = FileStatus.MODIFIED,
                        patch =
                            listOf(
                                "@@ -10,3 +10,5 @@ class UserService(private val repo: UserRepository) {",
                                "     fun findUser(id: String): User {",
                                "-        return repo.find(id) ?: throw UserNotFound(id)",
                                "+        val user = repo.find(id)!!",
                                "+        log.info(\"found user \${user.email}\")",
                                "+        return user",
                                "     }",
                            ).joinToString("\n"),
                    ),
                ).shouldNotBeNull()
        val prompt =
            PromptBuilder(ReviewSettings()).build(
                PullRequestInfo(
                    number = 1,
                    title = "Simplify user lookup",
                    description = "Removes the custom exception.",
                ),
                listOf(file),
                batchNumber = 1,
                batchCount = 1,
                boundary = "f1rstpa55b0undary",
            )

        val result = OpenAiCompatibleClient(config).review(LlmRequest(prompt, config.budget.outputTokens))

        File(
            "build/live-gemini-review.json",
        ).writeText(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result))
        withClue("See build/live-gemini-review.json: $result") { result.shouldBeInstanceOf<LlmResult.Success>() }
    }
}

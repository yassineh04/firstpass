package io.github.yassineh04.firstpass.llm

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath
import com.github.tomakehurst.wiremock.client.WireMock.notContaining
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import com.github.tomakehurst.wiremock.stubbing.Scenario
import io.github.yassineh04.firstpass.core.batch.ProviderBudget
import io.github.yassineh04.firstpass.core.prompt.Prompt
import io.github.yassineh04.firstpass.core.review.LlmFailure
import io.github.yassineh04.firstpass.core.review.LlmRequest
import io.github.yassineh04.firstpass.core.review.LlmResult
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class OpenAiCompatibleClientTest {
    private val server = WireMockServer(options().dynamicPort())
    private val sleeps = mutableListOf<Duration>()
    private val retry = RetryPolicy(random = Random(0), sleeper = { sleeps += it })
    private val request =
        LlmRequest(Prompt(system = "SYSTEM PROMPT", user = "USER MESSAGE", version = "1"), maxOutputTokens = 1500)

    @BeforeEach
    fun start() = server.start()

    @AfterEach
    fun stop() {
        if (server.isRunning) server.stop()
    }

    private fun config(
        outputMode: OutputMode = OutputMode.JSON_SCHEMA,
        apiKey: ApiKey? = ApiKey("test-key"),
        reasoningEffort: String? = null,
        baseUrl: String = server.baseUrl() + "/v1/",
    ) = ProviderConfig(
        name = "test",
        baseUrl = baseUrl,
        model = "test-model",
        apiKey = apiKey,
        outputMode = outputMode,
        budget = ProviderBudget(inputTokensPerBatch = 20_000, maxBatches = 6, outputTokens = 1500),
        tokensPerDay = Int.MAX_VALUE,
        reasoningEffort = reasoningEffort,
        requestTimeout = 5.seconds,
    )

    private fun client(config: ProviderConfig = config()) = OpenAiCompatibleClient(config, retry = retry)

    private fun completion(
        content: String,
        finishReason: String = "stop",
        withUsage: Boolean = true,
    ): String {
        val body =
            mutableMapOf<String, Any>(
                "choices" to
                    listOf(
                        mapOf(
                            "index" to 0,
                            "message" to mapOf("role" to "assistant", "content" to content),
                            "finish_reason" to finishReason,
                        ),
                    ),
            )
        if (withUsage) body["usage"] = mapOf("prompt_tokens" to 120, "completion_tokens" to 40, "total_tokens" to 160)
        return JSON.writeValueAsString(body)
    }

    /** Stubs consecutive answers to the chat endpoint, one per call. */
    private fun answers(vararg responses: ResponseDefinitionBuilderSupplier) {
        responses.forEachIndexed { index, response ->
            server.stubFor(
                post(urlEqualTo(PATH))
                    .inScenario("calls")
                    .whenScenarioStateIs(if (index == 0) Scenario.STARTED else "call-$index")
                    .willReturn(response())
                    .willSetStateTo("call-${index + 1}"),
            )
        }
    }

    private fun ok(content: String = VALID_ANSWER) = ResponseDefinitionBuilderSupplier { okJson(completion(content)) }

    private fun status(
        code: Int,
        retryAfter: String? = null,
    ) = ResponseDefinitionBuilderSupplier {
        aResponse()
            .withStatus(code)
            .withBody("""{"error": {"message": "status $code"}}""")
            .apply { if (retryAfter != null) withHeader("retry-after", retryAfter) }
    }

    @Test
    fun `sends an OpenAI-compatible request and returns the typed review`() {
        answers(ok())

        val result = client().review(request).shouldBeInstanceOf<LlmResult.Success>()

        result.review.comments
            .single()
            .line shouldBe 3
        result.provider shouldBe "test"
        result.model shouldBe "test-model"
        result.inputTokens shouldBe 120
        result.outputTokens shouldBe 40
        server.verify(
            postRequestedFor(urlEqualTo(PATH))
                .withHeader("Authorization", equalTo("Bearer test-key"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("test-model")))
                .withRequestBody(matchingJsonPath("$.temperature", equalTo("0.2")))
                .withRequestBody(matchingJsonPath("$.max_tokens", equalTo("1500")))
                .withRequestBody(matchingJsonPath("$.messages[0].role", equalTo("system")))
                .withRequestBody(matchingJsonPath("$.messages[0].content", equalTo("SYSTEM PROMPT")))
                .withRequestBody(matchingJsonPath("$.messages[1].content", equalTo("USER MESSAGE")))
                .withRequestBody(matchingJsonPath("$.response_format.type", equalTo("json_schema")))
                .withRequestBody(
                    matchingJsonPath(
                        "$.response_format.json_schema.schema.properties.comments.maxItems",
                        equalTo("10"),
                    ),
                ).withRequestBody(notContaining("strict"))
                .withRequestBody(notContaining("reasoning_effort")),
        )
    }

    @Test
    fun `asks for strict mode with the all-required schema when the provider needs it`() {
        answers(ok())

        client(
            config(outputMode = OutputMode.JSON_SCHEMA_STRICT),
        ).review(request).shouldBeInstanceOf<LlmResult.Success>()

        server.verify(
            postRequestedFor(urlEqualTo(PATH))
                .withRequestBody(matchingJsonPath("$.response_format.json_schema.strict", equalTo("true")))
                .withRequestBody(
                    matchingJsonPath(
                        "$.response_format.json_schema.schema.properties.comments.items.required[?(@ == 'suggestion')]",
                    ),
                ),
        )
    }

    @Test
    fun `uses plain JSON mode with the schema in the prompt, and no key, for a local provider`() {
        answers(ok())

        client(
            config(outputMode = OutputMode.JSON_OBJECT, apiKey = null),
        ).review(request).shouldBeInstanceOf<LlmResult.Success>()

        server.verify(
            postRequestedFor(urlEqualTo(PATH))
                .withoutHeader("Authorization")
                .withRequestBody(matchingJsonPath("$.response_format.type", equalTo("json_object")))
                .withRequestBody(matchingJsonPath("$.messages[0].content", containing("OUTPUT SCHEMA"))),
        )
    }

    @Test
    fun `sends the reasoning effort when it is configured`() {
        answers(ok())

        client(config(reasoningEffort = "low")).review(request)

        server.verify(
            postRequestedFor(urlEqualTo(PATH)).withRequestBody(matchingJsonPath("$.reasoning_effort", equalTo("low"))),
        )
    }

    @Test
    fun `waits as asked by retry-after, then succeeds`() {
        answers(status(429, retryAfter = "2"), ok())

        client().review(request).shouldBeInstanceOf<LlmResult.Success>()

        sleeps shouldContainExactly listOf(2.seconds)
        server.verify(2, postRequestedFor(urlEqualTo(PATH)))
    }

    @Test
    fun `reports a rate limit after two attempts`() {
        answers(status(429), status(429))

        client().review(request).shouldBeInstanceOf<LlmResult.Failure>().reason shouldBe LlmFailure.RATE_LIMITED

        server.verify(2, postRequestedFor(urlEqualTo(PATH)))
    }

    @Test
    fun `does not wait for a long retry-after, so that another provider can take over`() {
        answers(status(429, retryAfter = "120"))

        client().review(request).shouldBeInstanceOf<LlmResult.Failure>().reason shouldBe LlmFailure.RATE_LIMITED

        sleeps.shouldBeEmpty()
        server.verify(1, postRequestedFor(urlEqualTo(PATH)))
    }

    @Test
    fun `retries a server error once with a backoff, then reports the provider unavailable`() {
        answers(status(503), status(503))

        client().review(request).shouldBeInstanceOf<LlmResult.Failure>().reason shouldBe LlmFailure.UNAVAILABLE

        sleeps shouldHaveSize 1
        server.verify(2, postRequestedFor(urlEqualTo(PATH)))
    }

    @Test
    fun `reports the provider unavailable when the connection fails`() {
        val port = server.port()
        server.stop()

        val result = client(config(baseUrl = "http://localhost:$port/v1/")).review(request)

        result.shouldBeInstanceOf<LlmResult.Failure>().reason shouldBe LlmFailure.UNAVAILABLE
    }

    @Test
    fun `reports a refused request at once, with the provider's message`() {
        answers(status(401))

        val failure = client().review(request).shouldBeInstanceOf<LlmResult.Failure>()

        failure.reason shouldBe LlmFailure.REJECTED
        failure.detail shouldContain "401"
        failure.detail shouldContain "status 401"
        server.verify(1, postRequestedFor(urlEqualTo(PATH)))
    }

    @Test
    fun `repairs an invalid answer once, with the validator errors`() {
        answers(ok("Sure, here is my review."), ok())

        val result = client().review(request).shouldBeInstanceOf<LlmResult.Success>()

        result.inputTokens shouldBe 240
        result.outputTokens shouldBe 80
        server.verify(
            postRequestedFor(urlEqualTo(PATH))
                .withRequestBody(matchingJsonPath("$.messages[2].role", equalTo("assistant")))
                .withRequestBody(matchingJsonPath("$.messages[3].content", containing("does not match"))),
        )
    }

    @Test
    fun `gives up when the repaired answer is still invalid`() {
        answers(ok("not json"), ok("still not json"))

        val failure = client().review(request).shouldBeInstanceOf<LlmResult.Failure>()

        failure.reason shouldBe LlmFailure.INVALID_OUTPUT
        failure.detail shouldContain "repair"
    }

    @Test
    fun `asks for a shorter answer when the first one was cut off`() {
        answers(
            ResponseDefinitionBuilderSupplier { okJson(completion("""{"summary": "cut""", finishReason = "length")) },
            ok(),
        )

        client().review(request).shouldBeInstanceOf<LlmResult.Success>()

        server.verify(
            postRequestedFor(
                urlEqualTo(PATH),
            ).withRequestBody(matchingJsonPath("$.messages[3].content", containing("cut off"))),
        )
    }

    @Test
    fun `estimates the tokens when the provider does not report them`() {
        answers(ResponseDefinitionBuilderSupplier { okJson(completion(VALID_ANSWER, withUsage = false)) })

        val result = client().review(request).shouldBeInstanceOf<LlmResult.Success>()

        result.inputTokens shouldBeGreaterThan 0
        result.outputTokens shouldBeGreaterThan 0
    }

    @Test
    fun `reports a response that is not a chat completion as unavailable`() {
        answers(ResponseDefinitionBuilderSupplier { aResponse().withStatus(200).withBody("<html>maintenance</html>") })

        client().review(request).shouldBeInstanceOf<LlmResult.Failure>().reason shouldBe LlmFailure.UNAVAILABLE
    }

    /** Builds a fresh WireMock response definition for each stub. */
    fun interface ResponseDefinitionBuilderSupplier {
        operator fun invoke(): ResponseDefinitionBuilder
    }

    private companion object {
        const val PATH = "/v1/chat/completions"
        const val VALID_ANSWER =
            """{"summary": "One crash.", "comments": [{"path": "a.kt", "line": 3, "severity": "major",
            "category": "bug", "title": "Crash", "body": "Null.", "confidence": 0.9}]}"""
    }
}

package io.github.yassineh04.firstpass.aws

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.ssm.SsmClient
import software.amazon.awssdk.services.ssm.model.GetParametersRequest
import software.amazon.awssdk.services.ssm.model.GetParametersResponse
import software.amazon.awssdk.services.ssm.model.Parameter

class SsmSettingsTest {
    /** A Parameter Store holding [values]; any other name is reported invalid, as SSM does. */
    private class FakeSsm(
        private val values: Map<String, String>,
    ) : SsmClient {
        val requests = mutableListOf<GetParametersRequest>()

        override fun serviceName() = "ssm"

        override fun close() = Unit

        override fun getParameters(request: GetParametersRequest): GetParametersResponse {
            requests += request
            val (found, invalid) = request.names().partition { it in values }
            return GetParametersResponse
                .builder()
                .parameters(
                    found.map {
                        Parameter
                            .builder()
                            .name(it)
                            .value(values.getValue(it))
                            .build()
                    },
                ).invalidParameters(invalid)
                .build()
        }
    }

    @Test
    fun `reads the decrypted parameters into the settings they provide`() {
        val ssm =
            FakeSsm(
                mapOf(
                    "/firstpass/github/webhook-secret" to "s3cret-value",
                    "/firstpass/github/client-id" to "Iv1.x",
                ),
            )

        val settings =
            SsmSettings(ssm).load(
                mapOf(
                    "/firstpass/github/webhook-secret" to "GITHUB_WEBHOOK_SECRET",
                    "/firstpass/github/client-id" to "GITHUB_CLIENT_ID",
                ),
            )

        settings shouldBe mapOf("GITHUB_WEBHOOK_SECRET" to "s3cret-value", "GITHUB_CLIENT_ID" to "Iv1.x")
        ssm.requests.single().withDecryption() shouldBe true
    }

    @Test
    fun `asks for 10 names at most per call, the GetParameters maximum`() {
        val names = (1..12).associate { "/firstpass/p$it" to "P$it" }
        val ssm = FakeSsm(names.mapValues { "v" })

        SsmSettings(ssm).load(names).size shouldBe 12

        ssm.requests.map { it.names().size } shouldContainExactly listOf(10, 2)
    }

    @Test
    fun `names the required parameters that are missing, never a value, and accepts missing optional ones`() {
        val ssm = FakeSsm(mapOf("/firstpass/llm/gemini-api-key" to "g-key-value"))
        val parameters =
            mapOf(
                "/firstpass/llm/gemini-api-key" to "GEMINI_API_KEY",
                "/firstpass/llm/groq-api-key" to "GROQ_API_KEY",
                "/firstpass/github/private-key" to "GITHUB_APP_PRIVATE_KEY",
            )

        SsmSettings(
            ssm,
        ).load(parameters - "/firstpass/github/private-key", optional = setOf("/firstpass/llm/groq-api-key")) shouldBe
            mapOf("GEMINI_API_KEY" to "g-key-value")
        val error =
            shouldThrow<MissingParameterException> {
                SsmSettings(ssm).load(parameters, optional = setOf("/firstpass/llm/groq-api-key"))
            }
        error.message shouldContain "/firstpass/github/private-key"
        error.message shouldNotContain "groq"
        error.message shouldNotContain "g-key-value"
    }
}

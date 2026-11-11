package io.github.yassineh04.firstpass.aws

import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.services.ssm.SsmClient

/** Required SSM parameters do not exist; only their names are reported, never a value. */
class MissingParameterException(
    names: Collection<String>,
) : IllegalStateException(
        "SSM parameters not found: ${names.sorted().joinToString()}. Create them as docs/deploy.md explains.",
    )

/**
 * Reads the secrets of FirstPass from SSM Parameter Store (ADR 0013), decrypted, 10 names per
 * call (the GetParameters maximum). Each Lambda reads them once per execution environment, on its
 * first request: never during SnapStart's initialization, so no secret is kept in a snapshot.
 */
class SsmSettings internal constructor(
    private val ssm: SsmClient,
) {
    /**
     * Maps each parameter name of [parameters] to the setting it provides, such as
     * `/firstpass/github/webhook-secret` to `GITHUB_WEBHOOK_SECRET`. Only [optional] parameters
     * may be missing.
     */
    fun load(
        parameters: Map<String, String>,
        optional: Set<String> = emptySet(),
    ): Map<String, String> {
        val settings = mutableMapOf<String, String>()
        val missing = mutableListOf<String>()
        parameters.keys.chunked(MAX_NAMES).forEach { names ->
            val response = ssm.getParameters { it.names(names).withDecryption(true) }
            response.parameters().forEach { settings[parameters.getValue(it.name())] = it.value() }
            missing += response.invalidParameters()
        }
        val required = missing - optional
        if (required.isNotEmpty()) throw MissingParameterException(required)
        return settings
    }

    companion object {
        private const val MAX_NAMES = 10

        /** On Lambda, the region and credentials come from the environment of the function. */
        fun create(): SsmSettings =
            SsmSettings(SsmClient.builder().httpClient(UrlConnectionHttpClient.create()).build())
    }
}

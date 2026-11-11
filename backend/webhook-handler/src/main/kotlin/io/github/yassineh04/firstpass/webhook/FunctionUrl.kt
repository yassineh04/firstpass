package io.github.yassineh04.firstpass.webhook

import tools.jackson.databind.json.JsonMapper
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64

private val JSON: JsonMapper = JsonMapper.builder().build()

/**
 * A request received through a Lambda function URL (payload format 2.0). The body is base64 only
 * when Lambda flags it so; a text body is the delivery's UTF-8 text, so its bytes are the ones
 * GitHub signed. Header names are compared without case, as the documentation does not fix it.
 */
internal class FunctionUrlRequest(
    val method: String,
    val headers: Map<String, String>,
    val body: ByteArray,
) {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    companion object {
        fun read(input: InputStream): FunctionUrlRequest {
            val event = JSON.readTree(input)
            val method =
                event
                    .path("requestContext")
                    .path("http")
                    .path("method")
                    .takeIf { it.isString }
                    ?.asString()
                    .orEmpty()
            val headers =
                event
                    .path("headers")
                    .properties()
                    .filter { it.value.isString }
                    .associate { it.key to it.value.asString() }
            val text =
                event
                    .path("body")
                    .takeIf { it.isString }
                    ?.asString()
                    .orEmpty()
            val body =
                if (event.path("isBase64Encoded").booleanValue()) {
                    Base64.getDecoder().decode(text)
                } else {
                    text.toByteArray(Charsets.UTF_8)
                }
            return FunctionUrlRequest(method, headers, body)
        }
    }
}

/** The answer of the function, in the response format of function URLs. */
internal object FunctionUrlResponse {
    fun write(
        output: OutputStream,
        status: Int,
        text: String,
    ) {
        val response =
            JSON
                .createObjectNode()
                .put("statusCode", status)
                .put("body", text)
                .put("isBase64Encoded", false)
        response.putObject("headers").put("content-type", "text/plain; charset=utf-8")
        output.write(JSON.writeValueAsBytes(response))
    }
}

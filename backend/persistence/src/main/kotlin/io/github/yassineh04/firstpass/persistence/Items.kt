package io.github.yassineh04.firstpass.persistence

import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** One DynamoDB item, as the low-level client reads and writes it. */
internal typealias Item = Map<String, AttributeValue>

/** The attribute names every entity shares (design 8). */
internal object Attr {
    const val PK = "pk"
    const val SK = "sk"
    const val GSI1_PK = "gsi1pk"
    const val GSI1_SK = "gsi1sk"

    /** Epoch seconds, the format DynamoDB's TTL expects; items without it never expire. */
    const val EXPIRES_AT = "expiresAt"
}

/** Builds attribute values. */
internal object Values {
    fun s(value: String): AttributeValue = AttributeValue.fromS(value)

    fun n(value: Number): AttributeValue = AttributeValue.fromN(value.toString())

    fun bool(value: Boolean): AttributeValue = AttributeValue.fromBool(value)

    /** A list keeps the order and, unlike a string set, may be empty. */
    fun list(values: Collection<String>): AttributeValue = AttributeValue.fromL(values.map(::s))

    /** A string set, for values without order; DynamoDB refuses an empty one, so the caller leaves it out. */
    fun set(values: Collection<String>): AttributeValue = AttributeValue.fromSs(values.toList())

    fun time(instant: Instant): AttributeValue = s(instant.toString())

    fun expiry(instant: Instant): AttributeValue = n(instant.epochSecond)
}

/** The partition and sort keys of every entity (design 8). */
internal object Keys {
    const val META = "META"
    const val REPO_PREFIX = "REPO#"
    const val PR_PREFIX = "PR#"
    const val RUN_PREFIX = "RUN#"
    const val FINDING_PREFIX = "FINDING#"

    /** Sort-key times have a fixed width, so that the order of the text is the order of time. */
    private val SORTABLE = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    fun of(
        pk: String,
        sk: String,
    ): Item = mapOf(Attr.PK to Values.s(pk), Attr.SK to Values.s(sk))

    fun installation(installationId: Long) = "INST#$installationId"

    fun repository(repoId: Long) = "$REPO_PREFIX$repoId"

    fun pullRequest(number: Int) = "$PR_PREFIX$number"

    fun run(runId: String) = "$RUN_PREFIX$runId"

    fun sortable(instant: Instant): String = SORTABLE.format(instant)
}

/** An attribute FirstPass wrote itself is missing or of the wrong type. */
internal class CorruptItemException(
    attribute: String,
) : IllegalStateException("Missing or invalid attribute: $attribute")

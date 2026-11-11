package io.github.yassineh04.firstpass.persistence

import java.time.Instant

/** The reference time of the tests. */
internal val T0: Instant = Instant.parse("2026-11-09T10:00:00Z")

/** The raw items of a partition, as DynamoDB stores them. */
internal fun FirstPassTable.items(
    pk: String,
    skPrefix: String = "",
): List<Item> = query(pk, skPrefix)

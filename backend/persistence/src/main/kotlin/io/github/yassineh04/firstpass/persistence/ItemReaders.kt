package io.github.yassineh04.firstpass.persistence

import java.time.Instant

internal fun Item.string(name: String): String = this[name]?.s() ?: throw CorruptItemException(name)

internal fun Item.stringOrNull(name: String): String? = this[name]?.s()

internal fun Item.long(name: String): Long = this[name]?.n()?.toLong() ?: throw CorruptItemException(name)

internal fun Item.int(name: String): Int = long(name).toInt()

/** Counters start at zero: a missing one was never incremented. */
internal fun Item.counter(name: String): Int = this[name]?.n()?.toInt() ?: 0

internal fun Item.flag(name: String): Boolean = this[name]?.bool() ?: false

internal fun Item.instantOrNull(name: String): Instant? = stringOrNull(name)?.let(Instant::parse)

/** A string set left out because it was empty reads as an empty set. */
internal fun Item.stringSet(name: String): Set<String> =
    this[name]
        ?.takeIf { it.hasSs() }
        ?.ss()
        ?.toSet()
        .orEmpty()

internal fun Item.stringList(name: String): List<String>? = this[name]?.takeIf { it.hasL() }?.l()?.map { it.s() }

/** TTL deletes items days late (design 8): an item past its expiry counts as gone. */
internal fun Item.isExpired(now: Instant): Boolean =
    this[Attr.EXPIRES_AT]?.n()?.toLong()?.let { it < now.epochSecond } ?: false

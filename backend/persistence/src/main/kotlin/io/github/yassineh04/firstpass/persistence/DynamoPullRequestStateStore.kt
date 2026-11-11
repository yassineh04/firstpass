package io.github.yassineh04.firstpass.persistence

import io.github.yassineh04.firstpass.core.store.PullRequestState
import io.github.yassineh04.firstpass.core.store.PullRequestStateStore
import java.time.Clock
import java.time.Duration
import java.time.LocalDate

/** The state of each pull request (`REPO#{repoId}`, `PR#{number}`), design 8, access pattern 4. */
class DynamoPullRequestStateStore(
    private val table: FirstPassTable,
    private val clock: Clock,
) : PullRequestStateStore {
    override fun state(
        repoId: Long,
        number: Int,
    ): PullRequestState? =
        table
            .get(key(repoId, number))
            ?.takeUnless { it.isExpired(clock.instant()) }
            ?.let {
                PullRequestState(
                    repoId = repoId,
                    number = number,
                    lastReviewedSha = it.stringOrNull("lastReviewedSha"),
                    paused = it.flag("paused"),
                    fingerprints = it.stringSet("fingerprints"),
                    lastQuotaNoticeDay = it.stringOrNull("lastQuotaNoticeDay")?.let(LocalDate::parse),
                    tooLargeNoticePosted = it.flag("tooLargeNoticePosted"),
                    closedAt = it.instantOrNull("closedAt"),
                )
            }

    /** A closed pull request expires 90 days after its closing; an open one never does. */
    override fun save(state: PullRequestState) =
        table.put(
            buildMap {
                putAll(key(state.repoId, state.number))
                put("paused", Values.bool(state.paused))
                put("tooLargeNoticePosted", Values.bool(state.tooLargeNoticePosted))
                state.lastReviewedSha?.let { put("lastReviewedSha", Values.s(it)) }
                if (state.fingerprints.isNotEmpty()) put("fingerprints", Values.set(state.fingerprints))
                state.lastQuotaNoticeDay?.let { put("lastQuotaNoticeDay", Values.s(it.toString())) }
                state.closedAt?.let {
                    put("closedAt", Values.time(it))
                    put(Attr.EXPIRES_AT, Values.expiry(it.plus(KEPT_AFTER_CLOSING)))
                }
            },
        )

    private fun key(
        repoId: Long,
        number: Int,
    ) = Keys.of(Keys.repository(repoId), Keys.pullRequest(number))

    private companion object {
        val KEPT_AFTER_CLOSING: Duration = Duration.ofDays(90)
    }
}

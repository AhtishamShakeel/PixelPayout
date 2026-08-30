package com.example.pixelpayout.data.repository

/**
 * Today's goals, derived on the device instead of asked for.
 *
 * This is a deliberate port of functions/src/economy/dailyGoals.ts. Goal
 * SELECTION is a pure function of (uid, UTC day) and goal PROGRESS is a pure
 * function of the `dailyStats` map already on the user document - which the
 * snapshot listener delivers on every change, for free. So the callable that
 * used to answer both questions was a Firestore read per return to Home,
 * asking the server to recompute something the client could already see.
 *
 * WHAT IS NOT PORTED, and must never be: whether the bonus may be PAID.
 * claimDailyGoalBonus re-derives every one of these figures server-side from
 * the same counters, inside the transaction that pays. This file decides what
 * to draw; it has no say in what anything is worth.
 *
 * THE TWO HALVES HAVE TO AGREE. A client that thinks the target is eight
 * games while the server requires nine shows a claim button that fails with
 * no explanation. Two things keep them in step:
 *
 *   * The pool and the kind order are PUBLISHED by the server (see
 *     publishLevelCurve) rather than written down again here. There is one
 *     array, and it lives in TypeScript.
 *   * [hashForSelection] reproduces the server's FNV-1a exactly, including
 *     its 32-bit overflow behaviour. See the note on that function - it is
 *     the one piece of real translation risk in the file.
 */
object DailyGoalEngine {

    /** One entry of the published pool. */
    data class GoalTemplate(val id: String, val kind: String, val target: Int)

    /**
     * The pool and kind order, as published on config/levelCurve.
     *
     * Empty until that document arrives, which is the state the caller has to
     * cope with - no goals rather than guessed ones.
     */
    data class GoalPool(
        val templates: List<GoalTemplate> = emptyList(),
        val kinds: List<String> = emptyList()
    ) {
        val isEmpty: Boolean get() = templates.isEmpty() || kinds.isEmpty()
    }

    /**
     * FNV-1a, matching the server's `hash()` byte for byte.
     *
     * Three details carry the whole port, and all three are easy to get
     * silently wrong:
     *
     *   * The offset basis 0x811c9dc5 does not fit a signed Int. Kotlin needs
     *     the explicit `.toInt()` to reinterpret it as the same bit pattern
     *     JavaScript holds.
     *   * The server multiplies with Math.imul, which is defined as a 32-bit
     *     multiply that discards overflow. Kotlin's `Int * Int` wraps
     *     identically - this is the one place overflow is wanted, not a bug.
     *   * JavaScript's `charCodeAt` returns a UTF-16 code unit, and Kotlin's
     *     `Char.code` is the same thing. Iterating chars rather than encoding
     *     to UTF-8 first is what keeps them equal.
     *
     * Returned as an unsigned Long so the caller's `%` can never land on a
     * negative index the way it would with a signed Int.
     */
    private fun hashForSelection(input: String): Long {
        var h = 0x811c9dc5.toInt()
        for (ch in input) {
            h = h xor ch.code
            h *= 0x01000193
        }
        return h.toLong() and 0xFFFFFFFFL
    }

    /**
     * Today's three goals for one user - one of each kind, in the published
     * order.
     *
     * The kind's INDEX is part of the hash input, so the order of [kinds] is
     * load-bearing: reordering it server-side hands every user a different
     * set. That is why the order is published rather than assumed.
     */
    fun selectGoals(pool: GoalPool, uid: String, dayUtc: Long): List<GoalTemplate> {
        if (pool.isEmpty || uid.isEmpty()) return emptyList()

        return pool.kinds.mapIndexedNotNull { index, kind ->
            val options = pool.templates.filter { it.kind == kind }
            if (options.isEmpty()) return@mapIndexedNotNull null
            options[(hashForSelection("$uid:$dayUtc:$index") % options.size).toInt()]
        }
    }

    /** How far along one goal is, capped at its target. */
    fun progressFor(goal: GoalTemplate, stats: UserRepository.DailyStats): Int {
        val raw = when (goal.kind) {
            KIND_PLAY_GAMES -> stats.games
            KIND_COMPLETE_QUIZZES -> stats.quizzes
            else -> stats.correct
        }
        return raw.coerceIn(0, goal.target)
    }

    fun isDone(goal: GoalTemplate, stats: UserRepository.DailyStats): Boolean =
        progressFor(goal, stats) >= goal.target

    private const val KIND_PLAY_GAMES = "PLAY_GAMES"
    private const val KIND_COMPLETE_QUIZZES = "COMPLETE_QUIZZES"
}

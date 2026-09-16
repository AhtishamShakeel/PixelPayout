package com.createbyte.lootlevel.utils

import android.content.Context
import android.content.SharedPreferences
import com.google.firebase.auth.FirebaseAuth

/**
 * Where a player is in the Play tab's first-run tutorial.
 *
 * WHAT IS STORED HERE IS PROGRESS, NOT ENTITLEMENT. Whether the tutorial is
 * owed at all is the server's `playTutorialCompleted` flag, and the level-2
 * top-up at the end re-checks the ledger for the game run and quiz answers
 * before paying anything (completePlayTutorial). This only remembers which
 * step to draw, so leaving for a game or a quiz - separate activities - picks
 * the walkthrough up where it left off.
 *
 * Keyed by uid, so a second account signed in on the same phone starts its
 * own walkthrough rather than inheriting somebody else's step.
 *
 * The game and quiz screens report into it (noteGamePlayed, noteQuizAnswered)
 * and read [isActive] to keep the walkthrough clean: no double-XP offer, no
 * interstitial, no level-up popup stacked over its last card.
 */
object PlayTutorial {

    enum class Step { WELCOME, PICK_GAME, OPEN_QUIZZES, PICK_QUIZ, FINISHING, LEVEL_UP }

    /** Mirrors PLAY_TUTORIAL_QUIZZES_REQUIRED in functions/src/economy/playTutorial.ts. */
    const val QUIZZES_REQUIRED = 2

    /**
     * How long after the last card ads stay away. The player lands on Level
     * rewards straight from it, and an interstitial on the way back out would
     * be the first ad they ever see - right at the end of their onboarding.
     */
    private const val AD_QUIET_AFTER_MS = 10L * 60 * 1000

    private const val PREFS = "play_tutorial"
    private const val KEY_ACTIVE = "active"
    private const val KEY_STEP = "step"
    private const val KEY_QUIZZES = "quizzes"
    private const val KEY_LEVEL = "level"
    private const val KEY_STARS = "stars"
    private const val KEY_FINISHED_AT = "finishedAt"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(name: String): String =
        "$name:${FirebaseAuth.getInstance().currentUser?.uid.orEmpty()}"

    fun isActive(context: Context): Boolean =
        prefs(context).getBoolean(key(KEY_ACTIVE), false)

    /** Called once the server has said the tutorial is owed. */
    fun activate(context: Context) {
        if (!isActive(context)) prefs(context).edit().putBoolean(key(KEY_ACTIVE), true).apply()
    }

    fun step(context: Context): Step =
        Step.values().getOrNull(prefs(context).getInt(key(KEY_STEP), 0)) ?: Step.WELCOME

    fun setStep(context: Context, step: Step) {
        prefs(context).edit().putInt(key(KEY_STEP), step.ordinal).apply()
    }

    fun quizzesAnswered(context: Context): Int = prefs(context).getInt(key(KEY_QUIZZES), 0)

    /** A game run was paid. Moves the walkthrough on to the Quizzes tab. */
    fun noteGamePlayed(context: Context) {
        if (!isActive(context)) return
        if (step(context) == Step.PICK_GAME) setStep(context, Step.OPEN_QUIZZES)
    }

    /**
     * A quiz answer was graded - right or wrong, since the server counts both.
     * The second one finishes the walkthrough.
     */
    fun noteQuizAnswered(context: Context) {
        if (!isActive(context)) return
        val step = step(context)
        if (step != Step.OPEN_QUIZZES && step != Step.PICK_QUIZ) return

        val answered = quizzesAnswered(context) + 1
        prefs(context).edit()
            .putInt(key(KEY_QUIZZES), answered)
            .putInt(
                key(KEY_STEP),
                (if (answered >= QUIZZES_REQUIRED) Step.FINISHING else Step.PICK_QUIZ).ordinal
            )
            .apply()
    }

    /** The server did not see the run and answers; walk through them again. */
    fun restartPlaying(context: Context) {
        prefs(context).edit()
            .putInt(key(KEY_QUIZZES), 0)
            .putInt(key(KEY_STEP), Step.PICK_GAME.ordinal)
            .apply()
    }

    /** The server finished it; the last card shows what it came to. */
    fun saveLevelUp(context: Context, level: Int, stars: Int) {
        prefs(context).edit()
            .putInt(key(KEY_LEVEL), level)
            .putInt(key(KEY_STARS), stars)
            .putInt(key(KEY_STEP), Step.LEVEL_UP.ordinal)
            .apply()
    }

    fun levelReached(context: Context): Int = prefs(context).getInt(key(KEY_LEVEL), 2)

    fun starsWaiting(context: Context): Int = prefs(context).getInt(key(KEY_STARS), 0)

    /** The player left the last card. */
    fun finish(context: Context) {
        clear(context)
        prefs(context).edit().putLong(key(KEY_FINISHED_AT), System.currentTimeMillis()).apply()
    }

    /** Forgets progress without the quiet period - the server says it is already done. */
    fun clear(context: Context) {
        prefs(context).edit()
            .remove(key(KEY_ACTIVE))
            .remove(key(KEY_STEP))
            .remove(key(KEY_QUIZZES))
            .remove(key(KEY_LEVEL))
            .remove(key(KEY_STARS))
            .apply()
    }

    /** True while the walkthrough runs, and for a short while after it ends. */
    fun keepsAdsAway(context: Context): Boolean {
        if (isActive(context)) return true
        val finishedAt = prefs(context).getLong(key(KEY_FINISHED_AT), 0L)
        val since = System.currentTimeMillis() - finishedAt
        return finishedAt != 0L && since in 0 until AD_QUIET_AFTER_MS
    }
}

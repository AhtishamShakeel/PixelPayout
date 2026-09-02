package com.example.pixelpayout.ui.game

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.pixelpayout.data.model.LevelUpEvent
import com.example.pixelpayout.data.repository.UserRepository
import com.google.firebase.functions.FirebaseFunctionsException
import com.pixelpayout.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class GamePlayViewModel : ViewModel() {
    private companion object {
        /** How long a finished run waits for an in-flight session. */
        const val SESSION_WAIT_MILLIS = 10_000L
    }

    private val userRepository = UserRepository()

    /**
     * How a finished run ended.
     *
     * This used to be a bare Boolean, and the activity answered `false` by
     * showing its loading spinner and never hiding it - so every refusal, and
     * every dropped connection, read as a hang. A refused claim has a reason
     * and the player is entitled to it: the run is over either way, and the
     * session is spent, so silence is the one response that helps nobody.
     */
    sealed class ClaimOutcome {
        /** [xpAwarded] is what the server actually paid, buffs included. */
        data class Paid(val xpAwarded: Int) : ClaimOutcome()
        /** [reason] is a string resource the activity shows. */
        data class Refused(val reason: Int) : ClaimOutcome()
    }

    /** How the "double it" offer ended. */
    sealed class DoubleOutcome {
        data class Paid(val xpAwarded: Int) : DoubleOutcome()

        /**
         * The double had already landed - a previous call got through and its
         * response was lost. The XP is banked, so this is a success with no
         * number to show, not a failure.
         */
        data object AlreadyPaid : DoubleOutcome()

        data object Failed : DoubleOutcome()
    }

    private val _claimOutcome = MutableLiveData<ClaimOutcome>()
    val claimOutcome: LiveData<ClaimOutcome> = _claimOutcome

    private val _doubleOutcome = MutableLiveData<DoubleOutcome>()
    val doubleOutcome: LiveData<DoubleOutcome> = _doubleOutcome

    private val _levelUp = MutableLiveData<LevelUpEvent?>()
    val levelUp: LiveData<LevelUpEvent?> = _levelUp

    /**
     * Marks the level-up as announced.
     *
     * LiveData re-delivers its last value to a new observer, so without this
     * a rotation - which recreates the activity and re-subscribes - would put
     * the level-up dialog back on screen, offering an ad for a reward that may
     * already have been claimed. Harmless when it was a toast; not harmless
     * now that it is a modal offer.
     */
    fun clearLevelUp() {
        _levelUp.value = null
    }


    private val _sessionReady = MutableLiveData<Boolean>()
    val sessionReady: LiveData<Boolean> = _sessionReady

    /**
     * True while a finished run is being paid for.
     *
     * The claim is a network call, and on a cold-started function it can take
     * a few seconds. Without something on screen for that stretch the game
     * simply sits on its last frame, which reads as a crash rather than as
     * work in progress.
     */
    private val _claiming = MutableLiveData(false)
    val claiming: LiveData<Boolean> = _claiming

    private var sessionId: String? = null

    /**
     * The in-flight [startSession], so a claim can wait for it.
     *
     * A cold-started startGameSession can take a couple of seconds, and a
     * short run can be over before it answers. Waiting on this is what stops
     * that run being refused for want of a session that was on its way.
     */
    private var sessionJob: Job? = null

    /** A session pays once, however many times the game says it finished. */
    private var claimInFlight = false

    /**
     * The ledger entry a paid run can still be doubled against.
     *
     * Not [sessionId], which is cleared at claim time so a second completion
     * cannot reuse it. These are two different lifetimes: the play session is
     * spent the moment it pays, while the right to double what it paid lasts
     * until the player leaves the results screen. Cleared the instant the
     * double is requested, so a double-tap cannot send two calls.
     */
    private var doubleableEventId: String? = null

    /** Whether there is still a paid run to offer a double on. */
    fun canDouble(): Boolean = doubleableEventId != null

    /**
     * Opens a server-side session before play starts. A reward claim is only
     * accepted with a valid, unconsumed session, which is what ties a score to
     * a real play session rather than a bare claim.
     */
    fun startSession(gameId: String) {
        sessionJob = viewModelScope.launch {
            try {
                sessionId = userRepository.startGameSession(gameId)
                _sessionReady.value = true
            } catch (e: Exception) {
                sessionId = null
                _sessionReady.value = false
            }
        }
    }

    /**
     * Pays for a finished run.
     *
     * EVERY LINE OF THIS RUNS INSIDE THE COROUTINE, and that is the whole
     * point of the shape. This is called from GameJavaScriptInterface, whose
     * @JavascriptInterface methods the WebView invokes on its own JavaBridge
     * thread - never the main thread. The early `sessionId == null` return
     * used to touch LiveData directly, before any coroutine, so it called
     * setValue off the main thread and threw IllegalStateException. That
     * exception died inside the JS bridge, so the results panel never
     * appeared and no error was shown either: the game sat on its last frame
     * forever, still animating, which is exactly what a hang looks like.
     * viewModelScope is Dispatchers.Main.immediate, so launching first hops to
     * the main thread and everything below is safe.
     */
    fun claimGameReward(gameId: String, score: Int) {
        viewModelScope.launch {
            // Games announce game-over more than once often enough to matter,
            // and a session pays exactly once.
            if (claimInFlight) return@launch
            claimInFlight = true
            _claiming.value = true

            try {
                // The run can be over before a cold-started startSession has
                // answered. Waiting is the difference between paying for that
                // run and refusing it for want of a session already on its
                // way; join() returns immediately once it has landed, which is
                // every case after the first.
                //
                // BOUNDED, because startGameSession sets no timeout of its own
                // and the callable SDK's default is a full minute. A cold
                // start measures 1.4-2.9s, so this is generous for the case it
                // exists to cover while refusing to hold a spinner on screen
                // for anything like as long as the SDK would.
                withTimeoutOrNull(SESSION_WAIT_MILLIS) { sessionJob?.join() }

                val currentSession = sessionId
                if (currentSession == null) {
                    // startSession genuinely failed, or this run was already
                    // claimed. Either way there is nothing left to pay.
                    _claimOutcome.value =
                        ClaimOutcome.Refused(R.string.game_claim_no_session)
                    return@launch
                }

                try {
                    val result = userRepository.claimGameReward(gameId, score, currentSession)
                    // A session is single-use: drop it so a second completion
                    // in the same activity can't attempt to reuse it.
                    sessionId = null
                    // Only a run that paid something can be doubled - the
                    // server refuses a zero anyway, and offering an ad in
                    // exchange for twice nothing is worse than not offering
                    // one.
                    doubleableEventId =
                        if (result.xpAwarded > 0 && result.eventId.isNotEmpty()) result.eventId else null
                    _claimOutcome.value = ClaimOutcome.Paid(result.xpAwarded)
                    // AFTER the results, deliberately. The level-up is a
                    // dialog now rather than a toast, so it lands on top of
                    // the results panel instead of over a screen that has not
                    // drawn it yet.
                    if (result.leveledUp) {
                        _levelUp.value = LevelUpEvent(result.level, result.milestonePoints)
                    }
                } catch (e: Exception) {
                    // The session is burned by the server on a rejection, so
                    // it is dropped here too - retrying it could only fail
                    // again.
                    sessionId = null
                    doubleableEventId = null
                    _claimOutcome.value = ClaimOutcome.Refused(reasonFor(e))
                }
            } finally {
                _claiming.value = false
            }
        }
    }

    /**
     * Claims the doubled XP, the rewarded ad having been watched.
     *
     * Called from the ad's REWARD callback rather than on dismissal, the same
     * way the bonus-attempt purchase is: both fire on a normal completion but
     * the reward comes first, which shrinks the window in which a killed
     * process loses an ad the player actually sat through.
     */
    fun claimDoubleXp() {
        val eventId = doubleableEventId ?: return
        doubleableEventId = null

        viewModelScope.launch {
            when (val result = userRepository.claimDoubleXp(eventId)) {
                is UserRepository.DoubleXpResult.Paid -> {
                    if (result.leveledUp) {
                        _levelUp.value = LevelUpEvent(result.level, result.milestonePoints)
                    }
                    _doubleOutcome.value = DoubleOutcome.Paid(result.xpAwarded)
                }
                is UserRepository.DoubleXpResult.AlreadyDoubled ->
                    _doubleOutcome.value = DoubleOutcome.AlreadyPaid
                is UserRepository.DoubleXpResult.Error ->
                    _doubleOutcome.value = DoubleOutcome.Failed
            }
        }
    }

    /**
     * Turns a claim failure into something worth reading.
     *
     * The server distinguishes "what you sent is wrong" from "your account is
     * not in a state to do this", and the two need different words: one means
     * the run cannot be paid, the other that today's allowance is spent. Any
     * other failure is treated as a network problem, which is what it almost
     * always is.
     */
    private fun reasonFor(error: Exception): Int {
        val functionsError = error as? FirebaseFunctionsException
            ?: return R.string.game_claim_offline

        return when (functionsError.code) {
            FirebaseFunctionsException.Code.INVALID_ARGUMENT ->
                R.string.game_claim_rejected
            FirebaseFunctionsException.Code.FAILED_PRECONDITION ->
                R.string.game_claim_limit
            FirebaseFunctionsException.Code.NOT_FOUND ->
                R.string.game_claim_no_session
            FirebaseFunctionsException.Code.UNAUTHENTICATED ->
                R.string.game_claim_signed_out
            else -> R.string.game_claim_offline
        }
    }
}

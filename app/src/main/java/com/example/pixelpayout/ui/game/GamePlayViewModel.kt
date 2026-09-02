package com.example.pixelpayout.ui.game

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.pixelpayout.data.model.LevelUpEvent
import com.example.pixelpayout.data.repository.UserRepository
import com.google.firebase.functions.FirebaseFunctionsException
import com.pixelpayout.R
import kotlinx.coroutines.launch

class GamePlayViewModel : ViewModel() {
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

    private var sessionId: String? = null

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
        viewModelScope.launch {
            try {
                sessionId = userRepository.startGameSession(gameId)
                _sessionReady.value = true
            } catch (e: Exception) {
                sessionId = null
                _sessionReady.value = false
            }
        }
    }

    fun claimGameReward(gameId: String, score: Int) {
        val currentSession = sessionId
        if (currentSession == null) {
            // No session means startSession failed or this run was already
            // claimed. Either way there is nothing left to pay.
            _claimOutcome.value = ClaimOutcome.Refused(R.string.game_claim_no_session)
            return
        }

        viewModelScope.launch {
            try {
                val result = userRepository.claimGameReward(gameId, score, currentSession)
                // A session is single-use: drop it so a second completion in
                // the same activity can't attempt to reuse it.
                sessionId = null
                // Only a run that paid something can be doubled - the server
                // refuses a zero anyway, and offering an ad in exchange for
                // twice nothing is worse than not offering one.
                doubleableEventId =
                    if (result.xpAwarded > 0 && result.eventId.isNotEmpty()) result.eventId else null
                _claimOutcome.value = ClaimOutcome.Paid(result.xpAwarded)
                // AFTER the results, deliberately. The level-up is a dialog
                // now rather than a toast, so it lands on top of the results
                // panel instead of over a screen that has not drawn it yet.
                if (result.leveledUp) {
                    _levelUp.value = LevelUpEvent(result.level, result.milestonePoints)
                }
            } catch (e: Exception) {
                // The session is burned by the server on a rejection, so it is
                // dropped here too - retrying it could only fail again.
                sessionId = null
                doubleableEventId = null
                _claimOutcome.value = ClaimOutcome.Refused(reasonFor(e))
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

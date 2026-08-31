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
        object Paid : ClaimOutcome()
        /** [reason] is a string resource the activity shows. */
        data class Refused(val reason: Int) : ClaimOutcome()
    }

    private val _claimOutcome = MutableLiveData<ClaimOutcome>()
    val claimOutcome: LiveData<ClaimOutcome> = _claimOutcome

    private val _levelUp = MutableLiveData<LevelUpEvent?>()
    val levelUp: LiveData<LevelUpEvent?> = _levelUp

    private val _sessionReady = MutableLiveData<Boolean>()
    val sessionReady: LiveData<Boolean> = _sessionReady

    private var sessionId: String? = null

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
                if (result.leveledUp) {
                    _levelUp.value = LevelUpEvent(result.level, result.milestonePoints)
                }
                _claimOutcome.value = ClaimOutcome.Paid
            } catch (e: Exception) {
                // The session is burned by the server on a rejection, so it is
                // dropped here too - retrying it could only fail again.
                sessionId = null
                _claimOutcome.value = ClaimOutcome.Refused(reasonFor(e))
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

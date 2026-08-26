package com.example.pixelpayout.ui.game

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.pixelpayout.data.model.LevelUpEvent
import com.example.pixelpayout.data.repository.UserRepository
import kotlinx.coroutines.launch

class GamePlayViewModel : ViewModel() {
    private val userRepository = UserRepository()

    private val _pointsUpdated = MutableLiveData<Boolean>()
    val pointsUpdated: LiveData<Boolean> = _pointsUpdated

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
            _pointsUpdated.value = false
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
                _pointsUpdated.value = true
            } catch (e: Exception) {
                _pointsUpdated.value = false
            }
        }
    }
}

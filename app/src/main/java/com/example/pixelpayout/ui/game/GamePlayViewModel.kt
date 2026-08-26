package com.example.pixelpayout.ui.game

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.pixelpayout.data.repository.UserRepository
import kotlinx.coroutines.launch

class GamePlayViewModel : ViewModel() {
    private val userRepository = UserRepository()

    private val _pointsUpdated = MutableLiveData<Boolean>()
    val pointsUpdated: LiveData<Boolean> = _pointsUpdated

    fun claimGameReward(gameId: String, score: Int) {
        viewModelScope.launch {
            try {
                userRepository.claimGameReward(gameId, score)
                _pointsUpdated.value = true
            } catch (e: Exception) {
                _pointsUpdated.value = false
            }
        }
    }
}

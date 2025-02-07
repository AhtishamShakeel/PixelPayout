package com.pixelpayout.ui.game

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pixelpayout.data.repository.UserRepository
import kotlinx.coroutines.launch

class GamePlayViewModel : ViewModel() {
    private val userRepository = UserRepository()

    private val _pointsUpdated = MutableLiveData<Boolean>()
    val pointsUpdated: LiveData<Boolean> = _pointsUpdated

    fun updateGamePoints(points: Int) {
        viewModelScope.launch {
            try {
                userRepository.updateUserPoints(points) {
                    _pointsUpdated.value = true
                }
            } catch (e: Exception) {
                _pointsUpdated.value = false
            }
        }
    }
}
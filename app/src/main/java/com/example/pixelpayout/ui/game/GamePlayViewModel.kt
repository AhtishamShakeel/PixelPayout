package com.pixelpayout.ui.game

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pixelpayout.data.repository.UserRepository
import kotlinx.coroutines.launch

class GamePlayViewModel : ViewModel() {
    private val userRepository = UserRepository.getInstance()

    private val _pointsUpdated = MutableLiveData<Boolean>()
    val pointsUpdated: LiveData<Boolean> = _pointsUpdated

    fun updateGamePoints(points: Int) {
        Log.d("GamePoints", "Game completed with points: $points")
        viewModelScope.launch {
            try {
                userRepository.updateUserPoints(points) {
                    Log.d("GamePoints", "Points successfully updated in Firebase")
                    _pointsUpdated.value = true
                }
            } catch (e: Exception) {
                Log.e("GamePoints", "Failed to update points", e)
                _pointsUpdated.value = false
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        userRepository.cleanup()
    }
}
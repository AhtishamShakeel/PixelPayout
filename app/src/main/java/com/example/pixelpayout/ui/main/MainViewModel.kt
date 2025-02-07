package com.pixelpayout.ui.main

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pixelpayout.data.repository.UserRepository
import kotlinx.coroutines.launch

class MainViewModel : ViewModel() {
    private val userRepository = UserRepository()

    private val _points = MutableLiveData<Int>()
    val points: LiveData<Int> = _points

    init {
        loadPoints()
    }

    fun loadPoints() {
        viewModelScope.launch {
            try {
                val currentPoints = userRepository.getUserPoints()
                _points.value = currentPoints
            } catch (e: Exception) {
                // Handle error
            }
        }
    }
}
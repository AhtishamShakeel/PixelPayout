package com.pixelpayout.ui.main

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.map
import com.pixelpayout.data.repository.UserRepository

class MainViewModel(private val userRepository: UserRepository) : ViewModel() {
    val points: LiveData<Int> = userRepository.userData.map { userData ->
        Log.d("UIUpdate", "MainViewModel received points update: ${userData.points}")
        userData.points
    }
}
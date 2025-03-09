package com.pixelpayout.ui.main

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.map  // Add this import
import com.pixelpayout.data.repository.UserRepository

class MainViewModel(userRepository: UserRepository) : ViewModel() {
    val points: LiveData<Int> = userRepository.userData.map { userData ->
        userData.points
    }
}
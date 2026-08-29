package com.example.pixelpayout.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.pixelpayout.data.repository.UserRepository
import com.example.pixelpayout.utils.UserPreferences

class MainViewModelFactory(
    private val userRepository: UserRepository,
    private val userPreferences: UserPreferences
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return MainViewModel(userRepository, userPreferences) as T
    }
}

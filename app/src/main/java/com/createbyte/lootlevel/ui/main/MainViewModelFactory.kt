package com.createbyte.lootlevel.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.createbyte.lootlevel.data.repository.UserRepository
import com.createbyte.lootlevel.utils.UserPreferences

class MainViewModelFactory(
    private val userRepository: UserRepository,
    private val userPreferences: UserPreferences
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return MainViewModel(userRepository, userPreferences) as T
    }
}

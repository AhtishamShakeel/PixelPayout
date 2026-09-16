package com.createbyte.lootlevel.ui.redemption

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.createbyte.lootlevel.data.repository.UserRepository

class ReferralViewModelFactory(private val userRepository: UserRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ReferralViewModel::class.java)) {
            return ReferralViewModel(userRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

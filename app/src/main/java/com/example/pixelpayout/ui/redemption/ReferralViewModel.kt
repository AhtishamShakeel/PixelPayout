package com.pixelpayout.ui.redemption

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pixelpayout.data.repository.UserRepository
import kotlinx.coroutines.launch

class ReferralViewModel(private val userRepository: UserRepository) : ViewModel() {
    private val _referralResult = MutableLiveData<ReferralResult>()
    val referralResult: LiveData<ReferralResult> = _referralResult

    fun submitReferral(referralCode: String) {
        viewModelScope.launch {
            try {
                val result = userRepository.submitReferral(referralCode)
                _referralResult.value = result
            } catch (e: Exception) {
                _referralResult.value = ReferralResult.Error(e.message ?: "Unknown error occurred")
            }
        }
    }
}

sealed class ReferralResult {
    object Success : ReferralResult()
    data class Error(val message: String) : ReferralResult()
    object InvalidCode : ReferralResult()
    object AlreadyUsed : ReferralResult()
}
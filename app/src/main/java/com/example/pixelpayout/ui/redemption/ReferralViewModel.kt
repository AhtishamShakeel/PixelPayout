package com.example.pixelpayout.ui.redemption

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.pixelpayout.data.model.RedemptionOption
import com.example.pixelpayout.data.repository.UserRepository
import kotlinx.coroutines.launch

class ReferralViewModel(private val userRepository: UserRepository) : ViewModel() {
    private val _referralResult = MutableLiveData<ReferralResult>()
    val referralResult: LiveData<ReferralResult> = _referralResult

    private val _options = MutableLiveData<List<RedemptionOption>>(emptyList())
    val options: LiveData<List<RedemptionOption>> = _options

    private val _redemptionResult = MutableLiveData<RedemptionResult?>()
    val redemptionResult: LiveData<RedemptionResult?> = _redemptionResult

    private val _isRedeeming = MutableLiveData(false)
    val isRedeeming: LiveData<Boolean> = _isRedeeming

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

    fun loadOptions() {
        viewModelScope.launch {
            try {
                _options.value = userRepository.getRedemptionOptions()
            } catch (e: Exception) {
                _options.value = emptyList()
            }
        }
    }

    fun redeem(option: RedemptionOption, payoutNumber: String?) {
        if (_isRedeeming.value == true) return

        viewModelScope.launch {
            _isRedeeming.value = true
            _redemptionResult.value = userRepository.redeem(option.id, payoutNumber)
            _isRedeeming.value = false
        }
    }

    fun clearRedemptionResult() {
        _redemptionResult.value = null
    }
}

sealed class RedemptionResult {
    data class Success(val pointsSpent: Int, val remainingPoints: Int) : RedemptionResult()
    data class Error(val message: String) : RedemptionResult()
}

sealed class ReferralResult {
    object Success : ReferralResult()
    data class Error(val message: String) : ReferralResult()
    object InvalidCode : ReferralResult()
    object AlreadyUsed : ReferralResult()
}
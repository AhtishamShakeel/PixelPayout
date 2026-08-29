package com.example.pixelpayout.ui.redemption

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.pixelpayout.data.model.RedemptionGame
import com.example.pixelpayout.data.model.RedemptionPack
import com.example.pixelpayout.data.repository.RedemptionOptionsStore
import com.example.pixelpayout.data.repository.UserRepository
import kotlinx.coroutines.launch

class ReferralViewModel(private val userRepository: UserRepository) : ViewModel() {

    /**
     * Nullable and cleared once handled.
     *
     * This view model is activity scoped - the first-run popup and the card
     * on Profile share it - so a result left sitting here is redelivered to
     * whichever of them observes next. That is how opening Profile could
     * greet the user with the snackbar from a referral they submitted days
     * ago. Clearing after handling is what stops it.
     */
    private val _referralResult = MutableLiveData<ReferralResult?>()
    val referralResult: LiveData<ReferralResult?> = _referralResult

    /**
     * The catalogue, straight off the shared store. Not a copy held here:
     * the store is what keeps it cached between visits to the tab and what
     * pushes through edits made in the Firebase console.
     */
    val games: LiveData<List<RedemptionGame>> = userRepository.redemptionGames

    val isLoadingGames: LiveData<Boolean> = RedemptionOptionsStore.isLoading

    val orders: LiveData<List<UserRepository.Order>> = userRepository.orders

    private val _history = MutableLiveData<List<UserRepository.LedgerEntry>>(emptyList())
    val history: LiveData<List<UserRepository.LedgerEntry>> = _history

    private val _redemptionResult = MutableLiveData<RedemptionResult?>()
    val redemptionResult: LiveData<RedemptionResult?> = _redemptionResult

    private val _isRedeeming = MutableLiveData(false)
    val isRedeeming: LiveData<Boolean> = _isRedeeming

    /**
     * The level at which the first-redeem discount unlocks. Null until the
     * config read lands; the offer card stays hidden until then rather than
     * promising an unlock level it might have to correct a moment later.
     */
    private val _firstRedeemMinLevel = MutableLiveData<Int?>(null)
    val firstRedeemMinLevel: LiveData<Int?> = _firstRedeemMinLevel

    fun submitReferral(referralCode: String) {
        viewModelScope.launch {
            try {
                _referralResult.value = userRepository.submitReferral(referralCode)
            } catch (e: Exception) {
                _referralResult.value = ReferralResult.Error(e.message ?: "Unknown error occurred")
            }
        }
    }

    /** Idempotent - the store ignores this once it is already listening. */
    fun loadGames() {
        userRepository.observeRedemptionGames()

        if (_firstRedeemMinLevel.value == null) {
            viewModelScope.launch {
                _firstRedeemMinLevel.value = userRepository.getFirstRedeemMinLevel()
            }
        }
    }

    /**
     * Re-reads the ledger. Called when the tab appears and after a redemption,
     * which are the two moments the list could be out of date - the ledger has
     * no snapshot to listen to that would not also mean holding a query open
     * for a screen the user is usually not looking at.
     */
    fun refreshHistory() {
        viewModelScope.launch {
            _history.value = userRepository.getEarningHistory()
        }
    }

    suspend fun gameProfile(gameId: String): UserRepository.GameProfile? =
        userRepository.getGameProfile(gameId)

    fun redeem(
        game: RedemptionGame,
        pack: RedemptionPack,
        playerId: String,
        username: String,
        server: String,
        useFirstRedeem: Boolean
    ) {
        if (_isRedeeming.value == true) return

        viewModelScope.launch {
            _isRedeeming.value = true
            _redemptionResult.value =
                userRepository.redeem(game, pack, playerId, username, server, useFirstRedeem)
            _isRedeeming.value = false
            // The ledger has a new line whether this succeeded or not being
            // worth showing; refreshing only on success would leave a failed
            // attempt looking like it silently did something.
            refreshHistory()
        }
    }

    fun clearRedemptionResult() {
        _redemptionResult.value = null
    }

    fun clearReferralResult() {
        _referralResult.value = null
    }
}

sealed class RedemptionResult {
    data class Success(
        val pointsSpent: Int,
        val remainingPoints: Int,
        val redemptionId: String
    ) : RedemptionResult()

    data class Error(val message: String) : RedemptionResult()
}

sealed class ReferralResult {
    object Success : ReferralResult()
    data class Error(val message: String) : ReferralResult()
    object InvalidCode : ReferralResult()
    object AlreadyUsed : ReferralResult()
}

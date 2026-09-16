package com.createbyte.lootlevel.ui.redemption

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.createbyte.lootlevel.data.model.RedemptionGame
import com.createbyte.lootlevel.data.model.RedemptionPack
import com.createbyte.lootlevel.data.repository.RedemptionOptionsStore
import com.createbyte.lootlevel.data.repository.UserRepository
import com.google.firebase.firestore.DocumentSnapshot
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

    /**
     * Where the next page starts. Held here rather than in the repository,
     * which is constructed per view model and owns no screen state.
     */
    private var historyCursor: DocumentSnapshot? = null

    /** Whether there is anything left to load - drives the Load more row. */
    private val _historyHasMore = MutableLiveData(false)
    val historyHasMore: LiveData<Boolean> = _historyHasMore

    private val _isLoadingHistory = MutableLiveData(false)
    val isLoadingHistory: LiveData<Boolean> = _isLoadingHistory

    private val _redemptionResult = MutableLiveData<RedemptionResult?>()
    val redemptionResult: LiveData<RedemptionResult?> = _redemptionResult

    private val _isRedeeming = MutableLiveData(false)
    val isRedeeming: LiveData<Boolean> = _isRedeeming

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
    }

    /**
     * Re-reads the ledger. Called when the tab appears and after a redemption,
     * which are the two moments the list could be out of date - the ledger has
     * no snapshot to listen to that would not also mean holding a query open
     * for a screen the user is usually not looking at.
     */
    fun refreshHistory() {
        if (_isLoadingHistory.value == true) return

        viewModelScope.launch {
            _isLoadingHistory.value = true
            val page = userRepository.getEarningHistory()
            // Back to one page. An expanded list is not carried across a
            // visit to another tab on purpose: re-reading everything the user
            // had paged through would make returning to Wallet cost more the
            // more they had looked at, which is the opposite of the point.
            _history.value = page.entries
            historyCursor = page.cursor
            _historyHasMore.value = page.hasMore
            _isLoadingHistory.value = false
        }
    }

    /**
     * The next page, appended.
     *
     * Only ever reached by a tap, so the reads past the first page are ones
     * somebody actually asked for. The cursor is dropped when the ledger runs
     * out, which is what retires the button.
     */
    fun loadMoreHistory() {
        if (_isLoadingHistory.value == true) return
        val cursor = historyCursor ?: return

        viewModelScope.launch {
            _isLoadingHistory.value = true
            val page = userRepository.getEarningHistory(after = cursor)
            _history.value = _history.value.orEmpty() + page.entries
            historyCursor = page.cursor
            _historyHasMore.value = page.hasMore
            _isLoadingHistory.value = false
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

    /**
     * @param code the server's raw rejection code, when there was one.
     *   Carried alongside the message because ONE of these codes is not a
     *   correction the user can act on - first_redeem_uid_used ends the offer
     *   outright - and the sheet has to be able to tell that case apart
     *   without matching on display text.
     */
    data class Error(val message: String, val code: String? = null) : RedemptionResult()
}

sealed class ReferralResult {
    object Success : ReferralResult()
    data class Error(val message: String) : ReferralResult()
    object InvalidCode : ReferralResult()
    object AlreadyUsed : ReferralResult()

    /**
     * The account reached the unlock level without entering a code, so the
     * chance has expired. Distinct from [AlreadyUsed] because nothing was
     * spent - telling somebody a code they never used was "already used"
     * would send them looking for a mistake that never happened.
     */
    object WindowClosed : ReferralResult()
}

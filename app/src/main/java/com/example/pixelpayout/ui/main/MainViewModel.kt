package com.example.pixelpayout.ui.main

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.map  // Add this import
import androidx.lifecycle.viewModelScope
import com.example.pixelpayout.data.model.RedemptionOption
import com.example.pixelpayout.data.repository.UserRepository
import kotlinx.coroutines.launch

class MainViewModel(private val userRepository: UserRepository) : ViewModel() {
    val points: LiveData<Int> = userRepository.userData.map { userData ->
        userData.points
    }

    val xp: LiveData<Int> = userRepository.userData.map { userData ->
        userData.xp
    }

    val level: LiveData<Int> = userRepository.userData.map { userData ->
        userData.level
    }

    val activeBuff: LiveData<UserRepository.TimedBuff?> =
        userRepository.userData.map { userData ->
            Log.d("BUFF_DEBUG", "activeBuff = ${userData.activeBuff}")
            Log.d("BUFF_DEBUG", "isActive = ${userData.activeBuff?.isActive()}")
            userData.activeBuff
        }

    /**
     * The XP buff, tracked apart from the Points one because the server keeps
     * them as separate grants with separate eligibility - a user can be
     * running either, both, or neither.
     */
    val activeXpBuff: LiveData<UserRepository.TimedBuff?> =
        userRepository.userData.map { userData ->
            Log.d("BUFF_DEBUG", "activeXpBuff = ${userData.activeXpBuff}")
            userData.activeXpBuff
        }

    /**
     * The cheapest redemption the user cannot afford yet - what the balance
     * bar on the home screen fills toward.
     *
     * The label is the OPTION'S OWN TITLE ("100 UC", "Rs 500"), never a
     * points-to-currency rate computed here. redemptionOptions carries a
     * pointsCost and a free-text title but no machine-readable currency
     * amount, so any "= 90 UC" figure would mean parsing that title - which
     * silently produces a wrong number the moment someone edits a title in
     * Firestore. Showing the real target is honest; inventing a rate is not.
     *
     * Level-gated options the user cannot reach yet are skipped: filling a
     * bar toward something they are not allowed to buy is a false promise.
     */
    data class NextRedemption(
        val title: String,
        val pointsCost: Int,
        val pointsShort: Int,
        val percent: Int
    )

    private val redemptionOptions = MutableLiveData<List<RedemptionOption>>(emptyList())

    val nextRedemption: LiveData<NextRedemption?> = MediatorLiveData<NextRedemption?>().apply {
        fun recompute() {
            val user = userRepository.userData.value
            val options = redemptionOptions.value.orEmpty()
            if (user == null || options.isEmpty()) {
                value = null
                return
            }

            val target = options
                .filter { it.minLevel <= user.level && it.pointsCost > user.points }
                .minByOrNull { it.pointsCost }

            // Nothing left to reach means everything on offer is already
            // affordable - the bar has no meaning, so hide it rather than
            // showing a permanently full one.
            if (target == null) {
                value = null
                return
            }

            value = NextRedemption(
                title = target.title,
                pointsCost = target.pointsCost,
                pointsShort = (target.pointsCost - user.points).coerceAtLeast(0),
                percent = (user.points * 100 / target.pointsCost).coerceIn(0, 100)
            )
        }

        addSource(userRepository.userData) { recompute() }
        addSource(redemptionOptions) { recompute() }
    }

    init {
        // Options are server-managed and change rarely, so one read at start
        // is enough; a failure just leaves the bar hidden.
        viewModelScope.launch {
            redemptionOptions.value = runCatching { userRepository.getRedemptionOptions() }
                .getOrDefault(emptyList())
        }
    }

    val streak: LiveData<UserRepository.Streak> = userRepository.userData.map { it.streak }

    val pendingRedemptions: LiveData<UserRepository.PendingRedemptions> =
        userRepository.pendingRedemptions

    val payoutFeed: LiveData<List<UserRepository.PayoutFeedEntry>> =
        userRepository.payoutFeed

    val resolvedRedemptions: LiveData<List<UserRepository.ResolvedRedemption>> =
        userRepository.resolvedRedemptions

    /** Claims today's streak reward. The server owns every rule about it. */
    suspend fun claimDailyStreak(adWatched: Boolean): UserRepository.StreakClaimResult =
        userRepository.claimDailyStreak(adWatched)

    suspend fun getStreakConfig(): List<UserRepository.StreakDayReward> =
        userRepository.getStreakConfig()

    /** Points and level together, for screens that gate on both. */
    data class UserState(val points: Int, val level: Int)

    val userState: LiveData<UserState> = userRepository.userData.map { userData ->
        UserState(userData.points, userData.level)
    }

    /**
     * Progress through the CURRENT level, rather than lifetime XP. Lifetime XP
     * is still what's stored and what drives leaderboards - this is just the
     * view of it that resets each level, which is what reads as progress.
     */
    data class LevelProgress(
        val level: Int,
        val xpIntoLevel: Int,
        val xpForNextLevel: Int,
        val isMaxLevel: Boolean,
        val totalXp: Int
    )

    val levelProgress: LiveData<LevelProgress> = MediatorLiveData<LevelProgress>().apply {
        fun recompute() {
            val user = userRepository.userData.value ?: return
            val curve = userRepository.levelCurve.value

            if (curve == null) {
                // Curve not loaded yet - show the level we know, no bar.
                value = LevelProgress(
                    level = user.level,
                    xpIntoLevel = 0,
                    xpForNextLevel = 0,
                    isMaxLevel = false,
                    totalXp = user.xp
                )
                return
            }

            val floor = curve.xpRequiredFor(user.level)
            val isMax = user.level >= curve.maxLevel

            value = LevelProgress(
                level = user.level,
                xpIntoLevel = (user.xp - floor).coerceAtLeast(0),
                xpForNextLevel = if (isMax) 0 else curve.xpRequiredFor(user.level + 1) - floor,
                isMaxLevel = isMax,
                totalXp = user.xp
            )
        }

        addSource(userRepository.userData) { recompute() }
        addSource(userRepository.levelCurve) { recompute() }
    }
}

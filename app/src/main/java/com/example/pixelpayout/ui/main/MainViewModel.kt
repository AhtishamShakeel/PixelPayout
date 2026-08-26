package com.example.pixelpayout.ui.main

import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.map  // Add this import
import com.example.pixelpayout.data.repository.UserRepository

class MainViewModel(userRepository: UserRepository) : ViewModel() {
    val points: LiveData<Int> = userRepository.userData.map { userData ->
        userData.points
    }

    val xp: LiveData<Int> = userRepository.userData.map { userData ->
        userData.xp
    }

    val level: LiveData<Int> = userRepository.userData.map { userData ->
        userData.level
    }

    val activeBuff: LiveData<UserRepository.PointsBuff?> = userRepository.userData.map { userData ->
        userData.activeBuff?.takeIf { it.isActive() }
    }

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

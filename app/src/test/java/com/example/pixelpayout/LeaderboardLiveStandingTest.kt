package com.example.pixelpayout

import com.example.pixelpayout.data.repository.UserRepository.Leaderboard
import com.example.pixelpayout.data.repository.UserRepository.LeaderboardEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Leaderboard.withLiveStanding: the caller's standing kept live between fetches. */
class LeaderboardLiveStandingTest {

    private val prizes = listOf(350, 200, 200, 100, 100)

    private fun entry(rank: Int, xp: Int, isMe: Boolean = false) =
        LeaderboardEntry(rank, "p$rank", xp, prizes.getOrElse(rank - 1) { 50 }, isMe)

    private fun board(
        entries: List<LeaderboardEntry>,
        myXp: Int = 0,
        myRank: Int = 0,
        myPrize: Int = 0,
        unlocked: Boolean = true,
        full: Boolean = true
    ) = Leaderboard(
        entries = entries, myRank = myRank, myXp = myXp, myPrize = myPrize,
        prizePool = 2450, size = 30, weekEndsAtMillis = 0L,
        unlocked = unlocked, unlockLevel = 10,
        full = full, prizeBands = emptyList()
    )

    @Test
    fun unchangedStandingReturnsTheSameBoard() {
        val b = board(listOf(entry(1, 500), entry(2, 40, isMe = true)), myXp = 40, myRank = 2, myPrize = 200)
        assertSame(b, b.withLiveStanding(40, true))
    }

    @Test
    fun aFinishedQuizMovesAPlayerUpTheBoardInHand() {
        val b = board(
            listOf(entry(1, 500), entry(2, 300), entry(3, 100, isMe = true)),
            myXp = 100, myRank = 3, myPrize = 200
        )
        val live = b.withLiveStanding(400, true)
        assertEquals(400, live.myXp)
        assertEquals(2, live.myRank)
        assertEquals(200, live.myPrize)
    }

    @Test
    fun reachingTheUnlockLevelPlacesTheFirstXpOnTheBoard() {
        val b = board(listOf(entry(1, 500), entry(2, 300)), unlocked = false)
        val live = b.withLiveStanding(350, true)
        assertEquals(true, live.unlocked)
        assertEquals(2, live.myRank)
        assertEquals(200, live.myPrize)
    }

    @Test
    fun aLockedPlayerIsNeverRankedWhateverTheStoredXp() {
        val b = board(listOf(entry(1, 500)), unlocked = false)
        val live = b.withLiveStanding(900, false)
        assertEquals(false, live.unlocked)
        assertEquals(0, live.myXp)
        assertEquals(0, live.myRank)
        assertEquals(0, live.myPrize)
    }

    @Test
    fun aPodiumOnlyBoardKeepsTheServersRankWhenItCannotTell() {
        val b = board(
            listOf(entry(1, 900), entry(2, 800), entry(3, 700)),
            myXp = 100, myRank = 12, myPrize = 50, full = false
        )
        val live = b.withLiveStanding(150, true)
        assertEquals(150, live.myXp)
        assertEquals(12, live.myRank)
        assertEquals(50, live.myPrize)
    }

    @Test
    fun noXpMeansNoRank() {
        val b = board(listOf(entry(1, 500)), myXp = 0, unlocked = false)
        assertEquals(0, b.withLiveStanding(0, true).myRank)
    }
}

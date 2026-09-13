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
        entered: Boolean = false,
        expectedRank: Int = 0,
        expectedPrize: Int = 0,
        full: Boolean = true
    ) = Leaderboard(
        entries = entries, myRank = myRank, myXp = myXp, myPrize = myPrize,
        prizePool = 2450, size = 30, weekEndsAtMillis = 0L, entryFee = 20,
        entered = entered, expectedRank = expectedRank, expectedPrize = expectedPrize,
        full = full, prizeBands = emptyList(), entriesCloseAtMillis = 0L
    )

    @Test
    fun unchangedStandingReturnsTheSameBoard() {
        val b = board(listOf(entry(1, 500)), myXp = 40, expectedRank = 2, expectedPrize = 200)
        assertSame(b, b.withLiveStanding(40, false))
    }

    @Test
    fun aFinishedQuizMovesAnEntrantUpTheBoardInHand() {
        val b = board(
            listOf(entry(1, 500), entry(2, 300), entry(3, 100, isMe = true)),
            myXp = 100, myRank = 3, myPrize = 200, entered = true
        )
        val live = b.withLiveStanding(400, true)
        assertEquals(400, live.myXp)
        assertEquals(2, live.myRank)
        assertEquals(200, live.myPrize)
    }

    @Test
    fun aNonEntrantsExpectedRankFollowsTheirXp() {
        val b = board(listOf(entry(1, 500), entry(2, 300)), myXp = 10, expectedRank = 3)
        val live = b.withLiveStanding(350, false)
        assertEquals(0, live.myRank)
        assertEquals(2, live.expectedRank)
        assertEquals(200, live.expectedPrize)
    }

    @Test
    fun enteringTurnsTheExpectedPlaceIntoAHeldOne() {
        val b = board(listOf(entry(1, 500), entry(2, 300)), myXp = 350, expectedRank = 2, expectedPrize = 200)
        val live = b.withLiveStanding(350, true)
        assertEquals(true, live.entered)
        assertEquals(2, live.myRank)
        assertEquals(200, live.myPrize)
        assertEquals(0, live.expectedRank)
    }

    @Test
    fun aPodiumOnlyBoardKeepsTheServersRankWhenItCannotTell() {
        val b = board(
            listOf(entry(1, 900), entry(2, 800), entry(3, 700)),
            myXp = 100, myRank = 12, myPrize = 50, entered = true, full = false
        )
        val live = b.withLiveStanding(150, true)
        assertEquals(150, live.myXp)
        assertEquals(12, live.myRank)
        assertEquals(50, live.myPrize)
    }

    @Test
    fun noXpMeansNoRank() {
        val b = board(listOf(entry(1, 500)), myXp = 0, entered = false)
        assertEquals(0, b.withLiveStanding(0, true).myRank)
    }
}

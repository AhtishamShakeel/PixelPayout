package com.example.pixelpayout.ui.game

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.children
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.example.pixelpayout.ui.main.MAX_DAILY_GAME_SESSIONS
import com.example.pixelpayout.ui.main.MainViewModel
import com.example.pixelpayout.utils.ServerClock
import com.pixelpayout.R
import com.pixelpayout.databinding.FragmentGameBinding
import java.util.concurrent.TimeUnit

/**
 * Play > Games, built from the Games.dc.html handoff.
 *
 * The daily allowance is read straight off the shared user snapshot, the same
 * way QuizListFragment reads the quiz one - both counters live on the user
 * document behind one day stamp, so there is nothing to fetch. What is drawn
 * here is display only; the cap that actually refuses a run is applied inside
 * the claimReward transaction against the server's clock.
 */
class GameFragment : Fragment() {
    private var _binding: FragmentGameBinding? = null
    private val binding get() = _binding!!

    private val mainViewModel: MainViewModel by activityViewModels()

    private val timerHandler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            updateResetCountdown()
            timerHandler.postDelayed(this, 1_000)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGameBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val xpChip = getString(R.string.game_xp_chip, GAME_XP_PER_SESSION_CAP)
        binding.flappyXpChip.text = xpChip
        binding.towerXpChip.text = xpChip
        binding.gamesAvailable.text = getString(R.string.games_available, GAME_COUNT)
        binding.gamesFootnote.text =
            getString(R.string.games_xp_footnote, GAME_XP_PER_SESSION_CAP)

        buildPips()

        binding.playFlappy.setOnClickListener { launchGame(GamePlayActivity.SLUG_FLAPPY) }
        binding.playTower.setOnClickListener { launchGame(GamePlayActivity.SLUG_TOWER) }

        mainViewModel.gameAttemptsToday.observe(viewLifecycleOwner) { used ->
            renderAllowance(used.coerceIn(0, MAX_DAILY_GAME_SESSIONS))
        }
    }

    override fun onResume() {
        super.onResume()
        timerHandler.post(timerRunnable)
    }

    override fun onPause() {
        super.onPause()
        timerHandler.removeCallbacks(timerRunnable)
    }

    /**
     * One pip per play in the allowance, sized by weight so the row fills the
     * card whatever the cap happens to be. Built here rather than in XML so a
     * change to MAX_DAILY_GAME_SESSIONS cannot leave a stale number of pips.
     */
    private fun buildPips() {
        val row = binding.playsPips
        val gap = resources.getDimensionPixelSize(R.dimen.game_pip_gap)
        row.removeAllViews()

        repeat(MAX_DAILY_GAME_SESSIONS) { index ->
            val pip = View(requireContext())
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            if (index > 0) params.marginStart = gap
            pip.layoutParams = params
            row.addView(pip)
        }
    }

    private fun renderAllowance(used: Int) {
        val remaining = MAX_DAILY_GAME_SESSIONS - used
        val hasPlaysLeft = remaining > 0

        binding.playsLeftNote.text = if (hasPlaysLeft) {
            getString(R.string.games_plays_left, remaining, MAX_DAILY_GAME_SESSIONS)
        } else {
            // The handoff's copy, and true here: the quiz allowance is a
            // separate counter, so a spent game allowance leaves it untouched.
            getString(R.string.games_plays_spent)
        }

        // Spent pips go grey from the left, so the violet that remains reads as
        // what is left rather than as what has been used.
        binding.playsPips.children.forEachIndexed { index, pip ->
            pip.setBackgroundResource(
                if (index < used) R.drawable.bg_pip_spent else R.drawable.bg_pip_remaining
            )
        }

        setRowEnabled(binding.playFlappy, binding.flappyAction, hasPlaysLeft)
        setRowEnabled(binding.playTower, binding.towerAction, hasPlaysLeft)
    }

    /**
     * A spent row stays on screen, dimmed, with its play glyph swapped for a
     * clock - the handoff's "Limit reached" state. Hiding the rows instead
     * would make the screen look like it had lost its content.
     */
    private fun setRowEnabled(row: View, action: ImageView, enabled: Boolean) {
        row.isEnabled = enabled
        row.alpha = if (enabled) 1f else 0.55f

        action.setBackgroundResource(
            if (enabled) R.drawable.bg_play_action else R.drawable.bg_play_action_locked
        )
        action.setImageResource(
            if (enabled) R.drawable.ic_play_fill else R.drawable.ic_clock_countdown
        )
        action.imageTintList = ContextCompat.getColorStateList(
            requireContext(),
            if (enabled) R.color.brand_violet_light else R.color.text_ghost
        )
    }

    private fun updateResetCountdown() {
        if (_binding == null) return

        val remainingMs = mainViewModel.nextAttemptsResetMillis() - ServerClock.now()
        val seconds = (remainingMs / 1_000).coerceAtLeast(0)

        // HH:MM:SS, as the handoff draws it - a monospace ticker rather than a
        // rounded "4h 12m", so the digits do not jitter as they count down.
        binding.playsResetTimer.text = String.format(
            "%02d:%02d:%02d",
            TimeUnit.SECONDS.toHours(seconds),
            TimeUnit.SECONDS.toMinutes(seconds) % 60,
            seconds % 60
        )
    }

    private fun launchGame(slug: String) {
        // Re-checked at the moment of the tap rather than trusted from the last
        // render: startGameSession refuses an over-cap session anyway, but that
        // failure currently surfaces as a stuck spinner, so it is worth not
        // reaching.
        if (mainViewModel.gameAttemptsNow() >= MAX_DAILY_GAME_SESSIONS) {
            Toast.makeText(requireContext(), R.string.games_limit_toast, Toast.LENGTH_LONG).show()
            return
        }

        val intent = Intent(requireContext(), GamePlayActivity::class.java)
        intent.putExtra("GAME_URL", GamePlayActivity.gameUrl(slug))
        startActivity(intent)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        timerHandler.removeCallbacks(timerRunnable)
        _binding = null
    }

    private companion object {
        /** Mirrors the server's GAME_XP_PER_SESSION_CAP, for the "up to" chip. */
        const val GAME_XP_PER_SESSION_CAP = 30

        /** Games wired up on this screen, for the "N available" count. */
        const val GAME_COUNT = 2
    }
}

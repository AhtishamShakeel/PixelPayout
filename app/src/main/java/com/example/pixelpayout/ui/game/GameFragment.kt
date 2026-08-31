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
import androidx.lifecycle.lifecycleScope
import com.example.pixelpayout.data.repository.UserRepository
import com.example.pixelpayout.ui.main.MAX_DAILY_BONUS_ATTEMPTS
import com.example.pixelpayout.ui.main.MAX_DAILY_GAME_SESSIONS
import com.example.pixelpayout.ui.main.MainViewModel
import com.example.pixelpayout.utils.AdManager
import com.example.pixelpayout.utils.ServerClock
import kotlinx.coroutines.launch
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
            // Ad availability is polled on the countdown's tick rather than
            // subscribed to: AdManager holds a single availability listener
            // and Home already owns it. A second registration would silently
            // unsubscribe Home's, which is a worse bug than a one-second lag
            // on a pill becoming tappable.
            refreshBonusButtonState()
            timerHandler.postDelayed(this, 1_000)
        }
    }

    /** How many pips the row currently holds, so it is only rebuilt on change. */
    private var pipCount = 0

    /** True from the tap until the grant settles, so one ad buys one attempt. */
    private var bonusInFlight = false

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

        AdManager.getInstance().loadRewardedAd(requireContext())

        buildPips(MAX_DAILY_GAME_SESSIONS)
        // Painted before the observer, because the observer does not fire
        // until the user snapshot arrives. Without this the card sat on an
        // empty allowance, which reads as "no plays left" rather than as
        // "not known yet" - and the rows were live while it did.
        renderLoading()

        binding.playFlappy.setOnClickListener { launchGame(GamePlayActivity.SLUG_FLAPPY) }
        binding.playTower.setOnClickListener { launchGame(GamePlayActivity.SLUG_TOWER) }

        binding.gameBonusButton.setOnClickListener { buyBonusAttempt() }

        mainViewModel.gameAllowance.observe(viewLifecycleOwner) { renderAllowance(it) }
    }

    /**
     * The state before the first snapshot: no count, no pips lit, and both
     * rows disabled so a tap cannot open a session against an allowance
     * nobody has checked yet.
     */
    private fun renderLoading() {
        binding.playsLeftNote.text = getString(R.string.games_plays_loading)
        binding.playsPips.children.forEach { pip ->
            pip.setBackgroundResource(R.drawable.bg_pip_spent)
        }
        setRowEnabled(binding.playFlappy, binding.flappyAction, false)
        setRowEnabled(binding.playTower, binding.towerAction, false)
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
     *
     * [count] is now the allowance rather than the constant, because a bought
     * attempt widens it: the row has to grow to thirteen and shrink back at
     * the rollover. Rebuilt only when the number actually changes - the
     * snapshot fires on every points or XP change too, and re-inflating the
     * row on each would flicker it.
     */
    private fun buildPips(count: Int) {
        if (pipCount == count) return
        pipCount = count

        val row = binding.playsPips
        val gap = resources.getDimensionPixelSize(R.dimen.game_pip_gap)
        row.removeAllViews()

        repeat(count) { index ->
            val pip = View(requireContext())
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            if (index > 0) params.marginStart = gap
            pip.layoutParams = params
            row.addView(pip)
        }
    }

    private fun renderAllowance(allowance: MainViewModel.Allowance) {
        val used = allowance.used
        val hasPlaysLeft = allowance.remaining > 0

        buildPips(allowance.allowance)

        binding.playsLeftNote.text = when {
            hasPlaysLeft ->
                getString(R.string.games_plays_left, allowance.remaining, allowance.allowance)

            // "Back tomorrow" stops being true while the pill is on screen,
            // so the spent line has to know whether one more can still be
            // bought.
            allowance.canBuyMore -> getString(R.string.games_plays_spent_buyable)

            // The handoff's copy, and true here: the quiz allowance is a
            // separate counter, so a spent game allowance leaves it untouched.
            else -> getString(R.string.games_plays_spent)
        }

        binding.gameBonusRow.visibility =
            if (allowance.canBuyMore) View.VISIBLE else View.GONE
        binding.gameBonusNote.text = getString(
            R.string.bonus_attempt_remaining,
            MAX_DAILY_BONUS_ATTEMPTS - allowance.bonusBought
        )
        refreshBonusButtonState()

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
        if (mainViewModel.gameAllowanceNow().remaining <= 0) {
            Toast.makeText(requireContext(), R.string.games_limit_toast, Toast.LENGTH_LONG).show()
            return
        }

        val intent = Intent(requireContext(), GamePlayActivity::class.java)
        intent.putExtra("GAME_URL", GamePlayActivity.gameUrl(slug))
        startActivity(intent)
    }

    /**
     * Greys the pill while an ad is unavailable or a grant is in flight.
     *
     * The pill stays VISIBLE either way - it disappears only at the daily cap.
     * Hiding it on a momentary fill gap would make the offer flicker in and
     * out of the card once a second.
     */
    private fun refreshBonusButtonState() {
        val binding = _binding ?: return
        val ready = !bonusInFlight && AdManager.getInstance().isRewardedAdReady()
        binding.gameBonusButton.isEnabled = ready
        binding.gameBonusButton.alpha = if (ready) 1f else 0.5f
    }

    /**
     * Watch an ad, then buy one attempt.
     *
     * The grant fires on the REWARD callback rather than on dismissal. Both
     * arrive on a normal completion, but the reward comes first, so claiming
     * there shrinks the window in which a killed process loses an ad the user
     * actually sat through. [bonusInFlight] is what stops the pair of
     * callbacks buying two attempts for one ad.
     */
    private fun buyBonusAttempt() {
        if (bonusInFlight) return

        if (!AdManager.getInstance().isRewardedAdReady()) {
            toast(R.string.bonus_attempt_ad_unavailable)
            AdManager.getInstance().loadRewardedAd(requireContext())
            return
        }

        bonusInFlight = true
        binding.gameBonusLabel.setText(R.string.bonus_attempt_loading)
        refreshBonusButtonState()

        var claimed = false
        AdManager.getInstance().showRewardedAd(
            activity = requireActivity(),
            onRewarded = {
                if (!claimed) {
                    claimed = true
                    submitBonusAttempt()
                }
            },
            // Dismissal without a reward means the ad was closed early. There
            // is nothing to buy and nothing to apologise for.
            onAdClosed = { if (!claimed) endBonusAttempt() },
            onAdFailedToShow = {
                if (!claimed) {
                    toast(R.string.bonus_attempt_ad_unavailable)
                    endBonusAttempt()
                }
            }
        )
    }

    private fun submitBonusAttempt() {
        // The fragment's own scope: if the user leaves, this simply stops
        // caring. The grant still lands server-side and the snapshot listener
        // brings it back next time the card is drawn.
        viewLifecycleOwner.lifecycleScope.launch {
            when (mainViewModel.buyBonusAttempt(UserRepository.BonusActivity.GAME)) {
                is UserRepository.BonusAttemptResult.Granted ->
                    toast(R.string.bonus_attempt_added_game)

                UserRepository.BonusAttemptResult.AtCap ->
                    toast(R.string.bonus_attempt_at_cap)

                is UserRepository.BonusAttemptResult.Error ->
                    toast(R.string.bonus_attempt_failed)
            }
            // The card itself is repainted by the snapshot listener, which is
            // the only thing that knows what the server actually stored.
            endBonusAttempt()
        }
    }

    private fun endBonusAttempt() {
        bonusInFlight = false
        _binding?.gameBonusLabel?.setText(R.string.bonus_attempt_action)
        refreshBonusButtonState()
    }

    private fun toast(resId: Int) {
        if (isAdded) Toast.makeText(requireContext(), resId, Toast.LENGTH_SHORT).show()
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

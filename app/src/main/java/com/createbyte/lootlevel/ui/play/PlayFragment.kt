package com.createbyte.lootlevel.ui.play

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.commitNow
import androidx.lifecycle.lifecycleScope
import com.createbyte.lootlevel.ui.game.GameFragment
import com.createbyte.lootlevel.ui.quiz.QuizListFragment
import com.google.android.material.tabs.TabLayout
import com.createbyte.lootlevel.R
import com.createbyte.lootlevel.data.repository.UserRepository
import com.createbyte.lootlevel.databinding.FragmentPlayBinding
import com.createbyte.lootlevel.databinding.ViewPlayTutorialBinding
import com.createbyte.lootlevel.ui.main.MainActivity
import com.createbyte.lootlevel.ui.main.MainViewModel
import com.createbyte.lootlevel.utils.PlayTutorial
import com.createbyte.lootlevel.utils.PlayTutorial.Step
import kotlinx.coroutines.launch

/**
 * Container for the two "play to earn" surfaces, which used to occupy two
 * separate bottom-nav tabs.
 *
 * GameFragment and QuizListFragment are hosted UNCHANGED. Neither uses
 * findNavController - they both launch activities directly - and
 * QuizListFragment scopes its ViewModel with activityViewModels, which still
 * resolves to the same instance from a child fragment. So nothing about
 * either screen had to be rewritten to live here.
 *
 * Children are hidden rather than replaced on tab switch, so the quiz list
 * keeps its scroll position and does not re-fetch every time the user flips
 * back and forth.
 *
 * Also runs the first-run tutorial (see [renderTutorial]), because this is
 * the one screen that holds both of the things it points at.
 */
class PlayFragment : Fragment() {

    private var _binding: FragmentPlayBinding? = null
    private val binding get() = _binding!!

    private val mainViewModel: MainViewModel by activityViewModels()

    /** The tutorial overlay while it is on screen. */
    private var tutorial: ViewPlayTutorialBinding? = null

    /** Guards against a tab selection made BY a render re-entering it. */
    private var renderingTutorial = false

    /** One completion call at a time. */
    private var completionInFlight = false

    /** The last completion call failed; the card offers a retry. */
    private var completionFailed = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlayBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val tabs = binding.playTabs
        tabs.addTab(tabs.newTab().setText(R.string.play_tab_games))
        tabs.addTab(tabs.newTab().setText(R.string.play_tab_quizzes))

        val start = when {
            savedInstanceState != null -> savedInstanceState.getInt(STATE_TAB, TAB_GAMES)
            else -> arguments?.getInt(ARG_START_TAB, TAB_GAMES) ?: TAB_GAMES
        }.coerceIn(TAB_GAMES, TAB_QUIZZES)

        tabs.getTabAt(start)?.select()
        showTab(start)

        // Attached AFTER the selection above, deliberately. Adding it earlier
        // means the setup selection fires the listener as well, showTab runs
        // twice for the same tab, and - because a plain commit() is queued
        // rather than immediate - the second run does not yet see the child
        // the first one added and adds a duplicate on top of it.
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                showTab(tab.position)
                renderTutorial()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })

        mainViewModel.playTutorialPending.observe(viewLifecycleOwner) { renderTutorial() }
    }

    override fun onResume() {
        super.onResume()
        // Coming back from a game or a quiz is what moves the walkthrough on,
        // and those are separate activities - so this, not an observer, is
        // where the next step gets drawn.
        renderTutorial()
    }

    override fun onPause() {
        super.onPause()
        removeTutorial()
    }

    private fun showTab(index: Int) {
        val fm = childFragmentManager
        // Nothing may be committed once state is saved; a tab tap racing the
        // fragment going away would otherwise crash.
        if (fm.isStateSaved) return

        val tag = if (index == TAB_QUIZZES) TAG_QUIZZES else TAG_GAMES
        val target = fm.findFragmentByTag(tag)

        // commitNow rather than commit: the transaction runs immediately, so a
        // later findFragmentByTag sees this child instead of adding a second
        // copy of it into the same container.
        fm.commitNow {
            setReorderingAllowed(true)
            fm.fragments.forEach { if (it.tag != tag) hide(it) }
            if (target == null) {
                val fragment = if (index == TAB_QUIZZES) QuizListFragment() else GameFragment()
                add(R.id.playContainer, fragment, tag)
            } else {
                show(target)
            }
        }
    }

    // --- First-run tutorial --------------------------------------------------

    /**
     * Draws whichever tutorial step the player is on, or nothing.
     *
     * Welcome -> play one game -> open Quizzes -> answer two quizzes -> the
     * server tops XP up to level 2 -> "Level 2!" -> Level rewards.
     *
     * Owed-or-not is the server's flag on the user document; the step is kept
     * locally (PlayTutorial). The one exception is the last card, which stays
     * up after the server has set the flag, so the player still sees it.
     */
    private fun renderTutorial() {
        if (renderingTutorial || !isResumed || _binding == null) return
        val context = context ?: return

        val step = PlayTutorial.step(context)
        if (step != Step.LEVEL_UP) {
            when (mainViewModel.playTutorialPending.value) {
                // The user snapshot has not landed yet.
                null -> return removeTutorial()
                false -> {
                    // Finished already - on another device, or before a reinstall.
                    if (PlayTutorial.isActive(context)) PlayTutorial.clear(context)
                    return removeTutorial()
                }
                true -> PlayTutorial.activate(context)
            }
        }

        renderingTutorial = true
        try {
            val t = ensureTutorial()
            t.root.onHoleTapped = null
            when (step) {
                Step.WELCOME -> showCard(t, t.tutorialWelcome)

                Step.PICK_GAME -> {
                    selectTab(TAB_GAMES)
                    showCallout(t, R.drawable.ic_game, R.string.tutorial_pick_game, done = 0) {
                        gameFragment()?.tutorialTargets().orEmpty()
                    }
                    // Nothing to play today (a reused account): a tap on the
                    // dimmed rows moves on instead of doing nothing forever.
                    // The server still checks the ledger at the end.
                    t.root.onHoleTapped = {
                        if (mainViewModel.gameAllowanceNow().remaining <= 0) {
                            advanceTutorial(Step.OPEN_QUIZZES)
                        }
                    }
                }

                Step.OPEN_QUIZZES -> {
                    if (binding.playTabs.selectedTabPosition == TAB_QUIZZES) {
                        PlayTutorial.setStep(context, Step.PICK_QUIZ)
                        renderingTutorial = false
                        return renderTutorial()
                    }
                    showCallout(
                        t, R.drawable.ic_quiz, R.string.tutorial_open_quizzes,
                        done = 1, check = true
                    ) {
                        listOfNotNull(binding.playTabs.getTabAt(TAB_QUIZZES)?.view)
                    }
                }

                Step.PICK_QUIZ -> {
                    selectTab(TAB_QUIZZES)
                    showCallout(
                        t, R.drawable.ic_quiz, R.string.tutorial_pick_quiz,
                        done = 1 + PlayTutorial.quizzesAnswered(context)
                    ) {
                        listOfNotNull(quizFragment()?.tutorialTarget())
                    }
                    t.root.onHoleTapped = {
                        if (mainViewModel.quizAllowanceNow().remaining <= 0) {
                            advanceTutorial(Step.FINISHING)
                        }
                    }
                }

                Step.FINISHING -> if (completionFailed) {
                    showCard(t, t.tutorialRetry)
                } else {
                    showCard(t, t.tutorialWorking)
                    completeTutorial()
                }

                Step.LEVEL_UP -> showLevelCard(t)
            }
        } finally {
            renderingTutorial = false
        }
    }

    private fun advanceTutorial(step: Step) {
        val context = context ?: return
        // Posted: this is reached from inside the overlay's touch dispatch.
        view?.post {
            PlayTutorial.setStep(context, step)
            renderTutorial()
        }
    }

    private fun ensureTutorial(): ViewPlayTutorialBinding {
        tutorial?.takeIf { it.root.parent != null }?.let { return it }

        val root = requireActivity().findViewById<ViewGroup>(android.R.id.content)
        val t = ViewPlayTutorialBinding.inflate(layoutInflater, root, false)
        root.addView(t.root)

        t.root.callout = t.tutorialCallout
        t.root.addCard(t.tutorialCallout)
        t.root.addCard(t.tutorialCenter)

        t.tutorialStart.setOnClickListener { advanceTutorial(Step.PICK_GAME) }
        t.tutorialRetry.setOnClickListener {
            completionFailed = false
            renderTutorial()
        }
        t.tutorialClaim.setOnClickListener { finishTutorial() }

        tutorial = t
        return t
    }

    private fun removeTutorial() {
        tutorial?.root?.let { (it.parent as? ViewGroup)?.removeView(it) }
        tutorial = null
    }

    /** A centred card with no lit target; [content] is the part to show. */
    private fun showCard(t: ViewPlayTutorialBinding, content: View) {
        t.root.targets = { emptyList() }
        t.tutorialCallout.isVisible = false
        t.tutorialCenter.isVisible = true
        listOf(t.tutorialWelcome, t.tutorialWorking, t.tutorialRetry, t.tutorialLevel)
            .forEach { it.isVisible = it === content }
    }

    /**
     * The lit target and its callout: a glyph, a few words, and one dot per
     * thing to do - [done] of them already green.
     */
    private fun showCallout(
        t: ViewPlayTutorialBinding,
        icon: Int,
        title: Int,
        done: Int,
        check: Boolean = false,
        targets: () -> List<View>
    ) {
        t.root.targets = targets
        t.tutorialCenter.isVisible = false
        t.tutorialCallout.isVisible = true
        t.tutorialCalloutIcon.setImageResource(icon)
        t.tutorialCalloutTitle.setText(title)
        t.tutorialCalloutCheck.isVisible = check
        renderDots(t.tutorialDots, done)
    }

    private fun renderDots(row: LinearLayout, done: Int) {
        val context = row.context
        val total = 1 + PlayTutorial.QUIZZES_REQUIRED
        val size = resources.getDimensionPixelSize(R.dimen.tutorial_dot)
        row.removeAllViews()
        repeat(total) { index ->
            val color = when {
                index < done -> R.color.success
                index == done -> R.color.brand_violet_light
                else -> R.color.text_ghost
            }
            row.addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    if (index > 0) marginStart = size
                }
                setBackgroundResource(R.drawable.bg_tutorial_dot)
                backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, color))
            })
        }
    }

    private fun showLevelCard(t: ViewPlayTutorialBinding) {
        val context = requireContext()
        val level = PlayTutorial.levelReached(context)
        val stars = PlayTutorial.starsWaiting(context)

        showCard(t, t.tutorialLevel)
        t.tutorialLevelNumber.text = level.toString()
        t.tutorialLevelTitle.text = getString(R.string.tutorial_level_title, level)
        t.tutorialStarsRow.isVisible = stars > 0
        t.tutorialStars.text = getString(R.string.tutorial_stars, stars)
        t.tutorialClaim.setText(if (stars > 0) R.string.tutorial_claim else R.string.tutorial_done)
    }

    /**
     * Asks the server to finish the tutorial. It checks its own ledger for the
     * run and the answers and tops XP up to level 2; nothing is sent.
     *
     * On the ACTIVITY's scope, so a player leaving Play mid-call does not
     * cancel a completion the server may already have committed.
     */
    private fun completeTutorial() {
        if (completionInFlight) return
        completionInFlight = true

        val activity = requireActivity()
        activity.lifecycleScope.launch {
            val result = mainViewModel.completePlayTutorial()
            completionInFlight = false

            when (result) {
                is UserRepository.PlayTutorialResult.Completed -> {
                    // What this call locked, or - when play already crossed
                    // the level on its own - what is still waiting in the queue.
                    val stars = result.milestonePoints.takeIf { it > 0 } ?: run {
                        val rewards = mainViewModel.levelCurve.value?.levelRewards.orEmpty()
                        mainViewModel.levelProgress.value?.pendingLevelRewards
                            ?.sumOf { rewards[it] ?: 0 } ?: 0
                    }
                    PlayTutorial.saveLevelUp(activity, result.level, stars)
                }

                // The ledger does not show a run and two answers - a claim
                // that never reached the server. Play the steps again.
                UserRepository.PlayTutorialResult.NotFinished ->
                    PlayTutorial.restartPlaying(activity)

                UserRepository.PlayTutorialResult.Error -> completionFailed = true
            }
            renderTutorial()
        }
    }

    private fun finishTutorial() {
        val context = context ?: return
        val level = PlayTutorial.levelReached(context)
        val stars = PlayTutorial.starsWaiting(context)
        PlayTutorial.finish(context)
        removeTutorial()
        (activity as? MainActivity)?.onPlayTutorialFinished(level, stars)
    }

    private fun selectTab(index: Int) {
        val tabs = _binding?.playTabs ?: return
        if (tabs.selectedTabPosition != index) tabs.getTabAt(index)?.select()
    }

    private fun gameFragment(): GameFragment? =
        childFragmentManager.findFragmentByTag(TAG_GAMES) as? GameFragment

    private fun quizFragment(): QuizListFragment? =
        childFragmentManager.findFragmentByTag(TAG_QUIZZES) as? QuizListFragment

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        _binding?.let { outState.putInt(STATE_TAB, it.playTabs.selectedTabPosition) }
    }

    override fun onDestroyView() {
        removeTutorial()
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val ARG_START_TAB = "startTab"
        const val TAB_GAMES = 0
        const val TAB_QUIZZES = 1

        private const val STATE_TAB = "play:selectedTab"
        private const val TAG_GAMES = "play:games"
        private const val TAG_QUIZZES = "play:quizzes"
    }
}

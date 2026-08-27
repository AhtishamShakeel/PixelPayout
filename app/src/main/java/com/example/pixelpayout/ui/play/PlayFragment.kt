package com.example.pixelpayout.ui.play

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.commitNow
import com.example.pixelpayout.ui.game.GameFragment
import com.example.pixelpayout.ui.quiz.QuizListFragment
import com.google.android.material.tabs.TabLayout
import com.pixelpayout.R
import com.pixelpayout.databinding.FragmentPlayBinding

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
 */
class PlayFragment : Fragment() {

    private var _binding: FragmentPlayBinding? = null
    private val binding get() = _binding!!

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
            override fun onTabSelected(tab: TabLayout.Tab) = showTab(tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        _binding?.let { outState.putInt(STATE_TAB, it.playTabs.selectedTabPosition) }
    }

    override fun onDestroyView() {
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

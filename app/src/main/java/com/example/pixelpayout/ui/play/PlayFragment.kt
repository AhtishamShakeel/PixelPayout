package com.example.pixelpayout.ui.play

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
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

        binding.playTabs.apply {
            addTab(newTab().setText(R.string.play_tab_games))
            addTab(newTab().setText(R.string.play_tab_quizzes))

            addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) = showTab(tab.position)
                override fun onTabUnselected(tab: TabLayout.Tab) = Unit
                override fun onTabReselected(tab: TabLayout.Tab) = Unit
            })
        }

        // Only honour the requested start tab on a fresh view. On a recreation
        // the child fragments already exist and the TabLayout restores its own
        // selection, so forcing it again would fight that.
        if (savedInstanceState == null) {
            val start = arguments?.getInt(ARG_START_TAB, TAB_GAMES) ?: TAB_GAMES
            binding.playTabs.getTabAt(start)?.select()
            showTab(start)
        }
    }

    private fun showTab(index: Int) {
        val tag = if (index == TAB_QUIZZES) TAG_QUIZZES else TAG_GAMES
        val fm = childFragmentManager
        val target = fm.findFragmentByTag(tag)

        fm.commit {
            setReorderingAllowed(true)
            fm.fragments.forEach { hide(it) }
            if (target == null) {
                val fragment = if (index == TAB_QUIZZES) QuizListFragment() else GameFragment()
                add(R.id.playContainer, fragment, tag)
            } else {
                show(target)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val ARG_START_TAB = "startTab"
        const val TAB_GAMES = 0
        const val TAB_QUIZZES = 1

        private const val TAG_GAMES = "play:games"
        private const val TAG_QUIZZES = "play:quizzes"
    }
}

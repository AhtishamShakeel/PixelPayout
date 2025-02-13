package com.pixelpayout.ui.details

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.navArgs
import com.pixelpayout.R
import com.pixelpayout.databinding.FragmentDetailsBinding

class DetailsFragment : Fragment() {
    private var _binding: FragmentDetailsBinding? = null
    private val binding get() = _binding!!
    private val args by navArgs<DetailsFragmentArgs>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDetailsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupContent()
    }

    private fun setupContent() {
        when (args.type) {
            "quiz" -> {
                binding.apply {
                    titleText.text = "Quiz Rules"
                    descriptionText.text = getString(R.string.quiz_rules_description)
                    rewardsText.text = "Rewards:\n• Easy: 10 stars\n• Medium: 20 stars\n• Hard: 30 stars"
                }
            }
            "game" -> {
                binding.apply {
                    titleText.text = "Game Rules"
                    descriptionText.text = getString(R.string.game_rules_description)
                    rewardsText.text = "Rewards:\n• 5-10 stars per minute based on performance"
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
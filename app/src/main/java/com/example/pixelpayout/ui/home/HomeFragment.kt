package com.pixelpayout.ui.home

import android.graphics.Point
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.activity.viewModels
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.pixelpayout.R
import com.pixelpayout.data.repository.UserRepository
import com.pixelpayout.databinding.FragmentHomeBinding
import com.pixelpayout.ui.main.MainActivity
import com.pixelpayout.ui.main.MainViewModel
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val mainViewModel: MainViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupClickListeners()
        observeViewModel()
    }
    override fun onResume() {
        super.onResume()
    }

    private fun observeViewModel(){
        mainViewModel.points.observe(viewLifecycleOwner){
            points -> binding.totalPoints.text = "Total Stars: $points"
        }
    }


    private fun setupClickListeners() {
        binding.apply {
            // Quiz card clicks
            playQuizButton.setOnClickListener {
                navigateToQuizzes()
            }

            quizImage.setOnClickListener {
                navigateToQuizzes()
            }

            quizDetails.setOnClickListener {
                navigateToDetails("quiz")
            }

            // Game card clicks
            playGameButton.setOnClickListener {
                navigateToGame()
            }

            gameImage.setOnClickListener {
                navigateToGame()
            }

            gameDetails.setOnClickListener {
                navigateToDetails("game")
            }

            btnPayout.setOnClickListener{
                (activity as? MainActivity)?.binding?.bottomNav?.selectedItemId = R.id.navigation_redemption
            }
        }
    }

    private fun navigateToQuizzes() {
        (activity as? MainActivity)?.binding?.bottomNav?.selectedItemId = R.id.navigation_quizzes
    }

    private fun navigateToGame() {
        (activity as? MainActivity)?.binding?.bottomNav?.selectedItemId = R.id.navigation_play
    }

    private fun navigateToDetails(type: String) {
        findNavController().navigate(
            HomeFragmentDirections.actionHomeToDetails(type)
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
} 
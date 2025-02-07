package com.pixelpayout.ui.home

import android.graphics.Point
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.activity.viewModels
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
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
    }
    override fun onResume() {
        super.onResume()
        // Refresh points when returning to home screen
        (activity as? MainActivity)?.refreshPoints()
    }


    private fun setupClickListeners() {
        binding.quizCard.setOnClickListener {
            (activity as? MainActivity)?.binding?.bottomNav?.selectedItemId = R.id.navigation_quizzes
        }

        binding.gameCard.setOnClickListener {
            (activity as? MainActivity)?.binding?.bottomNav?.selectedItemId = R.id.navigation_play
        }

    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
} 
package com.example.pixelpayout.ui.game

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.pixelpayout.databinding.FragmentGameBinding

class GameFragment : Fragment() {
    private var _binding: FragmentGameBinding? = null
    private val binding get() = _binding!!

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

        binding.play2048.setOnClickListener {
            val intent = Intent(requireContext(), GamePlayActivity::class.java)
            intent.putExtra("GAME_URL", "https://game-ccdff.web.app/") // URL for 2048
            startActivity(intent)
        }
        //

        binding.playFlappyBird.setOnClickListener {
            val intent = Intent(requireContext(), GamePlayActivity::class.java)
            intent.putExtra("GAME_URL", "https://floppybird-bc843.web.app/") // URL for Flappy Bird
            startActivity(intent)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
} 
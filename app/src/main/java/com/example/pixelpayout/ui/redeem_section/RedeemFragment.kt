package com.example.pixelpayout.ui.redeem_section

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.example.pixelpayout.data.api.RedeemOption
import com.example.pixelpayout.utils.SpacingItemDecoration
import com.pixelpayout.databinding.FragmentRedeemBinding
import com.pixelpayout.R
import com.tapjoy.*

class RedeemFragment : Fragment() {
    private var _binding: FragmentRedeemBinding? = null
    private val binding get() = _binding!!
    private lateinit var redeemAdapter: RedeemAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRedeemBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        setupRecyclerView()

        val fakeList = listOf(
            RedeemOption("100 UC", 100, R.drawable.ic_game),
            RedeemOption("500 pkr", 200, R.drawable.ic_google),
            RedeemOption("300 CP", 300, R.drawable.quiz_ui),
            RedeemOption("300 CP", 300, R.drawable.quiz_ui)

        )
        binding.recyclerViewRedeem.adapter = RedeemAdapter(fakeList) { redeemOption ->
            Toast.makeText(requireContext(), "Clicked on ${redeemOption.title}", Toast.LENGTH_SHORT).show()

        }
    }
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
    private fun setupRecyclerView() {
        redeemAdapter = RedeemAdapter(emptyList()) { redeemOption ->
            // Handle item click here
            Toast.makeText(requireContext(), "Clicked on ${redeemOption.title}", Toast.LENGTH_SHORT).show()
        }

        binding.recyclerViewRedeem.apply {
            adapter = redeemAdapter
            layoutManager = StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL)
            addItemDecoration(SpacingItemDecoration(0))
        }

    }
}

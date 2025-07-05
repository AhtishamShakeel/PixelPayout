package com.example.pixelpayout.ui.redeem_section

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.example.pixelpayout.utils.SpacingItemDecoration
import com.example.pixelpayout.utils.UserPreferences
import com.pixelpayout.databinding.FragmentRedeemBinding


class RedeemFragment : Fragment() {

    private var _binding: FragmentRedeemBinding? = null
    private val binding get() = _binding!!
    private lateinit var redeemAdapter: RedeemAdapter
    /*private lateinit var viewModel: RedeemViewModel*/

    private val viewModel: RedeemViewModel by activityViewModels()


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRedeemBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setUpSwipeRefresh()

        val userPrefs = UserPreferences(requireContext())
        viewModel.loadRedeemOptionsWithCache(userPrefs)

        viewModel.redeemList.observe(viewLifecycleOwner) { list ->
            redeemAdapter = RedeemAdapter(list) { selected ->
                Toast.makeText(requireContext(), "Clicked on ${selected.title}", Toast.LENGTH_SHORT).show()
            }
            binding.recyclerViewRedeem.adapter = redeemAdapter
            binding.swipeRefreshRedeem.isRefreshing = false
        }
    }
    private fun setupRecyclerView() {
        redeemAdapter = RedeemAdapter(emptyList()) { selected ->
            // Handle item click here
            Toast.makeText(requireContext(), "Clicked on ${selected.title}", Toast.LENGTH_SHORT).show()
        }

        binding.recyclerViewRedeem.apply {
            adapter = redeemAdapter
            layoutManager = StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL)
            addItemDecoration(SpacingItemDecoration(0))
        }

    }

    private fun setUpSwipeRefresh(){
        binding.swipeRefreshRedeem.setOnRefreshListener {
            val userPrefs = UserPreferences(requireContext())
            viewModel.forceRefresh(userPrefs)
        }
    }



    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

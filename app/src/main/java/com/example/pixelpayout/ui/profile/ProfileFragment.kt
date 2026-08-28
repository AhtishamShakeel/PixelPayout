package com.example.pixelpayout.ui.profile

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.pixelpayout.databinding.FragmentProfileBinding

/**
 * Placeholder for the fifth tab.
 *
 * The bottom bar has been a five-up grid since the nav redesign, but Profile
 * had no destination, so MainActivity refused the tab outright - tapping it
 * did nothing, with no way for the user to tell that from a hang. An empty
 * screen that says so is the smaller lie, and it lets the bar behave
 * consistently across all five tabs.
 *
 * Nothing here is real yet. When there is a profile to show, this stops being
 * a stub rather than being replaced.
 */
class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

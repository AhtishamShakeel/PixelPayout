package com.example.pixelpayout.ui.help

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import com.example.pixelpayout.data.repository.SupportTicketStore
import com.google.android.material.chip.Chip
import com.google.firebase.auth.FirebaseAuth
import com.pixelpayout.BuildConfig
import com.pixelpayout.R
import com.pixelpayout.databinding.FragmentSupportNewBinding
import kotlinx.coroutines.launch

/**
 * Contact support: pick a category, optionally the order it is about, and
 * describe the problem. The server enforces every limit; the checks here only
 * save a round trip.
 */
class SupportNewTicketFragment : Fragment() {

    private var _binding: FragmentSupportNewBinding? = null
    private val binding get() = _binding!!

    private var selectedCategory: String? = null
    private var selectedOrderId: String? = null
    private var ordersLoaded = false
    private var sending = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSupportNewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.header.supportTitle.setText(R.string.support_new_title)
        binding.header.supportSubtitle.setText(R.string.support_new_subtitle)
        binding.header.supportBack.setOnClickListener { findNavController().popBackStack() }

        SupportUi.categories.forEach { (id, label) ->
            val chip = layoutInflater.inflate(R.layout.item_server_chip, binding.supportCategories, false) as Chip
            chip.id = View.generateViewId()
            chip.setText(label)
            chip.tag = id
            binding.supportCategories.addView(chip)
        }
        binding.supportCategories.setOnCheckedStateChangeListener { group, checkedIds ->
            selectedCategory = checkedIds.firstOrNull()?.let { group.findViewById<Chip>(it)?.tag as? String }
            binding.supportError.text = ""
            val aboutOrder = selectedCategory == "redemption"
            binding.supportOrderGroup.isVisible = aboutOrder
            if (!aboutOrder) selectedOrderId = null
            if (aboutOrder) loadOrders()
        }

        updateCounter()
        binding.supportMessage.doAfterTextChanged {
            updateCounter()
            binding.supportError.text = ""
        }
        binding.supportSubmit.setOnClickListener { submit() }
    }

    override fun onResume() {
        super.onResume()
        SupportUi.enterComposeMode(this)
    }

    override fun onPause() {
        super.onPause()
        SupportUi.exitComposeMode(this)
    }

    private fun updateCounter() {
        binding.supportCounter.text = getString(
            R.string.support_message_counter, binding.supportMessage.length(), MESSAGE_MAX
        )
    }

    /** The caller's recent orders, fetched once, the first time they're needed. */
    private fun loadOrders() {
        if (ordersLoaded) return
        ordersLoaded = true
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        binding.supportOrdersLoading.isVisible = true

        viewLifecycleOwner.lifecycleScope.launch {
            val orders = SupportTicketStore.recentOrders(uid)
            val b = _binding ?: return@launch
            b.supportOrdersLoading.isVisible = false
            val group = b.supportOrders

            val none = addOrderChip(group, getString(R.string.support_order_none), null)
            none.isChecked = true
            orders.forEach { addOrderChip(group, it.label, it.id) }
            group.setOnCheckedStateChangeListener { g, ids ->
                selectedOrderId = ids.firstOrNull()?.let { g.findViewById<Chip>(it)?.tag as? String }
            }
        }
    }

    private fun addOrderChip(group: ViewGroup, label: String, orderId: String?): Chip {
        val chip = layoutInflater.inflate(R.layout.item_server_chip, group, false) as Chip
        chip.id = View.generateViewId()
        chip.text = label
        chip.tag = orderId
        group.addView(chip)
        return chip
    }

    private fun submit() {
        if (sending) return
        val category = selectedCategory
        val message = binding.supportMessage.text.toString().trim()
        when {
            category == null -> {
                binding.supportError.setText(R.string.support_error_category)
                return
            }
            message.length < MESSAGE_MIN -> {
                binding.supportError.setText(R.string.support_error_short)
                return
            }
        }

        setSending(true)
        val activity = requireActivity()
        activity.lifecycleScope.launch {
            val result = SupportTicketStore.create(category!!, message, selectedOrderId, BuildConfig.VERSION_NAME)
            if (_binding == null) return@launch
            setSending(false)
            when (result) {
                is SupportTicketStore.Result.Ok -> result.ticketId?.let { openThread(it) }
                is SupportTicketStore.Result.ActiveTicketExists -> {
                    binding.supportError.setText(R.string.support_error_active)
                    result.ticketId?.let { openThread(it) }
                }
                SupportTicketStore.Result.DailyLimit ->
                    binding.supportError.setText(R.string.support_error_daily)
                else -> binding.supportError.setText(R.string.support_error_generic)
            }
        }
    }

    /** Replaces this form with the ticket, so Back returns to Help. */
    private fun openThread(ticketId: String) {
        val controller = findNavController()
        if (controller.currentDestination?.id != R.id.supportNewTicketFragment) return
        controller.navigate(
            R.id.supportThreadFragment,
            bundleOf(SupportThreadFragment.ARG_TICKET_ID to ticketId),
            NavOptions.Builder().setPopUpTo(R.id.supportNewTicketFragment, true).build()
        )
    }

    private fun setSending(value: Boolean) {
        sending = value
        binding.supportSubmit.isEnabled = !value
        binding.supportSubmit.alpha = if (value) 0.6f else 1f
        binding.supportSubmitLabel.setText(if (value) R.string.support_sending else R.string.support_submit)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        const val MESSAGE_MIN = 10
        const val MESSAGE_MAX = 1000
    }
}

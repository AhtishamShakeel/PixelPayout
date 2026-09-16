package com.example.pixelpayout.ui.help

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.pixelpayout.data.repository.SupportTicketStore
import com.pixelpayout.R
import com.pixelpayout.databinding.FragmentSupportTicketsBinding
import com.pixelpayout.databinding.ItemSupportTicketBinding

/** Every ticket this account has opened, from [SupportTicketStore]. No extra reads. */
class SupportTicketsFragment : Fragment() {

    private var _binding: FragmentSupportTicketsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSupportTicketsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.header.supportTitle.setText(R.string.support_tickets_title)
        binding.header.supportSubtitle.isVisible = false
        binding.header.supportBack.setOnClickListener { findNavController().popBackStack() }

        SupportTicketStore.tickets.observe(viewLifecycleOwner) { render(it) }
    }

    private fun render(tickets: List<SupportTicketStore.Ticket>) {
        val b = _binding ?: return
        b.ticketsEmpty.isVisible = tickets.isEmpty()
        b.ticketsList.removeAllViews()

        tickets.forEach { ticket ->
            val row = ItemSupportTicketBinding.inflate(layoutInflater, b.ticketsList, false)
            SupportUi.bindStatus(row.ticketStatus, ticket.status)
            row.ticketCategory.text = buildString {
                append(SupportUi.categoryLabel(requireContext(), ticket.category))
                if (ticket.orderLabel.isNotBlank()) append(" · ").append(ticket.orderLabel)
            }
            row.ticketUnread.isVisible = ticket.userUnread
            row.ticketTime.text = SupportUi.relativeTime(requireContext(), ticket.lastMessageAtMillis)
            row.ticketSubject.text = ticket.subject
            row.ticketPreview.text = if (ticket.lastMessageFrom == "admin") {
                "${getString(R.string.support_agent)}: ${ticket.lastMessagePreview}"
            } else {
                "${getString(R.string.support_you)}: ${ticket.lastMessagePreview}"
            }
            row.ticketCard.setOnClickListener { openThread(ticket.id) }
            b.ticketsList.addView(row.root)
        }
    }

    private fun openThread(ticketId: String) {
        val controller = findNavController()
        if (controller.currentDestination?.id != R.id.supportTicketsFragment) return
        controller.navigate(R.id.supportThreadFragment, bundleOf(SupportThreadFragment.ARG_TICKET_ID to ticketId))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

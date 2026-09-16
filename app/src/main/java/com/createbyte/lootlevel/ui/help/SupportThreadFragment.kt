package com.createbyte.lootlevel.ui.help

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.createbyte.lootlevel.data.repository.SupportTicketStore
import com.createbyte.lootlevel.utils.showAppDialog
import com.google.firebase.firestore.ListenerRegistration
import com.createbyte.lootlevel.R
import com.createbyte.lootlevel.databinding.FragmentSupportThreadBinding
import com.createbyte.lootlevel.databinding.ItemSupportMessageBinding
import kotlinx.coroutines.launch

/**
 * One ticket's conversation, live. Opening it clears the "support replied"
 * dot; replying and closing go through the ticket callables.
 */
class SupportThreadFragment : Fragment() {

    private var _binding: FragmentSupportThreadBinding? = null
    private val binding get() = _binding!!

    private val adapter = MessageAdapter()
    private var messages: ListenerRegistration? = null
    private var sending = false
    private lateinit var ticketId: String

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSupportThreadBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ticketId = requireArguments().getString(ARG_TICKET_ID).orEmpty()

        binding.header.supportBack.setOnClickListener { findNavController().popBackStack() }
        binding.header.supportTitle.setText(R.string.support_new_title)
        binding.header.supportSubtitle.isVisible = false

        binding.threadMessages.layoutManager = LinearLayoutManager(requireContext()).apply { stackFromEnd = true }
        binding.threadMessages.adapter = adapter

        binding.threadSend.setOnClickListener { send() }
        binding.threadClose.setOnClickListener { confirmClose() }

        SupportTicketStore.tickets.observe(viewLifecycleOwner) { renderTicket() }

        messages = SupportTicketStore.listenToMessages(ticketId) { list ->
            val b = _binding ?: return@listenToMessages
            adapter.submit(list)
            if (list.isNotEmpty()) b.threadMessages.scrollToPosition(list.size - 1)
            SupportTicketStore.markRead(ticketId)
        }
    }

    override fun onResume() {
        super.onResume()
        SupportUi.enterComposeMode(this)
    }

    override fun onPause() {
        super.onPause()
        SupportUi.exitComposeMode(this)
    }

    private fun renderTicket() {
        val b = _binding ?: return
        val ticket = SupportTicketStore.ticket(ticketId) ?: return

        b.header.supportTitle.text = SupportUi.categoryLabel(requireContext(), ticket.category)
        SupportUi.bindStatus(b.threadStatus, ticket.status)
        b.threadOrder.text = ticket.orderLabel

        val closed = !ticket.isActive
        val waiting = ticket.awaitingSupport
        b.threadClose.isVisible = !closed
        // Two messages in a row: the reply box goes until support answers.
        b.threadReplyBar.isVisible = !closed && !waiting
        b.threadNote.isVisible = closed || ticket.status == SupportTicketStore.STATUS_OPEN
        b.threadNote.setText(
            when {
                closed -> R.string.support_closed_note
                waiting -> R.string.support_limit_note
                else -> R.string.support_waiting_note
            }
        )
        SupportTicketStore.markRead(ticketId)
    }

    private fun send() {
        if (sending) return
        val text = binding.threadInput.text.toString().trim()
        if (text.length < 10) {
            Toast.makeText(requireContext(), R.string.support_error_short, Toast.LENGTH_SHORT).show()
            return
        }
        setSending(true)
        requireActivity().lifecycleScope.launch {
            val result = SupportTicketStore.reply(ticketId, text)
            val b = _binding ?: return@launch
            setSending(false)
            when (result) {
                is SupportTicketStore.Result.Ok -> b.threadInput.text?.clear()
                SupportTicketStore.Result.DailyLimit ->
                    Toast.makeText(requireContext(), R.string.support_error_daily, Toast.LENGTH_LONG).show()
                SupportTicketStore.Result.AwaitingSupport ->
                    Toast.makeText(requireContext(), R.string.support_limit_note, Toast.LENGTH_LONG).show()
                else -> Toast.makeText(requireContext(), R.string.support_error_generic, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun confirmClose() {
        requireContext().showAppDialog(
            title = R.string.support_close_confirm_title,
            message = R.string.support_close_confirm_message,
            positiveText = R.string.support_close_ticket,
            negativeText = R.string.cancel,
            onPositive = {
                requireActivity().lifecycleScope.launch {
                    val result = SupportTicketStore.close(ticketId)
                    if (result !is SupportTicketStore.Result.Ok && isAdded) {
                        Toast.makeText(requireContext(), R.string.support_error_generic, Toast.LENGTH_LONG).show()
                    }
                }
            }
        )
    }

    private fun setSending(value: Boolean) {
        sending = value
        binding.threadSend.isEnabled = !value
        binding.threadSendIcon.isVisible = !value
        binding.threadSending.isVisible = value
    }

    override fun onDestroyView() {
        messages?.remove()
        messages = null
        super.onDestroyView()
        _binding = null
    }

    private class MessageAdapter : RecyclerView.Adapter<MessageAdapter.Holder>() {
        private var items: List<SupportTicketStore.Message> = emptyList()

        fun submit(list: List<SupportTicketStore.Message>) {
            items = list
            notifyDataSetChanged()
        }

        class Holder(val binding: ItemSupportMessageBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemSupportMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val m = items[position]
            val b = holder.binding
            val context = b.root.context
            val gravity = if (m.fromSupport) Gravity.START else Gravity.END

            b.messageRow.gravity = gravity
            b.messageSender.isVisible = m.fromSupport
            b.messageSender.setText(R.string.support_agent)
            b.messageText.text = m.text
            b.messageText.setBackgroundResource(
                if (m.fromSupport) R.drawable.bg_bubble_support else R.drawable.bg_bubble_user
            )
            b.messageText.setTextColor(
                ContextCompat.getColor(context, if (m.fromSupport) R.color.text_strong else R.color.white)
            )
            b.messageTime.text = SupportUi.messageTime(m.createdAtMillis)
            (b.messageText.layoutParams as LinearLayout.LayoutParams).gravity = gravity
            (b.messageTime.layoutParams as LinearLayout.LayoutParams).gravity = gravity
        }
    }

    companion object {
        const val ARG_TICKET_ID = "ticketId"
    }
}

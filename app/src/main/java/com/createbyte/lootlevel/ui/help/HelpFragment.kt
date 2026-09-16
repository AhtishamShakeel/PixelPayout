package com.createbyte.lootlevel.ui.help

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.createbyte.lootlevel.ui.legal.LegalActivity
import com.createbyte.lootlevel.utils.setStarText
import com.createbyte.lootlevel.data.repository.SupportTicketStore
import androidx.core.os.bundleOf
import com.createbyte.lootlevel.R
import com.createbyte.lootlevel.databinding.FragmentHelpBinding
import com.createbyte.lootlevel.databinding.ItemFaqBinding

/**
 * Help & Support: frequently asked questions, then a way to reach a person.
 *
 * The questions are a static list of string pairs - no network, nothing to
 * load. "Contact support" opens a ticket (SupportNewTicketFragment), or the
 * open ticket if there already is one - one conversation at a time.
 */
class HelpFragment : Fragment() {

    private var _binding: FragmentHelpBinding? = null
    private val binding get() = _binding!!

    private data class Faq(val question: Int, val answer: Int)
    private data class Section(val title: Int, val items: List<Faq>)

    private val sections = listOf(
        Section(R.string.help_section_earning, listOf(
            Faq(R.string.faq_earn_q, R.string.faq_earn_a),
            Faq(R.string.faq_xp_q, R.string.faq_xp_a),
            Faq(R.string.faq_limit_q, R.string.faq_limit_a),
            Faq(R.string.faq_ad_q, R.string.faq_ad_a),
            Faq(R.string.faq_offer_q, R.string.faq_offer_a)
        )),
        Section(R.string.help_section_redeeming, listOf(
            Faq(R.string.faq_redeem_q, R.string.faq_redeem_a),
            Faq(R.string.faq_redeem_time_q, R.string.faq_redeem_time_a),
            Faq(R.string.faq_wrong_id_q, R.string.faq_wrong_id_a),
            Faq(R.string.faq_declined_q, R.string.faq_declined_a),
            Faq(R.string.faq_first_q, R.string.faq_first_a)
        )),
        Section(R.string.help_section_levels, listOf(
            Faq(R.string.faq_level_q, R.string.faq_level_a),
            Faq(R.string.faq_tournament_q, R.string.faq_tournament_a)
        )),
        Section(R.string.help_section_referrals, listOf(
            Faq(R.string.faq_referral_q, R.string.faq_referral_a),
            Faq(R.string.faq_referral_code_q, R.string.faq_referral_code_a)
        )),
        Section(R.string.help_section_account, listOf(
            Faq(R.string.faq_multi_q, R.string.faq_multi_a),
            Faq(R.string.faq_vpn_q, R.string.faq_vpn_a),
            Faq(R.string.faq_banned_q, R.string.faq_banned_a),
            Faq(R.string.faq_delete_q, R.string.faq_delete_a)
        ))
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHelpBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.helpBack.setOnClickListener { findNavController().popBackStack() }
        buildSections()

        binding.helpContactButton.setOnClickListener { open(R.id.supportNewTicketFragment) }
        binding.helpMyTickets.setOnClickListener { open(R.id.supportTicketsFragment) }
        SupportTicketStore.tickets.observe(viewLifecycleOwner) { tickets ->
            val b = _binding ?: return@observe
            val active = tickets.firstOrNull { it.isActive }
            b.helpContactLabel.setText(
                if (active != null) R.string.help_view_open_ticket else R.string.help_contact_button
            )
            b.helpContactButton.setOnClickListener {
                if (active != null) openThread(active.id) else open(R.id.supportNewTicketFragment)
            }
            b.helpMyTickets.isVisible = tickets.isNotEmpty()
            b.helpMyTicketsLabel.text = resources.getQuantityString(
                R.plurals.help_my_tickets_count, tickets.size, tickets.size
            )
            b.helpMyTicketsReply.isVisible = tickets.any { it.userUnread }
        }

        binding.helpTerms.setOnClickListener {
            LegalActivity.open(requireContext(), LegalActivity.Doc.TERMS)
        }
        binding.helpPrivacy.setOnClickListener {
            LegalActivity.open(requireContext(), LegalActivity.Doc.PRIVACY)
        }
    }

    private fun buildSections() {
        val inflater = layoutInflater
        val container = binding.helpSections
        container.removeAllViews()

        sections.forEach { section ->
            container.addView(sectionHeader(section.title))
            section.items.forEach { faq ->
                val item = ItemFaqBinding.inflate(inflater, container, false)
                item.faqQuestion.setText(faq.question)
                // Every "50 ★" in an answer is a gold figure with the real star.
                item.faqAnswer.setStarText(getString(faq.answer), figureColor = R.color.stars_accent, centerStars = true)
                item.faqCard.setOnClickListener {
                    val open = !item.faqAnswer.isVisible
                    item.faqAnswer.isVisible = open
                    item.faqChevron.animate().rotation(if (open) 270f else 90f).setDuration(150).start()
                }
                container.addView(item.root)
            }
        }
    }

    private fun sectionHeader(title: Int): TextView =
        TextView(requireContext()).apply {
            setText(title)
            isAllCaps = true
            letterSpacing = 0.14f
            textSize = 11f
            typeface = ResourcesCompat.getFont(requireContext(), R.font.jakarta_semibold)
            setTextColor(requireContext().getColor(R.color.brand_violet_light))
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (22 * resources.displayMetrics.density).toInt() }
        }

    private fun open(destination: Int, args: Bundle? = null) {
        val controller = findNavController()
        if (controller.currentDestination?.id != R.id.helpFragment) return
        controller.navigate(destination, args)
    }

    private fun openThread(ticketId: String) =
        open(R.id.supportThreadFragment, bundleOf(SupportThreadFragment.ARG_TICKET_ID to ticketId))

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

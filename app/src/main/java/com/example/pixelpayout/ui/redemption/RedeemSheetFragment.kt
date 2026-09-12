package com.example.pixelpayout.ui.redemption

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import com.example.pixelpayout.data.model.RedemptionGame
import com.example.pixelpayout.data.model.RedemptionPack
import com.example.pixelpayout.data.repository.UserRepository
import com.example.pixelpayout.ui.main.MainViewModel
import com.example.pixelpayout.utils.GridSpacingItemDecoration
import com.example.pixelpayout.utils.setStarText
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import com.pixelpayout.R
import com.pixelpayout.databinding.ItemSummaryRowBinding
import com.pixelpayout.databinding.SheetRedeemBinding
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The redeem flow: pick a pack, say where it goes, confirm, done.
 *
 * One sheet with four steps rather than four screens. They are always entered
 * in order, share a dismiss, and the whole thing is discarded when it closes -
 * so there is no state that would survive a fragment transaction anyway.
 *
 * The first-redeem offer opens the SAME sheet with [ARG_FIRST_REDEEM] set:
 * the pack list is filtered to the discounted ones and priced at the
 * discount. It is the same purchase at a different price, so making it a
 * separate flow would mean maintaining the ID-entry and confirmation steps
 * twice.
 *
 * Nothing here decides what anything costs. The price shown comes from the
 * catalogue and the price charged is read again by the server, which also
 * re-checks the level gate, the balance, and whether this player ID already
 * belongs to somebody else.
 */
class RedeemSheetFragment : BottomSheetDialogFragment() {

    private var _binding: SheetRedeemBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ReferralViewModel by activityViewModels {
        ReferralViewModelFactory(UserRepository())
    }
    private val mainViewModel: MainViewModel by activityViewModels()

    private lateinit var packAdapter: PackAdapter
    private lateinit var giftAdapter: GiftAdapter

    private var game: RedemptionGame? = null
    private var selectedPack: RedemptionPack? = null
    private var selectedServer: String = ""
    private var isFirstRedeem: Boolean = false
    private var balance: Int = 0

    override fun getTheme(): Int = R.style.Theme_PixelPayout_BottomSheet

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SheetRedeemBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        @Suppress("DEPRECATION")
        game = arguments?.getParcelable(ARG_GAME)
        isFirstRedeem = arguments?.getBoolean(ARG_FIRST_REDEEM) == true

        setupActions()
        observeViewModel()

        if (isFirstRedeem) {
            // The offer spans the catalogue, so there is no game yet - the
            // picker chooses one. Everything game-specific is bound in
            // bindGame() once that choice is made.
            setupGiftPicker()
            showStep(Step.GIFT)
            return
        }

        val game = this.game ?: run { dismissAllowingStateLoss(); return }
        bindGame(game)
        showStep(Step.AMOUNT)
    }

    /**
     * Everything that depends on WHICH game is being redeemed.
     *
     * Split out because the first-redeem flow does not know the game until
     * the picker has been used, while the normal flow knows it from the
     * moment the sheet opens.
     */
    private fun bindGame(game: RedemptionGame) {
        this.game = game
        setupHeader(game)
        setupPacks(game)
        setupDetails(game)
        prefillFromLastTime(game)
    }

    /**
     * Builds the cross-game offer grid: every discounted pack in the
     * catalogue, whatever game it belongs to.
     */
    private fun setupGiftPicker() {
        val offers = viewModel.games.value.orEmpty()
            .flatMap { g -> g.packs.filter { it.isFirstRedeemOffer }.map { GiftOffer(g, it) } }
            .sortedBy { it.pack.firstRedeemCost ?: it.pack.pointsCost }

        if (offers.isEmpty()) {
            dismissAllowingStateLoss()
            return
        }

        binding.giftSubtitle.text = getString(R.string.first_redeem_sheet_sub)

        giftAdapter = GiftAdapter { offer ->
            // Only an affordable cell is clickable, so reaching here means the
            // choice is payable.
            if (!binding.stepGift.isVisible) return@GiftAdapter
            selectedPack = offer.pack
            bindGame(offer.game)
            showStep(Step.DETAILS)
        }
        binding.giftRecyclerView.adapter = giftAdapter
        binding.giftRecyclerView.layoutManager = GridLayoutManager(requireContext(), GIFT_SPAN)
        binding.giftRecyclerView.addItemDecoration(
            GridSpacingItemDecoration(
                GIFT_SPAN,
                (GIFT_SPACING_DP * resources.displayMetrics.density).roundToInt()
            )
        )
        binding.giftRecyclerView.isNestedScrollingEnabled = false
        giftAdapter.submitList(offers)
        giftAdapter.updateBalance(balance)

    }

    private fun setupHeader(game: RedemptionGame) {
        // The currency, not the game. Same rule as the tiles - see
        // RedemptionGame.currencyName for why the game name does not go in
        // front of a user.
        binding.sheetGameCode.text = game.code
        val artwork = game.imageUrl?.takeIf { it.isNotBlank() }
        binding.sheetGameImage.isVisible = artwork != null
        // Reuse the card's URL and Coil cache; the code remains underneath
        // while loading, or if Firebase artwork is unavailable.
        binding.sheetGameImage.load(artwork) { crossfade(true) }
        binding.sheetGameName.text = game.displayName
        binding.sheetGameSub.text = game.subtitle
        binding.sheetGameSub.isVisible = game.subtitle.isNotBlank()
    }

    /**
     * The per-game pack list. Not used by the first-redeem flow at all - that
     * one picks its pack in the cross-game grid before a game even exists.
     */
    private fun setupPacks(game: RedemptionGame) {
        packAdapter = PackAdapter(discounted = false) { pack ->
            selectedPack = pack
            showStep(Step.DETAILS)
        }
        binding.packsRecyclerView.adapter = packAdapter
        binding.packsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.packsRecyclerView.isNestedScrollingEnabled = false
        // The buyable ones only. The first-redeem taster is reachable through
        // the offer and nowhere else, so listing it here would offer a price
        // the server refuses.
        packAdapter.submitList(game.purchasablePacks)
        packAdapter.updateBalance(balance)
    }

    private fun setupDetails(game: RedemptionGame) {
        binding.playerIdLabel.text = game.idLabel
        binding.detailsHint.text = game.idHint
        binding.detailsHint.isVisible = game.idHint.isNotBlank()

        binding.usernameGroup.isVisible = game.requiresUsername
        binding.usernameLabel.text = game.usernameLabel

        binding.serverGroup.isVisible = game.servers.isNotEmpty()
        binding.serverChips.removeAllViews()
        binding.serverChips.isSingleSelection = true

        game.servers.forEach { name ->
            val chip = layoutInflater.inflate(
                R.layout.item_server_chip, binding.serverChips, false
            ) as Chip
            // A unique id per chip, because every one of these is inflated
            // from the same layout and so arrives carrying the SAME id.
            // ChipGroup tracks its single selection by view id, so identical
            // ids leave it unable to tell the chips apart - it cannot uncheck
            // the previous one, and every chip stays checked.
            chip.id = View.generateViewId()
            chip.text = name
            chip.setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    selectedServer = name
                    updateContinueState()
                }
            }
            // Added BEFORE anything is checked: a ChipGroup enforces single
            // selection over its children, so a chip checked while still
            // detached is not part of that bookkeeping and the group can end
            // up with two chips checked, or none.
            binding.serverChips.addView(chip)
        }

        // Defaulting to the first server matches the handoff, but the server
        // still validates the choice against the game's own list - a region
        // is where the payout physically goes.
        (binding.serverChips.getChildAt(0) as? Chip)?.isChecked = true
        selectedServer = game.servers.firstOrNull().orEmpty()

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) = updateContinueState()
        }
        binding.playerIdInput.addTextChangedListener(watcher)
        binding.usernameInput.addTextChangedListener(watcher)
    }

    /**
     * Prefills what this user entered for this game last time.
     *
     * From their own gameProfiles document, not from the playerLinks
     * collection that enforces one-account-per-ID - that one is closed to
     * every client precisely so nobody can ask it who owns what.
     */
    private fun prefillFromLastTime(game: RedemptionGame) {
        viewLifecycleOwner.lifecycleScope.launch {
            val profile = viewModel.gameProfile(game.id) ?: return@launch
            val b = _binding ?: return@launch

            if (b.playerIdInput.text.isNullOrBlank()) b.playerIdInput.setText(profile.playerId)
            if (b.usernameInput.text.isNullOrBlank()) b.usernameInput.setText(profile.username)

            if (profile.server.isNotBlank()) {
                for (i in 0 until b.serverChips.childCount) {
                    val chip = b.serverChips.getChildAt(i) as? Chip ?: continue
                    if (chip.text.toString() == profile.server) {
                        chip.isChecked = true
                        selectedServer = profile.server
                    }
                }
            }
            updateContinueState()
        }
    }

    private fun setupActions() {
        binding.giftClose.setOnClickListener { dismiss() }

        // Back from the ID step returns to whichever list opened it.
        binding.detailsBack.setOnClickListener {
            showStep(if (isFirstRedeem) Step.GIFT else Step.AMOUNT)
        }
        binding.confirmBack.setOnClickListener { showStep(Step.DETAILS) }

        binding.detailsContinue.setOnClickListener {
            if (!detailsValid()) return@setOnClickListener
            buildSummary()
            showStep(Step.CONFIRM)
        }

        binding.placeOrderButton.setOnClickListener {
            val game = game ?: return@setOnClickListener
            val pack = selectedPack ?: return@setOnClickListener

            viewModel.redeem(
                game = game,
                pack = pack,
                playerId = binding.playerIdInput.text.toString().trim(),
                username = binding.usernameInput.text.toString().trim(),
                server = selectedServer,
                useFirstRedeem = isFirstRedeem
            )
        }

        binding.sheetClose.setOnClickListener { dismiss() }
        binding.successDone.setOnClickListener { dismiss() }
        binding.successTrack.setOnClickListener {
            setFragmentResultTrackOrders()
            dismiss()
        }
        binding.orderIdChip.setOnClickListener { copyOrderId() }
    }

    private fun detailsValid(): Boolean {
        val game = game ?: return false
        val id = binding.playerIdInput.text.toString().trim()

        if (id.length < game.idMinLength) {
            binding.playerIdInput.error = getString(R.string.sheet_player_id_hint)
            return false
        }
        if (game.requiresUsername && binding.usernameInput.text.toString().trim().length < 2) {
            binding.usernameInput.error = getString(R.string.sheet_username_hint)
            return false
        }
        return true
    }

    private fun updateContinueState() {
        val game = game ?: return
        val idOk = binding.playerIdInput.text.toString().trim().length >= game.idMinLength
        val nameOk = !game.requiresUsername ||
            binding.usernameInput.text.toString().trim().length >= 2

        binding.detailsContinue.isEnabled = idOk && nameOk
        binding.detailsContinue.alpha = if (idOk && nameOk) 1f else 0.45f
    }

    /**
     * The confirmation rows.
     *
     * Built as views rather than a RecyclerView: it is at most six rows, they
     * never change while on screen, and a list adapter for a fixed summary is
     * more machinery than the step is worth.
     */
    private fun buildSummary() {
        val game = game ?: return
        val pack = selectedPack ?: return
        val cost = priceOf(pack)

        val rows = buildList {
            // No "Game" row. It used to name the game, which no longer goes in
            // front of a user, and the only thing left to put there would be
            // the currency - which the Item row above already carries, as part
            // of "30 UC". A row repeating half of the row above it is not a
            // confirmation, it is noise on the screen where somebody is about
            // to spend their balance.
            add(getString(R.string.sheet_summary_item) to pack.amount)
            add(
                getString(R.string.sheet_summary_player_id) to
                    binding.playerIdInput.text.toString().trim()
            )
            if (game.requiresUsername) {
                add(
                    getString(R.string.sheet_summary_username) to
                        binding.usernameInput.text.toString().trim()
                )
            }
            if (game.servers.isNotEmpty()) {
                add(getString(R.string.sheet_summary_server) to selectedServer)
            }
            // Cost is added below rather than here: it is the one row whose
            // value is a Stars figure, so it is drawn rather than printed.
        }

        binding.summaryContainer.removeAllViews()
        rows.forEach { (key, value) ->
            val row = ItemSummaryRowBinding.inflate(
                layoutInflater, binding.summaryContainer, false
            )
            row.summaryKey.text = key
            row.summaryValue.text = value
            binding.summaryContainer.addView(row.root)
        }

        // The price, in the same Stars colour and with the same real ic_star
        // as the pack rows the user just picked from - see StarText. The
        // whole value takes the colour rather than only the digits, because
        // unlike the captions StarText was written for, there is no sentence
        // around it to keep readable: the value IS the figure.
        val costRow = ItemSummaryRowBinding.inflate(
            layoutInflater, binding.summaryContainer, false
        )
        costRow.summaryKey.text = getString(R.string.sheet_summary_cost)
        costRow.summaryValue.setTextColor(
            ContextCompat.getColor(requireContext(), R.color.stars_accent)
        )
        costRow.summaryValue.setStarText(
            getString(R.string.sheet_summary_cost_value, WalletFormat.number(cost))
        )
        binding.summaryContainer.addView(costRow.root)

        binding.balanceAfter.setStarText(
            getString(
                R.string.sheet_balance_after_value,
                WalletFormat.number((balance - cost).coerceAtLeast(0))
            )
        )
    }

    private fun priceOf(pack: RedemptionPack): Int =
        if (isFirstRedeem) pack.firstRedeemCost ?: pack.pointsCost else pack.pointsCost

    private fun observeViewModel() {
        mainViewModel.userState.observe(viewLifecycleOwner) { state ->
            balance = state.points
            // Each flow uses one of these two, never both, so either may
            // legitimately not exist yet.
            if (::packAdapter.isInitialized) packAdapter.updateBalance(state.points)
            if (::giftAdapter.isInitialized) giftAdapter.updateBalance(state.points)
        }

        viewModel.isRedeeming.observe(viewLifecycleOwner) { busy ->
            binding.confirmProgress.isVisible = busy
            binding.placeOrderButton.isEnabled = !busy
            binding.placeOrderButton.alpha = if (busy) 0.45f else 1f
        }

        viewModel.redemptionResult.observe(viewLifecycleOwner) { result ->
            when (result) {
                is RedemptionResult.Success -> {
                    val pack = selectedPack
                    binding.successLine.text = getString(
                        R.string.sheet_success_line,
                        pack?.amount.orEmpty(),
                        binding.playerIdInput.text.toString().trim()
                    )
                    binding.orderIdChip.text = result.redemptionId.takeLast(ORDER_ID_TAIL).uppercase()
                    showStep(Step.SUCCESS)
                    viewModel.clearRedemptionResult()
                }

                is RedemptionResult.Error -> {
                    viewModel.clearRedemptionResult()

                    // ONE REJECTION IS NOT A CORRECTION. Everything else here
                    // is something the user can act on - a different pack, a
                    // corrected ID - so the sheet stays put rather than
                    // throwing away everything they typed on the way to being
                    // told. This one ends the offer: the game account they
                    // entered has already had a discounted pack, and no other
                    // UID they could type belongs to them. There is nothing
                    // left to fix on this sheet, so it closes and Wallet says
                    // so properly - see RedemptionFragment.showFirstRedeemTaken.
                    //
                    // The card is retired by the server, not here, so a user
                    // who kills the app instead of reading the dialog still
                    // finds the offer gone.
                    if (result.code == CODE_FIRST_REDEEM_TAKEN) {
                        parentFragmentManager.setFragmentResult(
                            RESULT_FIRST_REDEEM_TAKEN, Bundle.EMPTY
                        )
                        dismiss()
                        return@observe
                    }

                    Snackbar.make(binding.root, result.message, Snackbar.LENGTH_LONG).show()
                }

                null -> Unit
            }
        }
    }

    private fun copyOrderId() {
        val id = binding.orderIdChip.text.toString()
        if (id.isBlank()) return

        val clipboard =
            requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("order", id))
        Snackbar.make(binding.root, getString(R.string.sheet_order_copied), Snackbar.LENGTH_SHORT)
            .show()
    }

    private fun setFragmentResultTrackOrders() {
        parentFragmentManager.setFragmentResult(RESULT_TRACK_ORDERS, Bundle.EMPTY)
    }

    private enum class Step { GIFT, AMOUNT, DETAILS, CONFIRM, SUCCESS }

    private fun showStep(step: Step) {
        binding.stepGift.isVisible = step == Step.GIFT
        binding.stepAmount.isVisible = step == Step.AMOUNT
        binding.stepDetails.isVisible = step == Step.DETAILS
        binding.stepConfirm.isVisible = step == Step.CONFIRM
        binding.stepSuccess.isVisible = step == Step.SUCCESS

        if (step == Step.DETAILS) updateContinueState()

        // Once the order is placed there is nothing to go back to and the
        // points are already spent, so the sheet stops being dismissible by
        // swipe - the two explicit buttons are the way out.
        isCancelable = step != Step.SUCCESS
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "RedeemSheet"
        const val RESULT_TRACK_ORDERS = "redeem_track_orders"

        /**
         * Raised when the discount was refused because the GAME ACCOUNT has
         * already had one. Wallet turns it into a dialog; the sheet cannot,
         * because it is closing.
         */
        const val RESULT_FIRST_REDEEM_TAKEN = "redeem_first_taken"

        /** Matches the rejection in economy/redemption.ts. */
        private const val CODE_FIRST_REDEEM_TAKEN = "first_redeem_uid_used"

        private const val ARG_GAME = "game"
        private const val ARG_FIRST_REDEEM = "firstRedeem"

        /** Enough of the id to be quotable in support without being a URL. */
        private const val ORDER_ID_TAIL = 8

        private const val GIFT_SPAN = 2
        private const val GIFT_SPACING_DP = 10

        fun newInstance(game: RedemptionGame) =
            RedeemSheetFragment().apply {
                arguments = Bundle().apply { putParcelable(ARG_GAME, game) }
            }

        /**
         * The first-redeem offer. Takes no game: the picker inside the sheet
         * chooses one from every discounted pack in the catalogue.
         */
        fun newInstanceFirstRedeem() =
            RedeemSheetFragment().apply {
                arguments = Bundle().apply { putBoolean(ARG_FIRST_REDEEM, true) }
            }
    }
}

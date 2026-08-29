package com.example.pixelpayout.ui.redemption

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.pixelpayout.data.repository.UserRepository
import com.example.pixelpayout.ui.main.MainViewModel
import com.example.pixelpayout.utils.GridSpacingItemDecoration
import com.google.android.material.snackbar.Snackbar
import com.pixelpayout.R
import com.pixelpayout.databinding.FragmentRedemptionBinding

/**
 * Wallet, rebuilt on the gaming-wallet handoff.
 *
 * Two views in one fragment, switched by the segmented control: WALLET
 * (balance, pending, first-redeem offer, game grid, activity) and ORDERS.
 * They share this fragment because they are driven by the same `redemptions`
 * snapshot - two fragments would mean two listeners that can disagree about
 * what is outstanding.
 *
 * Nothing about the catalogue is fetched here. It comes from
 * RedemptionOptionsStore by way of the view model, which is what makes
 * returning to this tab instant and what makes an edit in the Firebase
 * console appear without a restart.
 */
class RedemptionFragment : Fragment() {

    private var _binding: FragmentRedemptionBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ReferralViewModel by activityViewModels {
        ReferralViewModelFactory(UserRepository())
    }
    private val mainViewModel: MainViewModel by activityViewModels()

    private lateinit var gamesAdapter: RedemptionAdapter
    private lateinit var ordersAdapter: OrdersAdapter
    private lateinit var activityAdapter: ActivityAdapter

    /** Cached so the offer card can be shown the moment both facts land. */
    private var hasUsedFirstRedeem: Boolean = true
    private var currentLevel: Int = 1
    private var firstRedeemMinLevel: Int? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRedemptionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupLists()
        setupNavigation()
        observeViewModel()

        viewModel.loadGames()
        viewModel.refreshHistory()

        // Two callers ask to land on Orders rather than Wallet: the sheet
        // after a successful redemption, and the pending card on Home.
        //
        // A FragmentManager holds a result until a listener with a STARTED
        // owner appears, so Home can set it and switch tabs in either order -
        // this fragment picks it up whenever it is actually created.
        parentFragmentManager.setFragmentResultListener(
            RESULT_SHOW_ORDERS,
            viewLifecycleOwner
        ) { _, _ -> showOrders(true) }
        parentFragmentManager.setFragmentResultListener(
            RedeemSheetFragment.RESULT_TRACK_ORDERS,
            viewLifecycleOwner
        ) { _, _ -> showOrders(true) }
    }

    override fun onResume() {
        super.onResume()
        // The ledger has no snapshot to listen to, so it is re-read whenever
        // the tab comes back - which is also when play could have moved it.
        viewModel.refreshHistory()
    }

    private fun setupLists() {
        gamesAdapter = RedemptionAdapter { game ->
            RedeemSheetFragment.newInstance(game)
                .show(parentFragmentManager, RedeemSheetFragment.TAG)
        }
        binding.gamesRecyclerView.adapter = gamesAdapter
        binding.gamesRecyclerView.layoutManager = GridLayoutManager(requireContext(), SPAN_COUNT)
        binding.gamesRecyclerView.addItemDecoration(
            GridSpacingItemDecoration(SPAN_COUNT, dp(GUTTER_DP))
        )
        binding.gamesRecyclerView.isNestedScrollingEnabled = false

        activityAdapter = ActivityAdapter()
        binding.activityRecyclerView.adapter = activityAdapter
        binding.activityRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.activityRecyclerView.isNestedScrollingEnabled = false

        ordersAdapter = OrdersAdapter { shortId ->
            val clipboard = requireContext()
                .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("order", shortId))
            Snackbar.make(binding.root, R.string.sheet_order_copied, Snackbar.LENGTH_SHORT).show()
        }
        binding.ordersRecyclerView.adapter = ordersAdapter
        binding.ordersRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.ordersRecyclerView.isNestedScrollingEnabled = false
    }

    private fun setupNavigation() {
        binding.segmentWallet.setOnClickListener { showOrders(false) }
        binding.segmentOrders.setOnClickListener { showOrders(true) }
        binding.walletOrdersButton.setOnClickListener { showOrders(true) }
        binding.walletOrdersShortcut.setOnClickListener { showOrders(true) }
        binding.walletPendingRow.setOnClickListener { showOrders(true) }

        // Earning happens on the other tabs, so this hands the user back to
        // the bottom bar rather than opening anything of its own.
        binding.walletEarnMore.setOnClickListener {
            requireActivity().findViewById<View>(R.id.navigation_play)?.performClick()
        }

        binding.walletHelpButton.setOnClickListener {
            Snackbar.make(binding.root, R.string.sheet_delivery_note, Snackbar.LENGTH_LONG).show()
        }

        binding.firstRedeemButton.setOnClickListener {
            // Disabled while locked, so this can only be a real attempt.
            if (binding.firstRedeemButton.isEnabled) openFirstRedeem()
        }
    }

    private fun showOrders(orders: Boolean) {
        binding.walletView.isVisible = !orders
        binding.ordersView.isVisible = orders

        binding.segmentWallet.setBackgroundResource(
            if (orders) 0 else R.drawable.bg_segment_on
        )
        binding.segmentOrders.setBackgroundResource(
            if (orders) R.drawable.bg_segment_on else 0
        )
        binding.segmentWallet.setTextColor(
            requireContext().getColor(
                if (orders) R.color.text_faint else R.color.brand_violet_light
            )
        )
        binding.segmentOrders.setTextColor(
            requireContext().getColor(
                if (orders) R.color.brand_violet_light else R.color.text_faint
            )
        )

        binding.walletScroll.smoothScrollTo(0, 0)
    }

    /**
     * The first-redeem offer.
     *
     * Opens its own picker rather than a game's pack list: the offer is one
     * choice across the whole catalogue, so it cannot be reached through a
     * game the user has not chosen yet.
     */
    private fun openFirstRedeem() {
        val anyOffer = viewModel.games.value.orEmpty()
            .any { game -> game.packs.any { it.isFirstRedeemOffer } }

        if (!anyOffer) {
            Snackbar.make(binding.root, R.string.wallet_no_rewards, Snackbar.LENGTH_LONG).show()
            return
        }
        RedeemSheetFragment.newInstanceFirstRedeem()
            .show(parentFragmentManager, RedeemSheetFragment.TAG)
    }

    private fun observeViewModel() {
        viewModel.games.observe(viewLifecycleOwner) { games ->
            gamesAdapter.submitList(games)
            binding.walletGamesCount.text = when (games.size) {
                1 -> getString(R.string.wallet_games_count_one)
                else -> getString(R.string.wallet_games_count, games.size)
            }
            binding.walletGamesCount.isVisible = games.isNotEmpty()
            updateCatalogueState()
            updateReachLine()
            updateFirstRedeemCard()
        }

        viewModel.isLoadingGames.observe(viewLifecycleOwner) { updateCatalogueState() }

        mainViewModel.userState.observe(viewLifecycleOwner) { state ->
            currentLevel = state.level
            gamesAdapter.updateLevel(state.level)
            binding.walletBalance.text = WalletFormat.number(state.points)
            updateReachLine()
            updateFirstRedeemCard()
        }

        mainViewModel.hasUsedFirstRedeem.observe(viewLifecycleOwner) { used ->
            hasUsedFirstRedeem = used
            updateFirstRedeemCard()
        }

        viewModel.firstRedeemMinLevel.observe(viewLifecycleOwner) { level ->
            firstRedeemMinLevel = level
            updateFirstRedeemCard()
        }

        mainViewModel.pendingRedemptions.observe(viewLifecycleOwner) { pending ->
            binding.walletPendingRow.isVisible = pending.count > 0
            binding.walletPendingTitle.text = when {
                pending.count > 1 -> getString(R.string.wallet_pending_many, pending.count)
                else -> getString(R.string.wallet_pending_one)
            }
            binding.walletPendingMeta.text = pending.title
            binding.walletPendingMeta.isVisible = pending.title.isNotBlank()
        }

        viewModel.orders.observe(viewLifecycleOwner) { orders ->
            ordersAdapter.submitList(orders)
            binding.ordersEmpty.isVisible = orders.isEmpty()
        }

        viewModel.history.observe(viewLifecycleOwner) { history ->
            activityAdapter.submitList(history.take(ACTIVITY_PREVIEW))
            binding.activityEmpty.isVisible = history.isEmpty()
            binding.activityRecyclerView.isVisible = history.isNotEmpty()
        }

        viewModel.isRedeeming.observe(viewLifecycleOwner) { busy ->
            // The sheet draws its own spinner while it is up; this one only
            // covers a redemption confirmed from somewhere else.
            binding.progressIndicator.isVisible =
                busy && parentFragmentManager.findFragmentByTag(RedeemSheetFragment.TAG) == null
        }
    }

    /**
     * The balance card's second line.
     *
     * The handoff prints a points-to-currency conversion from a hardcoded
     * rate. There is no such rate here - a pack carries a points price and a
     * free-text amount, with nothing machine-readable between them - so this
     * names a real pack instead: the best one the balance already covers, or
     * the shortfall to the cheapest one it does not.
     */
    private fun updateReachLine() {
        val games = viewModel.games.value.orEmpty()
        val points = mainViewModel.userState.value?.points ?: 0

        val available = games
            .filter { it.minLevel <= currentLevel }
            .flatMap { game -> game.packs.map { game to it } }

        if (available.isEmpty()) {
            binding.walletReachLine.isVisible = false
            return
        }
        binding.walletReachLine.isVisible = true

        val affordable = available.filter { (_, pack) -> pack.pointsCost <= points }
        binding.walletReachLine.text = if (affordable.isNotEmpty()) {
            val (game, pack) = affordable.maxBy { (_, pack) -> pack.pointsCost }
            getString(R.string.wallet_reach_enough, pack.amount, game.name)
        } else {
            val (_, pack) = available.minBy { (_, pack) -> pack.pointsCost }
            getString(
                R.string.wallet_reach_short,
                WalletFormat.number(pack.pointsCost - points),
                pack.amount
            )
        }
    }

    /**
     * The offer card.
     *
     * Shown whenever the discount is unspent AND some pack actually carries a
     * discounted price. Below the unlock level it is shown LOCKED rather than
     * hidden: hiding it meant a user under level 10 had no way to learn the
     * offer existed, or what to aim at - and it made the whole feature
     * invisible while testing. An offer you cannot take yet is worth naming;
     * an offer that does not exist is not, which is why an empty catalogue
     * still hides it.
     */
    private fun updateFirstRedeemCard() {
        val minLevel = firstRedeemMinLevel
        val offerExists = viewModel.games.value.orEmpty()
            .any { game -> game.packs.any { it.isFirstRedeemOffer } }

        // Still hidden until the config read lands, so the card never names an
        // unlock level it might have to correct a moment later.
        val show = !hasUsedFirstRedeem && offerExists && minLevel != null
        binding.firstRedeemCard.isVisible = show
        if (!show) return

        val unlocked = currentLevel >= (minLevel ?: Int.MAX_VALUE)

        binding.firstRedeemBody.text = getString(R.string.first_redeem_body)
        binding.firstRedeemButton.isEnabled = unlocked
        binding.firstRedeemButton.alpha = if (unlocked) 1f else 0.5f
        binding.firstRedeemButton.text = if (unlocked) {
            getString(R.string.first_redeem_cta)
        } else {
            getString(R.string.first_redeem_locked, minLevel ?: 0)
        }
    }

    /**
     * "Nothing to buy" and "not asked yet" look identical on screen unless
     * they are told apart, so the empty line waits for the catalogue to have
     * actually answered.
     */
    private fun updateCatalogueState() {
        val loading = viewModel.isLoadingGames.value ?: true
        val empty = viewModel.games.value.isNullOrEmpty()

        binding.walletEmpty.isVisible = empty && !loading
        binding.gamesRecyclerView.isVisible = !empty
        if (viewModel.isRedeeming.value != true) {
            binding.progressIndicator.isVisible = empty && loading
        }
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics
    ).toInt()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        /**
         * Asks this screen to open on Orders. Set by Home's pending card, which
         * cannot call into a fragment that does not exist yet.
         */
        const val RESULT_SHOW_ORDERS = "wallet_show_orders"

        private const val SPAN_COUNT = 2
        private const val GUTTER_DP = 11

        /** The Wallet preview; the full ledger is not paged in here. */
        private const val ACTIVITY_PREVIEW = 6
    }
}

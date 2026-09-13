package com.example.pixelpayout.ui.redemption

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import kotlinx.coroutines.launch
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import com.example.pixelpayout.data.model.RedemptionGame
import com.example.pixelpayout.data.repository.UserRepository
import com.example.pixelpayout.ui.main.MainActivity
import com.example.pixelpayout.ui.main.MainViewModel
import com.example.pixelpayout.utils.GridSpacingItemDecoration
import com.example.pixelpayout.utils.showAppDialog
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

    /**
     * Cached so the offer card can be shown the moment both facts land, and
     * read by the reach line to decide which prices it is measuring.
     *
     * DEFAULTS TO FINISHED on purpose: until the user document has answered,
     * neither surface should advertise a discount that may already be spent.
     * The card stays hidden and the line names the ordinary floor, and both
     * correct themselves on the first emission.
     */
    private var firstRedeemFinished: Boolean = true
    private var currentLevel: Int = 1

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

        // Home's "Redeem 60 UC now". Delivered while this view is only
        // STARTED, and openRedeemSheet refuses anything short of RESUMED, so
        // the open waits for that. Games come from the shared store Home was
        // already reading, so they are here even before this screen's own
        // listener answers.
        parentFragmentManager.setFragmentResultListener(
            RESULT_OPEN_REDEEM,
            viewLifecycleOwner
        ) { _, result ->
            val gameId = result.getString(KEY_GAME_ID)
            val firstRedeem = result.getBoolean(KEY_FIRST_REDEEM)
            viewLifecycleOwner.lifecycleScope.launch {
                viewLifecycleOwner.withResumed {
                    showOrders(false)
                    if (firstRedeem) {
                        openFirstRedeem()
                    } else {
                        mainViewModel.redemptionGames.value.orEmpty()
                            .firstOrNull { it.id == gameId }
                            ?.let { game -> openRedeemSheet { RedeemSheetFragment.newInstance(game) } }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The ledger has no snapshot to listen to, so it is re-read whenever
        // the tab comes back - which is also when play could have moved it.
        viewModel.refreshHistory()
    }

    private fun setupLists() {
        gamesAdapter = RedemptionAdapter { game ->
            openRedeemSheet { RedeemSheetFragment.newInstance(game) }
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
        binding.activityLoadMore.setOnClickListener { viewModel.loadMoreHistory() }

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
        binding.walletPendingRow.setOnClickListener { showOrders(true) }

        // Earn, not Play. This card counts stars, and Play pays XP for games
        // and quizzes - sending somebody who wants a bigger balance to a
        // screen that grows the other currency is the wrong half of the app.
        binding.walletEarnMore.setOnClickListener { navigateToEarn() }

        // No enabled check any more. The offer has no level gate, so the card
        // is either on screen and usable or not on screen at all.
        binding.firstRedeemButton.setOnClickListener { openFirstRedeem() }

        // The refusal that ends the offer. The sheet cannot show this itself -
        // it dismisses on the failure - so it hands the news back here.
        parentFragmentManager.setFragmentResultListener(
            RedeemSheetFragment.RESULT_FIRST_REDEEM_TAKEN,
            viewLifecycleOwner
        ) { _, _ -> showFirstRedeemTaken() }
    }

    /**
     * "The one-time offer has already been used on this game account."
     *
     * A dialog rather than a snackbar, because it is the end of something
     * rather than a correction: there is no other UID they could type that
     * would work, and the card is about to disappear from under them. A line
     * of toast that vanishes in two seconds would leave them wondering where
     * the offer went.
     *
     * The card itself is retired by the server, not here - redeemReward sets
     * firstRedeemUnavailable inside the transaction that refused - so this
     * only has to say so. Nothing is dismissed or hidden by hand, and a user
     * who kills the app before reading it still finds the card gone.
     */
    private fun showFirstRedeemTaken() {
        if (!isAdded) return
        requireContext().showAppDialog(
            title = R.string.first_redeem_taken_title,
            message = R.string.first_redeem_taken_body,
            icon = R.drawable.ic_gift,
            positiveText = R.string.first_redeem_taken_ok
        )
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
                if (orders) R.color.text_faint else R.color.white
            )
        )
        binding.segmentOrders.setTextColor(
            requireContext().getColor(
                if (orders) R.color.white else R.color.text_faint
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
        openRedeemSheet { RedeemSheetFragment.newInstanceFirstRedeem() }
    }

    private fun openRedeemSheet(createSheet: () -> RedeemSheetFragment) {
        if (!isAdded || !isResumed) return
        val manager = parentFragmentManager
        if (manager.isDestroyed || manager.isStateSaved ||
            manager.findFragmentByTag(RedeemSheetFragment.TAG) != null
        ) return

        // show() queues the transaction, leaving a window for another tap to
        // add a second sheet. Register it now so every subsequent callback
        // sees the existing sheet, including one restored after rotation.
        createSheet().showNow(manager, RedeemSheetFragment.TAG)
    }

    /**
     * Earn is a tab that comes and goes with the offerwall catalogue, the
     * same switch the bottom bar follows. Leaving the button up when the tab
     * is gone would send the user to a destination the bar does not admit to,
     * so the button goes with it.
     */
    private fun navigateToEarn() {
        try {
            val navOptions = NavOptions.Builder()
                .setEnterAnim(R.anim.fade_in)
                .setExitAnim(R.anim.fade_out)
                .setPopEnterAnim(R.anim.fade_in)
                .setPopExitAnim(R.anim.fade_out)
                .build()

            findNavController().navigate(R.id.navigation_rewards, null, navOptions)
        } catch (e: Exception) {
            Log.e("Navigation", "Error navigating to earn: ${e.message}")
            (activity as? MainActivity)?.binding?.bottomNav?.selectedItemId = R.id.navigation_rewards
        }
    }

    private fun observeViewModel() {
        // Earn is a tab that comes and goes with the offerwall catalogue.
        // The button goes with it rather than pointing at a destination the
        // bottom bar no longer admits to. It is the only button on the card
        // now, so nothing has to be re-laid-out around its absence.
        mainViewModel.offerwallAvailable.observe(viewLifecycleOwner) { available ->
            _binding?.walletEarnMore?.isVisible = available
        }

        viewModel.games.observe(viewLifecycleOwner) { games ->
            gamesAdapter.submitList(games)
            // The hint under the title only makes sense with a grid under it.
            binding.walletGamesCount.isVisible = games.isNotEmpty()
            renderHeroArt(games)
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
        }

        mainViewModel.firstRedeemFinished.observe(viewLifecycleOwner) { finished ->
            firstRedeemFinished = finished
            updateFirstRedeemCard()
            // The reach line reads this too now - see updateReachLine - so
            // spending the offer has to move the bar, not only retire the
            // card above it.
            updateReachLine()
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
            // No take() any more: the list holds exactly what was read, and
            // what was read is exactly what the user has asked to see.
            activityAdapter.submitList(history)
            binding.activityEmpty.isVisible = history.isEmpty()
            binding.activityRecyclerView.isVisible = history.isNotEmpty()
        }

        viewModel.historyHasMore.observe(viewLifecycleOwner) { hasMore ->
            binding.activityLoadMore.isVisible = hasMore
        }

        viewModel.isLoadingHistory.observe(viewLifecycleOwner) { loading ->
            // Left visible but inert, so a slow page does not make the button
            // jump out from under the finger that just tapped it.
            binding.activityLoadMore.isEnabled = !loading
            binding.activityLoadMore.alpha = if (loading) 0.5f else 1f
        }

        viewModel.isRedeeming.observe(viewLifecycleOwner) { busy ->
            // The sheet draws its own spinner while it is up; this one only
            // covers a redemption confirmed from somewhere else.
            binding.progressIndicator.isVisible =
                busy && parentFragmentManager.findFragmentByTag(RedeemSheetFragment.TAG) == null
        }
    }

    /**
     * What the balance is climbing toward, as a line and a bar.
     *
     * The handoff prints a points-to-currency conversion from a hardcoded
     * rate. There is no such rate here - a pack carries a points price and a
     * free-text amount, with nothing machine-readable between them - so this
     * names a real pack instead: the CHEAPEST ONE THE BALANCE CANNOT REACH
     * YET, which is the only pack a progress bar could honestly point at.
     *
     * Three states, and the third is why the bar is not simply always on:
     *
     *   * nothing in the catalogue at all - the whole group goes,
     *   * everything already affordable - the group goes too, because a full
     *     bar under "you can afford everything" is progress toward nothing,
     *   * something out of reach - the line names it and the bar measures the
     *     climb.
     *
     * WHICH PACKS COUNT depends on whether the first-redeem offer is still
     * live, exactly as it does on Home - see MainViewModel.nextRedemption,
     * which this deliberately mirrors. The two bars describe the same fact
     * and disagreeing about it would be a bug on one of them.
     *
     * The pack's own `amount` is used rather than the game name, for the same
     * reason the tiles do: "600 more stars for 30 UC" says what arrives,
     * without putting somebody else's trade mark on our screen.
     */
    private fun updateReachLine() {
        val binding = _binding ?: return
        val games = viewModel.games.value.orEmpty()
        val points = mainViewModel.userState.value?.points ?: 0

        val reachable = games.filter { it.minLevel <= currentLevel }

        // The offer first, at its discounted price, while this account still
        // has it. It is the cheapest price in the catalogue by design and the
        // one they would actually pay next, so measuring the ordinary floor
        // instead would name a climb they do not have to make. The card
        // directly below this says the same thing; the bar should not be
        // quietly contradicting it.
        val offers = if (firstRedeemFinished) emptyList() else reachable
            .flatMap { game ->
                game.packs.mapNotNull { pack ->
                    pack.firstRedeemCost?.let { pack.amount to it }
                }
            }

        // The cheapest thing still out of reach, falling back to the ordinary
        // catalogue when the offer is finished, when nothing carries a
        // discount, or when the discount is already affordable. Null out of
        // both means the catalogue is empty OR everything is affordable -
        // either way there is no climb left to draw.
        val target = offers
            .filter { (_, cost) -> cost > points }
            .minByOrNull { (_, cost) -> cost }
            ?: reachable
                .flatMap { game ->
                    game.purchasablePacks.map { it.amount to it.pointsCost }
                }
                .filter { (_, cost) -> cost > points }
                .minByOrNull { (_, cost) -> cost }

        val show = target != null
        binding.walletReachLine.isVisible = show
        binding.walletReachGroup.isVisible = show
        if (target == null) return

        val (amount, cost) = target
        binding.walletReachLine.text = getString(
            R.string.wallet_reach_short,
            WalletFormat.number(cost - points),
            amount
        )
        binding.walletReachRatio.text = getString(
            R.string.wallet_reach_ratio,
            WalletFormat.number(points),
            WalletFormat.number(cost)
        )
        // Long arithmetic: a balance and a price are both Ints, and their
        // product overflows at a little over two million points - which this
        // economy will reach.
        binding.walletReachBar.progress =
            (points.toLong() * 100 / cost).toInt().coerceIn(0, 100)
    }

    /** Bundled artwork is always available; catalogue URLs can override it. */
    private fun renderHeroArt(games: List<RedemptionGame>) {
        val binding = _binding ?: return
        val hero = games.firstNotNullOfOrNull { it.walletHeroUrl?.takeIf(String::isNotBlank) }
        binding.walletHeroArt.load(hero ?: R.drawable.wallet_stars_art) {
            placeholder(R.drawable.wallet_stars_art)
            error(R.drawable.wallet_stars_art)
            crossfade(true)
        }
        val gift = games.firstNotNullOfOrNull { it.firstRedeemArtUrl?.takeIf(String::isNotBlank) }
        binding.firstRedeemArt.load(gift ?: R.drawable.wallet_gift_art) {
            placeholder(R.drawable.wallet_gift_art)
            error(R.drawable.wallet_gift_art)
            crossfade(true)
        }
    }

    /**
     * The offer card.
     *
     * Two conditions, and no third. The discount has to be unfinished for
     * this account, and some pack has to actually carry a discounted price -
     * an empty catalogue has no offer to advertise.
     *
     * THE LEVEL GATE IS GONE, and with it the locked state this used to draw.
     * The card previously appeared below the unlock level in a disabled form
     * so the offer was at least discoverable; there is no level to be below
     * now, so the card is either usable or absent.
     *
     * "Finished" covers both ways it ends - spent, or refused because the
     * game account had already had one - and neither is recoverable, so the
     * card never comes back. See MainViewModel.firstRedeemFinished.
     */
    private fun updateFirstRedeemCard() {
        val binding = _binding ?: return
        val offerExists = viewModel.games.value.orEmpty()
            .any { game -> game.packs.any { it.isFirstRedeemOffer } }

        binding.firstRedeemCard.isVisible = !firstRedeemFinished && offerExists
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

        /**
         * Asks this screen to open the redeem sheet for [KEY_GAME_ID], or the
         * first-redeem picker when [KEY_FIRST_REDEEM] is set. Set by the
         * Stars card on Home.
         */
        const val RESULT_OPEN_REDEEM = "wallet_open_redeem"
        const val KEY_GAME_ID = "gameId"
        const val KEY_FIRST_REDEEM = "firstRedeem"

        private const val SPAN_COUNT = 2
        private const val GUTTER_DP = 11

    }
}

package com.example.pixelpayout.ui.redemption

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Paint
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
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
import com.example.pixelpayout.ui.home.setRewardAmount
import com.example.pixelpayout.ui.main.MainViewModel
import com.example.pixelpayout.ui.main.showGameChooser
import com.example.pixelpayout.utils.GridSpacingItemDecoration
import com.example.pixelpayout.utils.setStarText
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

    private var gameChooser: Dialog? = null

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
                    // Posted, not run inline. withResumed fires from inside
                    // the tab-switch transaction that is resuming this
                    // fragment, and the sheet's showNow() is a transaction of
                    // its own - "FragmentManager is already executing
                    // transactions". One tick later that one has finished.
                    view.post {
                        if (_binding == null) return@post
                        showOrders(false)
                        if (firstRedeem) {
                            openFirstRedeem(gameId)
                        } else {
                            mainViewModel.redemptionGames.value.orEmpty()
                                .firstOrNull { it.id == gameId }
                                ?.let { game ->
                                    openRedeemSheet { RedeemSheetFragment.newInstance(game) }
                                }
                        }
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

        // The same chooser as Home's switch, writing the same preference, so
        // a change here moves Home's card too.
        val openChooser = View.OnClickListener {
            if (gameChooser?.isShowing == true) return@OnClickListener
            gameChooser = showGameChooser(mainViewModel, required = false)
        }
        binding.firstRedeemChange.setOnClickListener(openChooser)
        binding.rewardChange.setOnClickListener(openChooser)

        binding.firstRedeemButton.setOnClickListener { onOfferButton() }
        binding.rewardButton.setOnClickListener { onRewardButton() }

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
     * With a [gameId] whose game carries a taster, the sheet opens straight
     * at that pack's ID step. Without one it opens on the cross-game picker:
     * the offer is one choice across the whole catalogue, so it cannot be
     * reached through a game the user has not chosen yet.
     */
    private fun openFirstRedeem(gameId: String? = null) {
        val games = viewModel.games.value.orEmpty()
        if (games.none { game -> game.packs.any { it.isFirstRedeemOffer } }) {
            Snackbar.make(binding.root, R.string.wallet_no_rewards, Snackbar.LENGTH_LONG).show()
            return
        }
        val pack = games.firstOrNull { it.id == gameId }
            ?.packs
            ?.filter { it.isFirstRedeemOffer }
            ?.minByOrNull { it.firstRedeemCost ?: Int.MAX_VALUE }
        openRedeemSheet {
            RedeemSheetFragment.newInstanceFirstRedeem(gameId.takeIf { pack != null }, pack?.id)
        }
    }

    /**
     * Claim when the discount is affordable; otherwise go and earn toward it.
     *
     * Earn, not Play: this card counts stars, and Play pays XP. Without the
     * Earn tab the button still opens the offer, where the dimmed packs at
     * least show what the stars are for.
     */
    private fun onOfferButton() {
        val offer = mainViewModel.starsCard.value?.offer ?: return
        val points = mainViewModel.userState.value?.points ?: 0
        if (points < offer.price && mainViewModel.offerwallAvailable.value == true) {
            navigateToEarn()
        } else {
            openFirstRedeem(offer.game?.id)
        }
    }

    /** Redeem what is affordable in the chosen game; otherwise earn. */
    private fun onRewardButton() {
        val redeemable = mainViewModel.starsCard.value?.redeemable
        when {
            redeemable == null -> navigateToEarn()
            redeemable.viaFirstRedeem -> openFirstRedeem(redeemable.game.id)
            else -> openRedeemSheet { RedeemSheetFragment.newInstance(redeemable.game) }
        }
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
        // Earn is a tab that comes and goes with the offerwall catalogue, and
        // both card buttons fall back to it - so they are redrawn with it.
        mainViewModel.offerwallAvailable.observe(viewLifecycleOwner) { renderCards() }

        viewModel.games.observe(viewLifecycleOwner) { games ->
            gamesAdapter.submitList(games)
            // The hint under the title only makes sense with a grid under it.
            binding.walletGamesCount.isVisible = games.isNotEmpty()
            updateCatalogueState()
            renderCards()
        }

        viewModel.isLoadingGames.observe(viewLifecycleOwner) { updateCatalogueState() }

        mainViewModel.userState.observe(viewLifecycleOwner) { state ->
            gamesAdapter.updateLevel(state.level)
            binding.walletBalance.text = WalletFormat.number(state.points)
            renderCards()
        }

        // Home's Stars card and this screen draw the same object, so they
        // cannot disagree about the target, the chosen game, or whether the
        // offer is still live. It reads the user document directly, which is
        // what keeps the offer hidden until that document has answered.
        mainViewModel.starsCard.observe(viewLifecycleOwner) { renderCards() }
        mainViewModel.preferredGame.observe(viewLifecycleOwner) { renderCards() }

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
     * The two goal cards, of which at most one is ever on screen.
     *
     * WHILE THE OFFER IS LIVE it is the goal: its discounted price is what
     * this account will actually pay next (see MainViewModel.starsCard), so
     * the progress bar sits inside the offer card rather than on a second
     * card repeating the same number. Once it is finished, the goal card
     * takes over and measures the next pack in the chosen game.
     *
     * Everything comes from starsCard, the object Home's Stars card draws -
     * the two screens used to compute this separately, and Wallet's copy
     * ignored the chosen game.
     */
    private fun renderCards() {
        val binding = _binding ?: return
        val card = mainViewModel.starsCard.value
        val preferred = mainViewModel.preferredGame.value
        val points = mainViewModel.userState.value?.points ?: 0
        val canEarn = mainViewModel.offerwallAvailable.value == true
        val games = viewModel.games.value.orEmpty()

        // "Change" once a game is chosen; before that, an invitation. Both
        // cards carry the pill, and only one card is ever on screen.
        val changeText = getString(if (preferred != null) R.string.wallet_change else R.string.wallet_choose)
        listOf(binding.firstRedeemChange, binding.rewardChange).forEach { link ->
            link.text = changeText
            link.paintFlags = link.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        }

        val offer = card?.offer
        binding.firstRedeemCard.isVisible = offer != null
        if (offer != null) renderOfferCard(offer, points, canEarn, games)

        val showReward = offer == null && card != null &&
            (card.next != null || card.redeemable != null)
        binding.rewardCard.isVisible = showReward
        if (showReward && card != null) renderRewardCard(card, preferred, canEarn, games)
    }

    private fun renderOfferCard(
        offer: MainViewModel.FirstRedeemOffer,
        points: Int,
        canEarn: Boolean,
        games: List<RedemptionGame>
    ) {
        val binding = _binding ?: return

        // The pack, named only when it is the chosen game's own: "20 CP" to
        // somebody who plays for Diamonds is an offer they cannot use.
        val pack = offer.pack
        if (pack != null) {
            binding.firstRedeemAmount.setRewardAmount(pack.amount)
        } else {
            // A sentence, not an amount - Home's two-tone split would turn
            // all of it lilac.
            binding.firstRedeemAmount.setText(R.string.first_redeem_pick)
        }

        val listPrice = offer.listPrice
        binding.firstRedeemListPrice.isVisible = listPrice != null
        binding.firstRedeemArrow.isVisible = listPrice != null
        if (listPrice != null) {
            // "700 ★ → 150 ★" rather than words: the row also has to fit the
            // percent badge beside the 112dp art column. The struck-out star
            // is dimmed with its figure so only the real price reads as gold.
            binding.firstRedeemListPrice.setStarText(
                getString(R.string.wallet_star_amount, WalletFormat.number(listPrice)),
                starColor = R.color.text_faint
            )
            binding.firstRedeemListPrice.paintFlags =
                binding.firstRedeemListPrice.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        }
        binding.firstRedeemPrice.setStarText(
            getString(R.string.wallet_star_amount, WalletFormat.number(offer.price))
        )

        val percentOff = offer.percentOff
        binding.firstRedeemBadge.isVisible = percentOff != null
        if (percentOff != null) {
            binding.firstRedeemBadge.text = getString(R.string.first_redeem_percent_off, percentOff)
        }

        val short = offer.price - points
        val affordable = short <= 0
        binding.firstRedeemProgressGroup.isVisible = !affordable
        if (affordable) {
            binding.firstRedeemLine.text = getString(R.string.wallet_ready_to_redeem)
        } else {
            binding.firstRedeemLine.setMoreToUnlock(short)
            binding.firstRedeemRatio.text = getString(
                R.string.wallet_reach_ratio,
                WalletFormat.number(points),
                WalletFormat.number(offer.price)
            )
            // Long arithmetic: see MainViewModel.starsCard.
            binding.firstRedeemBar.progress =
                (points.toLong() * 100 / offer.price.coerceAtLeast(1)).toInt().coerceIn(0, 100)
        }

        // Filled violet is the claim. Short of the price, the button becomes
        // the way to close the gap - quieter, in the Stars colour it earns.
        val button = binding.firstRedeemButton
        when {
            affordable -> {
                styleButton(
                    button, R.string.first_redeem_cta,
                    R.drawable.bg_button_violet_12, R.color.white
                )
                // "Redeem 30 UC now" names what arrives. The generic offer
                // has no single pack to name, so it keeps "Use my discount".
                offer.pack?.let { button.text = getString(R.string.stars_redeem_now, it.amount) }
            }
            canEarn -> styleButton(
                button, R.string.wallet_earn_stars,
                R.drawable.bg_button_stars_outline, R.color.stars_accent
            )
            else -> styleButton(
                button, R.string.first_redeem_view,
                R.drawable.bg_button_accent_outline, R.color.brand_violet_light
            )
        }

        val gift = games.firstNotNullOfOrNull { it.firstRedeemArtUrl?.takeIf(String::isNotBlank) }
        loadArt(binding.firstRedeemArt, gift, R.drawable.wallet_gift_art)
    }

    private fun renderRewardCard(
        card: MainViewModel.StarsCard,
        preferred: RedemptionGame?,
        canEarn: Boolean,
        games: List<RedemptionGame>
    ) {
        val binding = _binding ?: return
        val next = card.next
        val redeemable = card.redeemable

        // The amount below already names the currency, so the header does not.
        binding.rewardHeader.setText(
            if (next != null) R.string.wallet_reward_header_next else R.string.wallet_reward_header_ready
        )

        if (next != null) {
            binding.rewardTarget.setRewardAmount(next.title)
            binding.rewardLine.setMoreToUnlock(next.pointsShort)
            binding.rewardProgressGroup.isVisible = true
            binding.rewardBar.progress = next.percent
            binding.rewardRatio.text = getString(
                R.string.wallet_reach_ratio,
                WalletFormat.number(next.pointsHeld),
                WalletFormat.number(next.pointsCost)
            )
        } else if (redeemable != null) {
            // Nothing left to climb toward in this game: the card says what
            // the balance buys instead of drawing a bar that is always full.
            binding.rewardTarget.setRewardAmount(redeemable.amount)
            binding.rewardLine.text = getString(R.string.wallet_ready_to_redeem)
            binding.rewardProgressGroup.isVisible = false
        }

        // Redeem outranks Earn whenever something is affordable, even while
        // the bar is still climbing toward a bigger pack: the button is for
        // what can be done now.
        val button = binding.rewardButton
        button.isVisible = redeemable != null || canEarn
        if (redeemable != null) {
            button.text = getString(R.string.wallet_redeem_amount, redeemable.amount)
            button.setBackgroundResource(R.drawable.bg_button_stars_filled)
            button.setTextColor(requireContext().getColor(R.color.background_dark))
        } else {
            styleButton(
                button, R.string.wallet_earn_stars,
                R.drawable.bg_button_stars_outline, R.color.stars_accent
            )
        }

        // The chosen game's art in the same order Home's Stars card uses -
        // currency art, then the game's image - so the two tabs always show
        // the same picture. The screen's hero and the bundled stars are only
        // for before a game is chosen, or a game with no art at all.
        val art = if (preferred != null) {
            preferred.currencyImageUrl?.takeIf(String::isNotBlank)
                ?: preferred.imageUrl?.takeIf(String::isNotBlank)
        } else {
            games.firstNotNullOfOrNull { it.walletHeroUrl?.takeIf(String::isNotBlank) }
        }
        loadArt(binding.rewardArt, art, R.drawable.wallet_stars_art)
    }

    /** "110 ★ more to unlock", with the figure and the star in gold. */
    private fun TextView.setMoreToUnlock(stars: Int) {
        val figure = WalletFormat.number(stars)
        setStarText(
            getString(R.string.wallet_more_to_unlock, figure),
            emphasise = figure,
            emphasisColor = R.color.stars_accent
        )
    }

    private fun styleButton(button: TextView, text: Int, background: Int, color: Int) {
        button.setText(text)
        button.setBackgroundResource(background)
        button.setTextColor(requireContext().getColor(color))
    }

    /**
     * Bundled artwork is always available; catalogue URLs can override it.
     * Skips the request when the view already shows that source, because
     * this runs on every balance tick and a fresh crossfade each time would
     * make the art flicker.
     */
    private fun loadArt(view: ImageView, url: String?, fallback: Int) {
        val source: Any = url ?: fallback
        if (view.tag == source) return
        view.tag = source
        view.load(source) {
            placeholder(fallback)
            error(fallback)
            crossfade(true)
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
        // Otherwise a rotation leaks the window.
        gameChooser?.dismiss()
        gameChooser = null
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

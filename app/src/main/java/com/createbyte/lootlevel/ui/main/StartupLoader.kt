package com.createbyte.lootlevel.ui.main

import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Size
import com.createbyte.lootlevel.R
import com.createbyte.lootlevel.data.repository.RedemptionOptionsStore
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch

/**
 * The logo screen that holds the app back until its first screens have
 * something to show.
 *
 * WHY IN MAINACTIVITY rather than a launch activity of its own: the data it
 * waits for is the data MainViewModel's repository loads anyway. Waiting on
 * that same view model costs nothing extra - no second listener, no second
 * read - and Home builds underneath while the logo is up, so it is already
 * drawn when the logo fades.
 *
 * THE BAR IS REAL. It moves one step per part that has actually arrived:
 * the profile, the level curve, the reward catalogue, the reward pictures and
 * the tournament standings. Firestore and callables report no progress inside
 * a part, so the steps are animated between rather than invented. It stays
 * hidden unless loading takes long enough to notice - a warm start, served
 * from Firestore's disk cache, is gone before it would appear.
 *
 * NEVER A TRAP: after [TIMEOUT_MS] the app opens with whatever has arrived,
 * and the rest keeps loading into the screens as it would have before.
 */
class StartupLoader(
    private val activity: AppCompatActivity,
    private val viewModel: MainViewModel,
    private val onFinished: () -> Unit
) {

    private enum class Part { PROFILE, LEVELS, REWARDS, PICTURES, TOURNAMENT }

    private val done = mutableSetOf<Part>()
    private var finished = false
    private var picturesStarted = false

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var overlay: View
    private lateinit var bar: LinearProgressIndicator

    private val showBar = Runnable {
        bar.animate().alpha(1f).setDuration(BAR_FADE_MS).start()
    }
    private val timeout = Runnable { finish() }

    fun start() {
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        overlay = LayoutInflater.from(activity).inflate(R.layout.view_startup_loading, root, false)
        root.addView(overlay)
        bar = overlay.findViewById(R.id.startupProgress)
        bar.max = 100
        bar.alpha = 0f

        handler.postDelayed(showBar, BAR_DELAY_MS)
        handler.postDelayed(timeout, TIMEOUT_MS)

        // The user document, which carries balance, level, streak and flags.
        whenReady(viewModel.points, Part.PROFILE) { true }

        // Level numbers, bars and the rewards on the ladder.
        whenReady(viewModel.levelCurve, Part.LEVELS) { it != null }

        // The catalogue behind Home's "next reward" card. Empty counts once
        // the store has stopped loading, so a catalogue with nothing enabled
        // does not hold the logo up for the whole timeout.
        whenReady(viewModel.redemptionGames, Part.REWARDS) {
            it.isNotEmpty() || RedemptionOptionsStore.isLoading.value == false
        }
        whenReady(RedemptionOptionsStore.isLoading, Part.REWARDS) { !it }

        // The standings, fetched once here instead of on the first visit to
        // Earn; the view model's throttle then treats them as fresh.
        activity.lifecycleScope.launch {
            viewModel.loadLeaderboardNow()
            complete(Part.TOURNAMENT)
        }
    }

    private fun <T> whenReady(data: LiveData<T>, part: Part, ready: (T) -> Boolean) {
        data.observe(activity, object : Observer<T> {
            override fun onChanged(value: T) {
                if (!ready(value)) return
                data.removeObserver(this)
                complete(part)
            }
        })
    }

    private fun complete(part: Part) {
        if (finished || !done.add(part)) return
        if (part == Part.REWARDS) preloadPictures()

        bar.setProgressCompat(done.size * 100 / Part.values().size, true)
        if (done.size == Part.values().size) finish()
    }

    /**
     * Downloads the catalogue's artwork into Coil's disk cache, so Home and
     * Wallet draw it from the phone instead of the network.
     *
     * Disk only, and decoded at a token size: holding every full-size PNG in
     * memory for screens that may never open would cost far more than
     * decoding the one that does from disk.
     */
    private fun preloadPictures() {
        if (picturesStarted) return
        picturesStarted = true

        val urls = viewModel.redemptionGames.value.orEmpty()
            .flatMap { listOf(it.currencyImageUrl, it.imageUrl, it.walletHeroUrl, it.firstRedeemArtUrl) }
            .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
            .distinct()

        activity.lifecycleScope.launch {
            val loader = activity.imageLoader
            urls.map { url ->
                async {
                    loader.execute(
                        ImageRequest.Builder(activity)
                            .data(url)
                            .size(Size(PRELOAD_DECODE_PX, PRELOAD_DECODE_PX))
                            .memoryCachePolicy(CachePolicy.DISABLED)
                            .build()
                    )
                }
            }.awaitAll()
            complete(Part.PICTURES)
        }
    }

    private fun finish() {
        if (finished) return
        finished = true
        handler.removeCallbacks(timeout)

        handler.removeCallbacks(showBar)
        bar.setProgressCompat(100, true)

        // A beat on the full bar when it was visible, so the last step is
        // seen landing rather than cut off.
        val hold = if (bar.alpha > 0f) FULL_BAR_HOLD_MS else 0L
        handler.postDelayed({
            overlay.animate()
                .alpha(0f)
                .setDuration(FADE_OUT_MS)
                .withEndAction {
                    (overlay.parent as? ViewGroup)?.removeView(overlay)
                    onFinished()
                }
                .start()
        }, hold)
    }

    private companion object {
        /** Starts shorter than this never show the bar at all. */
        const val BAR_DELAY_MS = 300L
        const val BAR_FADE_MS = 200L

        /** The longest the logo may hold the app back. */
        const val TIMEOUT_MS = 3_000L

        const val FULL_BAR_HOLD_MS = 250L
        const val FADE_OUT_MS = 220L

        const val PRELOAD_DECODE_PX = 64
    }
}

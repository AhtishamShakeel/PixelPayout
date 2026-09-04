package com.example.pixelpayout.ui.rewards

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.pixelpayout.data.model.OfferwallEntry
import com.example.pixelpayout.data.repository.UserRepository
import com.example.pixelpayout.utils.TapjoyOfferwall
import com.google.firebase.firestore.FirebaseFirestore
import com.pixelpayout.R
import com.pixelpayout.databinding.FragmentRewardsBinding
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * The offerwall list.
 *
 * REPLACES THE TAPJOY WIRING THAT USED TO LIVE HERE, which was placeholder
 * grade in three ways worth recording rather than quietly deleting:
 * `onRewardRequest` only logged, so no completion ever credited anybody;
 * `onRequestSuccess` called `requestContent()` again, re-entering the
 * request it was reporting on; and `Tapjoy.connect` ran on every visit to
 * this screen while the placement held an Activity reference across
 * rotation. The AAR stays in the build - removing it is work with no payoff
 * while Tapjoy's post-ironSource status is unresolved - but nothing calls
 * into it now, and if it is ever approved it should come back through the
 * catalogue below rather than as bespoke code on this screen.
 *
 * Everything here is driven by `config/offerwallWalls`, so approving a
 * network is a document rather than a release.
 */
class RewardsFragment : Fragment() {

    private var _binding: FragmentRewardsBinding? = null
    private val binding get() = _binding!!

    private val userRepository = UserRepository()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRewardsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Row spacing is a margin on the item, not an ItemDecoration:
        // SpacingItemDecoration casts to StaggeredGridLayoutManager.LayoutParams
        // and would throw under the linear manager this list uses.
        binding.offerwallList.layoutManager = LinearLayoutManager(requireContext())

        loadWalls()
    }

    private fun loadWalls() {
        showLoading()

        viewLifecycleOwner.lifecycleScope.launch {
            val walls = try {
                val snapshot = FirebaseFirestore.getInstance()
                    .collection(CONFIG_COLLECTION)
                    .document(WALLS_DOC)
                    .get()
                    .await()

                OfferwallEntry.parseAll(snapshot.data, currentLevel())
            } catch (e: Exception) {
                // An unreadable catalogue and an empty one look the same to
                // the user on purpose. There is nothing for them to retry -
                // no wall exists either way - and an error state here would
                // read as "the earning screen is broken", which is a worse
                // and less accurate thing to say than "nothing yet".
                Log.e(TAG, "Could not read the offerwall catalogue: ${e.message}")
                emptyList()
            }

            // The view can be gone by the time Firestore answers.
            if (_binding == null) return@launch
            render(walls)
        }
    }

    /**
     * The level to filter the catalogue against.
     *
     * Falls back to 1 rather than to a high number: an unknown level should
     * show the ungated walls, not silently unlock the gated ones.
     */
    private fun currentLevel(): Int =
        userRepository.userData.value?.level ?: 1

    private fun render(walls: List<OfferwallEntry>) {
        binding.offerwallLoading.isVisible = false
        binding.offerwallEmpty.isVisible = walls.isEmpty()
        binding.offerwallList.isVisible = walls.isNotEmpty()

        binding.offerwallList.adapter = OfferwallAdapter(walls) { wall -> open(wall) }
    }

    private fun showLoading() {
        binding.offerwallLoading.isVisible = true
        binding.offerwallEmpty.isVisible = false
        binding.offerwallList.isVisible = false
    }

    private fun open(wall: OfferwallEntry) {
        val uid = userRepository.getCurrentUserId()
        if (uid.isNullOrBlank()) {
            // Without a uid the network has nothing to attribute a
            // completion to, so opening the wall would let the user do the
            // work and never be paid for it. Refusing is the honest failure.
            Log.e(TAG, "No signed-in user; refusing to open ${wall.id}")
            return
        }

        if (wall.type == OfferwallEntry.TYPE_TAPJOY) {
            TapjoyOfferwall.show(requireActivity(), uid) { reason ->
                // Reported, never silent. A tap that produces no screen and
                // no message is indistinguishable from a frozen app, and the
                // user's only recourse is to tap again - which was exactly
                // the old behaviour.
                if (_binding == null) return@show
                Toast.makeText(
                    requireContext(),
                    when (reason) {
                        TapjoyOfferwall.REASON_CONNECTING -> getString(R.string.offerwall_connecting)
                        TapjoyOfferwall.REASON_LOADING -> getString(R.string.offerwall_loading)
                        else -> getString(R.string.offerwall_unavailable)
                    },
                    Toast.LENGTH_SHORT
                ).show()
            }
            return
        }

        startActivity(
            OfferwallActivity.intent(requireContext(), wall.urlFor(uid), wall.name)
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Dropped explicitly: the adapter closes over this fragment through
        // its click lambda, and a RecyclerView outliving the view it was
        // bound from is the usual way that becomes a leak.
        _binding?.offerwallList?.adapter = null
        _binding = null
    }

    companion object {
        private const val TAG = "Offerwall"
        private const val CONFIG_COLLECTION = "config"
        private const val WALLS_DOC = "offerwallWalls"
    }
}

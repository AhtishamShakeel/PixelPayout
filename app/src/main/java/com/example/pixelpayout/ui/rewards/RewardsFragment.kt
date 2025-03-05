package com.pixelpayout.ui.rewards

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.pixelpayout.databinding.FragmentRewardsBinding
import com.pixelpayout.data.repository.UserRepository
import com.tapjoy.*
import java.util.Hashtable

class RewardsFragment : Fragment() {
    private var _binding: FragmentRewardsBinding? = null
    private val binding get() = _binding!!

    private val userRepository = UserRepository()
    private var userId: String? = null
    private var offerwallPlacement: TJPlacement? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRewardsBinding.inflate(inflater, container, false)

        getUserIdAndInitializeTapjoy()

        binding.offerwallButton.setOnClickListener {
            showOfferwall()
        }

        return binding.root
    }

    private fun getUserIdAndInitializeTapjoy() {
        userId = userRepository.getCurrentUserId() // Get user ID

        if (userId != null) {
            initializeTapjoy(userId!!)
        } else {
            Log.e("Tapjoy", "Failed to get user ID")
        }
    }

    private fun initializeTapjoy(userId: String) {
        val connectFlags = Hashtable<String, Any>().apply {
            put(TapjoyConnectFlag.ENABLE_LOGGING, "false") // Disable in production
            put(TapjoyConnectFlag.USER_ID, userId) // Assign user ID to Tapjoy
        }

        activity?.applicationContext?.let { context ->
            Tapjoy.connect(context, "ouc7hbV7TwOZCHX3YYtQIQECcCkzfjwMerEDDZNQ32kCdsznWomW_spBpqbx", connectFlags, object : TJConnectListener() {
                override fun onConnectSuccess() {
                    Log.d("Tapjoy", "Tapjoy connected successfully")
                    setupOfferwall()
                }

                override fun onConnectFailure(code: Int, message: String) {  // FIXED
                    Log.e("Tapjoy", "Tapjoy connection failed: Code $code, Message: $message")
                }

                override fun onConnectWarning(code: Int, message: String) {
                    Log.w("Tapjoy", "Tapjoy connection warning: $message")
                }
            })
        }
    }

    private fun setupOfferwall() {
        activity?.let { activityContext ->
            offerwallPlacement = TJPlacement(activityContext, "offerwall", object : TJPlacementListener {
                override fun onRequestSuccess(placement: TJPlacement?) {
                    Log.d("Tapjoy", "Offerwall request successful")
                    placement?.requestContent() // Ensure requestContent() is called
                }

                override fun onRequestFailure(placement: TJPlacement?, error: TJError?) {
                    Log.e("Tapjoy", "Offerwall request failed: ${error?.message}")
                }

                override fun onContentReady(placement: TJPlacement?) {
                    Log.d("Tapjoy", "Offerwall content ready")
                    if (placement?.isContentAvailable == true) {
                        Log.d("Tapjoy", "Content available: showing offerwall")
                    } else {
                        Log.w("Tapjoy", "No content available for offerwall")
                    }
                }

                override fun onContentShow(placement: TJPlacement?) {
                    Log.d("Tapjoy", "Offerwall shown")
                }

                override fun onContentDismiss(placement: TJPlacement?) {
                    Log.d("Tapjoy", "Offerwall dismissed")
                }

                override fun onPurchaseRequest(placement: TJPlacement?, request: TJActionRequest?, sku: String?) {
                    Log.d("Tapjoy", "Offerwall purchase request")
                }

                override fun onRewardRequest(placement: TJPlacement?, request: TJActionRequest?, currency: String?, amount: Int) {
                    Log.d("Tapjoy", "Offerwall reward request: $amount $currency")
                }
            })
            offerwallPlacement?.requestContent() // Call this after initializing the placement
        } ?: Log.e("Tapjoy", "Activity is null, cannot set up offerwall")
    }


    private fun showOfferwall() {
        if (offerwallPlacement?.isContentAvailable == true) {
            offerwallPlacement?.showContent()
        } else {
            Log.w("Tapjoy", "Offerwall content not available")
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

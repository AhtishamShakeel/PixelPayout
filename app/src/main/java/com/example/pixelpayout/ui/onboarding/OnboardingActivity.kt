package com.pixelpayout.ui.onboarding

import android.content.Intent
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.example.pixelpayout.ui.onboarding.SlideAdapter
import com.example.pixelpayout.ui.onboarding.SlideItem
import com.example.pixelpayout.ui.onboarding.TermsDialogFragment
import com.google.android.material.tabs.TabLayoutMediator
import com.pixelpayout.R
import com.pixelpayout.databinding.ActivityOnboardingBinding
import com.pixelpayout.ui.auth.LoginActivity
import com.pixelpayout.ui.auth.SignupActivity
import com.pixelpayout.ui.main.MainActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class OnboardingActivity : AppCompatActivity() {
    private lateinit var binding: ActivityOnboardingBinding
    private val viewModel: OnboardingViewModel by viewModels()
    private var autoScrollJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check if user is already logged in
        if (viewModel.isUserLoggedIn()) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupViewPager()
        setupButtons()
        setupTermsText()
        startAutoScroll()
    }

    private fun setupViewPager() {
        val slides = listOf(
            SlideItem(
                title = getString(R.string.slide_1_title),
                description = getString(R.string.slide_1_description)
            ),
            SlideItem(
                title = getString(R.string.slide_2_title),
                description = getString(R.string.slide_2_description),
            ),
            SlideItem(
                title = getString(R.string.slide_3_title),
                description = getString(R.string.slide_3_description)
            )
        )

        binding.viewPager.adapter = SlideAdapter(slides)

        // Connect ViewPager2 with TabLayout for dots indicator
        TabLayoutMediator(binding.pageIndicator, binding.viewPager) { _, _ -> }.attach()

        // Handle manual sliding
        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                restartAutoScroll()
            }
        })
    }

    private fun setupButtons() {
        binding.btnCreateAccount.setOnClickListener {
            startActivity(Intent(this, SignupActivity::class.java))
        }

        binding.btnLogin.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
        }
    }

    private fun setupTermsText() {
        val fullText = getString(R.string.terms_agreement)
        val spannableString = SpannableString(fullText)

        val termsClickableSpan = object : ClickableSpan() {
            override fun onClick(view: View) {
                showTermsDialog("terms")
            }
        }

        val privacyClickableSpan = object : ClickableSpan() {
            override fun onClick(view: View) {
                showTermsDialog("privacy")
            }
        }

        // Find indices for clickable parts
        val termsStart = fullText.indexOf("Terms and Conditions")
        val termsEnd = termsStart + "Terms and Conditions".length
        val privacyStart = fullText.indexOf("Privacy Policy")
        val privacyEnd = privacyStart + "Privacy Policy".length

        spannableString.setSpan(termsClickableSpan, termsStart, termsEnd,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        spannableString.setSpan(privacyClickableSpan, privacyStart, privacyEnd,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        binding.termsText.apply {
            text = spannableString
            movementMethod = LinkMovementMethod.getInstance()
        }
    }

    private fun startAutoScroll() {
        autoScrollJob?.cancel()
        autoScrollJob = lifecycleScope.launch {
            while (true) {
                delay(5000) // Wait 5 seconds
                val numberOfSlides = binding.viewPager.adapter?.itemCount ?: 0
                if (numberOfSlides > 0) {
                    val nextItem = (binding.viewPager.currentItem + 1) % numberOfSlides
                    binding.viewPager.setCurrentItem(nextItem, true)
                }
            }
        }
    }

    private fun restartAutoScroll() {
        autoScrollJob?.cancel()
        startAutoScroll()
    }

    private fun showTermsDialog(type: String) {
        TermsDialogFragment.newInstance(type)
            .show(supportFragmentManager, "terms_dialog")
    }

    override fun onDestroy() {
        super.onDestroy()
        autoScrollJob?.cancel()
    }
}
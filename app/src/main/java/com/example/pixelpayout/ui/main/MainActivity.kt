package com.pixelpayout.ui.main

import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.pixelpayout.R
import com.pixelpayout.databinding.ActivityMainBinding
import com.pixelpayout.databinding.LayoutPointsHeaderBinding

class MainActivity : AppCompatActivity() {


    lateinit var binding: ActivityMainBinding
    private lateinit var pointsHeaderBinding: LayoutPointsHeaderBinding
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupNavigation()
        observeViewModel()
        refreshPoints()
    }

    private fun setupToolbar() {
        pointsHeaderBinding = LayoutPointsHeaderBinding.inflate(layoutInflater)
        supportActionBar?.apply {
            setDisplayShowCustomEnabled(true)
            customView = pointsHeaderBinding.root
        }

        // Make points clickable
        pointsHeaderBinding.root.setOnClickListener {
            binding.bottomNav.selectedItemId = R.id.navigation_redemption
        }
    }

    private fun setupNavigation() {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController

        binding.bottomNav.setupWithNavController(navController)
    }

    private fun observeViewModel() {
        viewModel.points.observe(this) { points ->
            pointsHeaderBinding.pointsText.text = getString(R.string.points_value, points)
        }
    }

    fun refreshPoints() {
        viewModel.loadPoints()
    }
} 
package com.example.pixelpayout.config

object AppConfig {
    const val TAPJOY_SDK_KEY = "8mQLvXA7SwSNhj9N8wQ3GQECBj9IennVxwkaSVhfF58OJr1P8DVIMsMcB_Va"
    const val TAPJOY_OFFERWALL_PLACEMENT = "offerwall"

    const val ADMOB_REWARDED_AD_UNIT_ID = "ca-app-pub-3940256099942544/5224354917"
    const val ADMOB_GAME_BANNER_AD_UNIT_ID = "ca-app-pub-3940256099942544/6300978111"

    /**
     * The between-activities interstitial. Google's test unit for now, like
     * the two above it.
     *
     * Kept as its own unit rather than reusing the rewarded one even once the
     * real ids land: the two are bid on differently and reported separately,
     * and the whole cadence design rests on being able to see how often each
     * one actually shows.
     */
    const val ADMOB_INTERSTITIAL_AD_UNIT_ID = "ca-app-pub-3940256099942544/1033173712"
}

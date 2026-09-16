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

    /**
     * Unity Ads, the fallback behind AdMob - see UnityAdsNetwork.
     *
     * The Game ID is on the Unity dashboard under Monetization > Project
     * settings (the Android one). Blank switches Unity off entirely.
     *
     * Which network plays first, and switching either off, is NOT here - it
     * is the `config/ads` document in Firestore. See AdNetworkConfigStore.
     *
     * The placement ids are the Ad Unit IDs Unity creates by default for a
     * new Android project; change them if yours are named differently.
     */
    const val UNITY_GAME_ID = "5816684"
    const val UNITY_REWARDED_PLACEMENT_ID = "Rewarded_Android"
    const val UNITY_INTERSTITIAL_PLACEMENT_ID = "Interstitial_Android"

    /**
     * Test ads only, like the AdMob test units above. Must be false for
     * release - live ads on a test device, or test ads in production, are
     * both wrong. See docs/DEFERRED.md item 1.
     */
    const val UNITY_TEST_MODE = true
}

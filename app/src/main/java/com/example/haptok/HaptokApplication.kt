package com.example.haptok

import android.app.Application

/**
 * Application class for Haptok.
 * Initializes singletons that live for the entire app lifecycle.
 */
class HaptokApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Initialize the haptic capability manager early so device profile
        // is available before the first video is opened.
        com.example.haptok.haptics.HapticCapabilityManager.init(this)
        // Initialize the network module with saved preferences.
        com.example.haptok.network.NetworkModule.getInstance(this)
    }
}

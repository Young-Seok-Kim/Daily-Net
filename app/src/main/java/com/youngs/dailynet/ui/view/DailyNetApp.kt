package com.youngs.dailynet.ui.view

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class DailyNetApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // 1. Firebase 초기화
        FirebaseApp.initializeApp(this)

        // 2. App Check 설정 (Play Integrity 사용)
        val firebaseAppCheck = FirebaseAppCheck.getInstance()
        firebaseAppCheck.installAppCheckProviderFactory(
            PlayIntegrityAppCheckProviderFactory.getInstance()
        )
    }
}
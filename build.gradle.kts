// Top-level build file

// AGP 8.9에 딸려오는 R8은 Kotlin 2.2 메타데이터를 못 읽어 릴리스 빌드 때 경고를 쏟아낸다.
// AGP를 올리기 전까지 R8만 최신으로 갈아끼운다 (안드로이드 공식 안내 방식).
buildscript {
    repositories {
        google()
    }
    dependencies {
        classpath("com.android.tools:r8:8.13.23")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.google.android.secrets) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.firebase.crashlytics) apply false
}
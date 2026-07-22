plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
    alias(libs.plugins.google.android.secrets)
    alias(libs.plugins.hilt)
    id("kotlin-kapt")
}

android {
    namespace = "com.youngs.dailynet"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.youngs.dailynet"
        minSdk = 26
        targetSdk = 36
        versionCode = 13
        versionName = "1.6"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // Kotlin 2.2 + 현재 AGP의 R8 조합에서 R8이 Gson TypeToken의 제네릭 시그니처를
            // 제거해 앱이 시작 시 죽는 문제가 있어 난독화를 비활성화한다.
            // (추후 AGP 업그레이드 후 재활성화 가능)
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.auth)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
    implementation(libs.google.gemini)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    //hilt
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    //room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    kapt(libs.androidx.room.compiler)

    implementation(libs.google.code.gson)

    // Retrofit2
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson) // JSON 변환기

    // OkHttp (로그 확인용)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    implementation(libs.firebase.appcheck.playintegrity)

    implementation(libs.play.billing)
    implementation(libs.play.billing.ktx)

    // Health Connect: 오늘의 총 걸음 수(앱 설치 전 걸음 포함) 읽기
    implementation("androidx.health.connect:connect-client:1.1.0")
}

// 2. 파일 맨 하단에 이 블록을 정확하게 넣어주세요.
secrets {
    // 읽어올 파일 지정
    propertiesFileName = "local.properties"
    ignoreList.add("sdk.dir") // sdk.dir 같은 건 필드 만들 필요 없으니 제외
}
android {
    val vName = defaultConfig.versionName
    val vCode = defaultConfig.versionCode

    // 빌드 결과물(AAB, APK 등)의 기본 생성 이름을 프로젝트단에서 강제 지정
    base.archivesName.set("DailyNet_v${vName}_b${vCode}")
}
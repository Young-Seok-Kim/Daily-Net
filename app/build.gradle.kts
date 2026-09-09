import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
    alias(libs.plugins.google.android.secrets)
    alias(libs.plugins.hilt)
    alias(libs.plugins.firebase.crashlytics)
    id("kotlin-kapt")
}

/*
 * 서명 키 정보는 git에 올리지 않는 keystore.properties(프로젝트 루트)에서 읽는다.
 * local.properties는 secrets 플러그인이 BuildConfig 필드로 만들어버리므로 비밀번호를 두면 안 된다.
 */
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.youngs.dailynet"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.youngs.dailynet"
        minSdk = 26
        targetSdk = 36
        // 변경 내역은 프로젝트 루트의 CHANGELOG.md 참고 (버전 올릴 때 거기에 기록)
        versionCode = 32
        versionName = "1.8.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val storeFilePath = keystoreProps.getProperty("storeFile")
            if (storeFilePath != null) {
                storeFile = rootProject.file(storeFilePath)
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // keystore.properties가 있을 때만 서명 (없으면 미서명 빌드로 떨어짐)
            if (keystoreProps.getProperty("storeFile") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            // R8 축소·난독화. 예전에 Gson TypeToken의 제네릭 시그니처가 제거돼 앱이 죽어 꺼뒀는데,
            // proguard-rules.pro에 TypeToken 보존 규칙을 넣어 해결했다.
            // 켜야 플레이 콘솔에 매핑 파일이 올라가고(Crashlytics 플러그인이 자동 업로드) 관련 경고가 사라진다.
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 네이티브(.so) 심볼 테이블이 있으면 AAB에 같이 넣는다.
            // 지금 들어 있는 .so(CameraX·androidx.graphics)는 이미 심볼이 벗겨진 채 배포돼 실제로 넣을 게 없다.
            // 그래서 플레이 콘솔의 "디버그 기호가 업로드되지 않았습니다" 경고는 남을 수 있다 (무시해도 되는 경고).
            // 나중에 심볼을 가진 라이브러리가 들어오면 자동으로 포함되도록 설정만 켜둔다.
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
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
    implementation(libs.firebase.config)
    implementation(libs.firebase.crashlytics)
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

    // 구글 프로필 사진 로딩
    implementation(libs.coil.compose)

    // 매일 정산 리마인더 알림
    implementation(libs.androidx.work.runtime)

    // 홈 화면 위젯 (오늘의 순칼로리)
    implementation(libs.androidx.glance.appwidget)

    // 음식 사진 무음 촬영 (앱 내 카메라)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
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
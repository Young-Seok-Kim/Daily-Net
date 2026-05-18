package com.youngs.dailynet.di

import android.content.Context
import androidx.room.Room
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.youngs.dailynet.data.local.entity.dao.SettlementDao
import com.youngs.dailynet.data.local.entity.dao.WeightDao
import com.youngs.dailynet.data.local.AppDatabase
import com.youngs.dailynet.data.network.DailyNetApiService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Singleton
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.youngs.dailynet.data.local.entity.dao.UserProfileDao
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(Constants.REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS) // 숫자와 단위를 쌍으로 입력
            .readTimeout(Constants.REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(Constants.REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)       // 연결 실패 시 재시도 여부
            .build()
    }
    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl(Constants.BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideDailyNetApiService(retrofit: Retrofit): DailyNetApiService {
        return retrofit.create(DailyNetApiService::class.java)
    }

    @Provides
    @Singleton
    fun provideAppCheck(@ApplicationContext context: Context): FirebaseAppCheck {
        // Firebase가 이미 초기화되어 있어야 합니다.
        FirebaseApp.initializeApp(context)
        val firebaseAppCheck = FirebaseAppCheck.getInstance()

        // Play Integrity 증명 공급자 등록
        firebaseAppCheck.installAppCheckProviderFactory(
            PlayIntegrityAppCheckProviderFactory.getInstance()
        )
        return firebaseAppCheck
    }

    @Provides
    @Singleton
    fun provideFirebaseAuth(): FirebaseAuth = FirebaseAuth.getInstance()

    @Provides
    @Singleton
    fun provideFirestore(): FirebaseFirestore = FirebaseFirestore.getInstance()

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "dailynet_db"
        )
            .fallbackToDestructiveMigration() // 스키마 변경 시 기존 데이터 초기화 허용
            .build()
    }

    @Provides
    fun provideSettlementDao(db: AppDatabase): SettlementDao = db.settlementDao()

    @Provides
    fun provideWeightDao(db: AppDatabase): WeightDao = db.weightDao()

    @Provides
    fun provideUserProfileDao(db: AppDatabase): UserProfileDao = db.userProfileDao()
}
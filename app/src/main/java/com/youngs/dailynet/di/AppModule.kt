package com.youngs.dailynet.di

import android.content.Context
import androidx.room.Room
import com.google.ai.client.generativeai.GenerativeModel
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.youngs.dailynet.BuildConfig
import com.youngs.dailynet.data.dao.SettlementDao
import com.youngs.dailynet.data.dao.WeightDao
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

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideRetrofit(): Retrofit {
        return Retrofit.Builder()
            .baseUrl(Constants.BASE_URL)
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
}
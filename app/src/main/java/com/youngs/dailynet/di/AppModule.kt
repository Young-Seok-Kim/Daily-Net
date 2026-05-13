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
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideGenerativeModel(): GenerativeModel {
        return GenerativeModel(
            modelName = Constants.MODEL_NAME,
            apiKey = BuildConfig.GEMINI_API_KEY
        )
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
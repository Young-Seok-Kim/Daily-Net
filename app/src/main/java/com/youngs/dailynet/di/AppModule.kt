package com.youngs.dailynet.di

import android.content.Context
import androidx.room.Room
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.youngs.dailynet.data.dao.SettlementDao
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

    // ✨ FirebaseFirestore 인스턴스를 Hilt가 관리하도록 제공
    @Provides
    @Singleton
    fun provideFirestore(): FirebaseFirestore {
        return FirebaseFirestore.getInstance()
    }

    // 기존에 만드신 AppDatabase, Dao 관련 코드들...
    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(context, AppDatabase::class.java, "dailynet_db")
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    fun provideSettlementDao(db: AppDatabase): SettlementDao = db.settlementDao()
}
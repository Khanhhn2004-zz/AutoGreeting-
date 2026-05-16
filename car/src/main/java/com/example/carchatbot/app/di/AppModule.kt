package com.example.carchatbot.app.di

import android.content.Context
import com.example.carchatbot.boot.BootStorageContextProvider
import com.example.carchatbot.boot.BootPlaybackStateStore
import com.example.carchatbot.boot.StartupArmStateStore
import com.example.carchatbot.data.local.UserPreferencesRepository
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
    fun provideUserPreferencesRepository(
        @ApplicationContext context: Context,
        bootStorageContextProvider: BootStorageContextProvider
    ): UserPreferencesRepository {
        return UserPreferencesRepository(context, bootStorageContextProvider)
    }

    @Provides
    @Singleton
    fun provideWorkManager(
        @ApplicationContext context: Context
    ): androidx.work.WorkManager {
        return androidx.work.WorkManager.getInstance(context)
    }

    @Provides
    @Singleton
    fun provideBootPlaybackStateStore(
        bootStorageContextProvider: BootStorageContextProvider
    ): BootPlaybackStateStore {
        return BootPlaybackStateStore(bootStorageContextProvider)
    }

    @Provides
    @Singleton
    fun provideStartupArmStateStore(
        bootStorageContextProvider: BootStorageContextProvider
    ): StartupArmStateStore {
        return StartupArmStateStore(bootStorageContextProvider)
    }
}

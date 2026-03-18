package com.mylock.app.ttlock

import com.mylock.app.AppConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object TtlockModule {

    @Provides
    @Singleton
    fun provideTtlockClient(): TtlockClient =
        TtlockClient(
            clientId = AppConfig.TTLOCK_CLIENT_ID,
            clientSecret = AppConfig.TTLOCK_CLIENT_SECRET
        )
}

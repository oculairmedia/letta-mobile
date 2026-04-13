package com.letta.mobile.di

import android.content.Context
import com.letta.mobile.data.local.AgentDao
import com.letta.mobile.data.local.BugReportDao
import com.letta.mobile.data.local.LettaDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): LettaDatabase {
        return LettaDatabase.getDatabase(context)
    }

    @Provides
    fun provideAgentDao(database: LettaDatabase): AgentDao {
        return database.agentDao()
    }

    @Provides
    fun provideBugReportDao(database: LettaDatabase): BugReportDao {
        return database.bugReportDao()
    }
}

package com.letta.mobile.ui.screens.agentlist

import com.letta.mobile.data.agenteditor.AgentCreator
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Binds [AndroidAgentCreator] as the shared agent editor's creator (letta-mobile-ckmub.2). */
@Module
@InstallIn(SingletonComponent::class)
interface AndroidAgentCreatorModule {
    @Binds
    fun bindAgentCreator(creator: AndroidAgentCreator): AgentCreator
}

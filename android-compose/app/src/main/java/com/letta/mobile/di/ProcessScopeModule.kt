package com.letta.mobile.di

import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.letta.mobile.data.session.ProcessScope
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope

/** letta-mobile-by4vm.89: the one place the process lifecycle becomes the data layer's [ProcessScope]. */
@Module
@InstallIn(SingletonComponent::class)
object ProcessScopeModule {
    @Provides
    @ProcessScope
    fun provideProcessScope(): CoroutineScope = ProcessLifecycleOwner.get().lifecycleScope
}

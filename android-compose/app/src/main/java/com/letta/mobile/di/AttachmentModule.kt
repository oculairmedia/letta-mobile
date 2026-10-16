package com.letta.mobile.di

import com.letta.mobile.data.attachment.AttachmentLimits
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * lcp-dlj: single source of truth for image-attachment caps.
 *
 * Default values follow Anthropic vision-input guidance.
 * Swap this provider to read from SettingsRepository when the
 * settings-UI tunable surface lands.
 */
@Module
@InstallIn(SingletonComponent::class)
object AttachmentModule {
    @Provides
    @Singleton
    fun provideAttachmentLimits(): AttachmentLimits = AttachmentLimits.Default
}

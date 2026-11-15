package com.letta.mobile.feature.chat.voice

import android.content.Context
import com.letta.mobile.data.voice.local.LocalSpeechRecognizer
import com.letta.mobile.data.voice.local.android.AndroidLocalSpeechRecognizer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.android.qualifiers.ApplicationContext

/**
 * Dictation listens through the same recogniser binding as voice mode: the shared
 * [LocalSpeechRecognizer] port over Android's `SpeechRecognizer`.
 */
@Module
@InstallIn(ViewModelComponent::class)
internal object VoiceRecognizerModule {
    @Provides
    fun localSpeechRecognizer(@ApplicationContext context: Context): LocalSpeechRecognizer = AndroidLocalSpeechRecognizer(context)
}

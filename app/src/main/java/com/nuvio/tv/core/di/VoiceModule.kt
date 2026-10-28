package com.nuvio.tv.core.di

import com.nuvio.tv.core.voice.AddonVoiceTitleSearch
import com.nuvio.tv.core.voice.InstalledVoiceAddonSource
import com.nuvio.tv.core.voice.VoiceAddonSource
import com.nuvio.tv.core.voice.VoiceResumeSource
import com.nuvio.tv.core.voice.VoiceTitleSearch
import com.nuvio.tv.core.voice.WatchProgressVoiceResumeSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class VoiceModule {
    @Binds
    @Singleton
    abstract fun bindVoiceAddonSource(impl: InstalledVoiceAddonSource): VoiceAddonSource

    @Binds
    @Singleton
    abstract fun bindVoiceTitleSearch(impl: AddonVoiceTitleSearch): VoiceTitleSearch

    @Binds
    @Singleton
    abstract fun bindVoiceResumeSource(impl: WatchProgressVoiceResumeSource): VoiceResumeSource
}

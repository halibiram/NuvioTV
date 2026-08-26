package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.player.PrewarmedPlayerFactory
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class PlaybackPrewarmModule {
    @Binds
    @Singleton
    abstract fun bindPrewarmedPlayerFactory(impl: ContentExoPlayerPreparer): PrewarmedPlayerFactory
}

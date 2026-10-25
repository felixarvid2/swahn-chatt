package se.familychat.app.call

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import se.familychat.app.di.ApplicationScope
import se.familychat.core.client.FamilyChatEngine
import se.familychat.core.client.call.CallManager
import se.familychat.core.client.call.MediaSessionFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CallModule {
    @Binds
    abstract fun mediaSessionFactory(impl: WebRtcMediaSessionFactory): MediaSessionFactory

    companion object {
        /** One call manager for the process; [se.familychat.app.AppStarter] starts it so incoming offers always have a listener. */
        @Provides
        @Singleton
        fun callManager(engine: FamilyChatEngine, media: MediaSessionFactory, @ApplicationScope scope: CoroutineScope): CallManager =
            CallManager(engine, media, scope)
    }
}

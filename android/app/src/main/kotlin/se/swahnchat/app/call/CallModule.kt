package se.swahnchat.app.call

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import se.swahnchat.app.di.ApplicationScope
import se.swahnchat.core.client.SwahnChatEngine
import se.swahnchat.core.client.call.CallManager
import se.swahnchat.core.client.call.MediaSessionFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CallModule {
    @Binds
    abstract fun mediaSessionFactory(impl: WebRtcMediaSessionFactory): MediaSessionFactory

    companion object {
        /** One call manager for the process; [se.swahnchat.app.AppStarter] starts it so incoming offers always have a listener. */
        @Provides
        @Singleton
        fun callManager(engine: SwahnChatEngine, media: MediaSessionFactory, @ApplicationScope scope: CoroutineScope): CallManager =
            CallManager(engine, media, scope)
    }
}

package se.familychat.app.media

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import se.familychat.app.ui.components.PlaybackUi
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Plays one voice message at a time with Media3. All calls on the main thread. */
@Singleton
class VoicePlayer @Inject constructor(@ApplicationContext private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var currentId: String? = null

    private val _state = MutableStateFlow(PlaybackUi())
    val state: StateFlow<PlaybackUi> = _state.asStateFlow()

    private val ticker = object : Runnable {
        override fun run() {
            publish()
            if (player?.isPlaying == true) handler.postDelayed(this, 200)
        }
    }

    private fun ensurePlayer(): ExoPlayer = player ?: ExoPlayer.Builder(context).build().also { p ->
        p.setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
            true,
        )
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                publish()
                if (isPlaying) handler.post(ticker)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    p.seekTo(0)
                    p.pause()
                }
                publish()
            }

            override fun onPlayerError(error: PlaybackException) {
                stop()
            }
        })
        player = p
    }

    private fun publish() {
        val p = player
        val id = currentId
        _state.value = if (p == null || id == null) PlaybackUi() else PlaybackUi(
            attachmentId = id, playing = p.isPlaying, positionMs = p.currentPosition.coerceAtLeast(0),
            durationMs = p.duration.takeIf { it != C.TIME_UNSET } ?: 0,
        )
    }

    /** Starts [file] as attachment [attachmentId], or pauses/resumes if it is the one already loaded. */
    fun toggle(attachmentId: String, file: File) {
        val p = ensurePlayer()
        if (currentId == attachmentId) {
            if (p.isPlaying) p.pause() else p.play()
            publish()
            return
        }
        currentId = attachmentId
        p.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)))
        p.prepare()
        p.play()
        publish()
    }

    fun stop() {
        handler.removeCallbacks(ticker)
        player?.run { stop(); clearMediaItems() }
        currentId = null
        publish()
    }
}

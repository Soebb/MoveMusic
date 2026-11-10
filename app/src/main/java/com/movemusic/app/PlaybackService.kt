package com.movemusic.app

import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

@OptIn(UnstableApi::class)
private class StableQueueControlsPlayer(player: Player) : ForwardingSimpleBasePlayer(player) {
    override fun getState(): State {
        val state = super.getState()
        // Keep the lock-screen's previous/next slots stable while MainActivity
        // appends the rest of a large playlist in small, non-blocking batches.
        val commands = state.availableCommands.buildUpon()
            .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .build()
        return state.buildUpon().setAvailableCommands(commands).build()
    }
}

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 50_000, 250, 500)
            .build()
        val exoPlayer = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .build().apply {
                setAudioAttributes(AudioAttributes.DEFAULT, true)
                setHandleAudioBecomingNoisy(true)
                setWakeMode(C.WAKE_MODE_LOCAL)
            }
        // When shuffle is off, pause at the end of each track instead of
        // automatically advancing through the queued playlist.
        exoPlayer.setPauseAtEndOfMediaItems(true)
        player = exoPlayer

        val previousButton = CommandButton.Builder(CommandButton.ICON_PREVIOUS)
            .setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .setDisplayName("Previous")
            .setSlots(CommandButton.SLOT_BACK)
            .build()
        val playPauseButton = CommandButton.Builder(CommandButton.ICON_PLAY)
            .setPlayerCommand(Player.COMMAND_PLAY_PAUSE)
            .setDisplayName("Play/Pause")
            .setSlots(CommandButton.SLOT_CENTRAL)
            .build()
        val nextButton = CommandButton.Builder(CommandButton.ICON_NEXT)
            .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .setDisplayName("Next")
            .setSlots(CommandButton.SLOT_FORWARD)
            .build()

        mediaSession = MediaSession.Builder(this, StableQueueControlsPlayer(exoPlayer))
            .setMediaButtonPreferences(listOf(previousButton, playPauseButton, nextButton))
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SET_SHUFFLE) {
            val shuffleEnabled = intent.getBooleanExtra(EXTRA_SHUFFLE_ENABLED, false)
            player?.setPauseAtEndOfMediaItems(!shuffleEnabled)
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    companion object {
        const val ACTION_SET_SHUFFLE = "com.movemusic.app.action.SET_SHUFFLE"
        const val EXTRA_SHUFFLE_ENABLED = "shuffle_enabled"
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (player?.isPlaying != true) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.release()
        player?.release()
        mediaSession = null
        player = null
        super.onDestroy()
    }
}

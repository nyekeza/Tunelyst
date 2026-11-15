package com.tunelyst.player

import android.app.PendingIntent
import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.Build
import java.util.UUID
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private var listeningTracker: ListeningTracker? = null
    private var persistence: PlaybackPersistence? = null
    private var visualState: PlaybackVisualState.Publisher? = null
    private var artworkPublisher: MediaArtworkPublisher? = null
    private var smartShuffle: SmartShuffleController? = null
    private var sharedAudioContinuation: SharedAudioContinuation? = null
    private var sharedMetadata: SharedAudioMetadataController? = null
    private var equalizer: EqualizerController? = null
    private var crossfade: CrossfadeController? = null
    private val timerHandler = Handler(Looper.getMainLooper())
    private val artworkRefresh = Runnable { artworkPublisher?.refresh(); persistence?.retryRestore() }
    private val artworkObserver = object : android.database.ContentObserver(timerHandler) {
        override fun onChange(selfChange: Boolean) { timerHandler.removeCallbacks(artworkRefresh); timerHandler.postDelayed(artworkRefresh, 900) }
    }
    private var sleepTimer: SleepTimerController? = null
    private val timerToken = UUID.randomUUID().toString()
    private var alarmIntent: PendingIntent? = null
    private val timerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == SLEEP_ALARM && intent.getStringExtra("token") == timerToken) sleepTimer?.wake()
        }
    }
    companion object {
        const val RETRY_QUEUE_COMMAND = "com.tunelyst.player.RETRY_QUEUE"
        const val SMART_SHUFFLE_COMMAND = "com.tunelyst.player.SMART_SHUFFLE"
        const val SMART_SHUFFLE_MODE = "mode"
        const val EQUALIZER_COMMAND = "com.tunelyst.player.EQUALIZER"
        const val SLEEP_COMMAND = "com.tunelyst.player.SET_SLEEP_TIMER"
        const val SLEEP_MILLIS = "duration_ms"
        const val SLEEP_END = "end_of_song"
        const val SLEEP_FADE = "fade_out"
        private const val SLEEP_ALARM = "com.tunelyst.player.SLEEP_ALARM"
    }
    private fun cancelTimerAlarm() {
        alarmIntent?.let { (getSystemService(ALARM_SERVICE) as AlarmManager).cancel(it) }
    }
    private fun armTimerAlarm(deadline: Long) {
        val pending = alarmIntent ?: return
        // Inexact fallback can wake the running playback service if the CPU sleeps.
        runCatching { (getSystemService(ALARM_SERVICE) as AlarmManager).setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, deadline, pending) }
    }
    private val callback = object : MediaSession.Callback {
        @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
        override fun onConnectAsync(session: MediaSession, controller: MediaSession.ControllerInfo): ListenableFuture<MediaSession.ConnectionResult> {
            val builder = MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
            if (controller.packageName == packageName) {
                val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().add(SessionCommand(SLEEP_COMMAND, Bundle.EMPTY)).add(SessionCommand(SMART_SHUFFLE_COMMAND, Bundle.EMPTY)).add(SessionCommand(RETRY_QUEUE_COMMAND, Bundle.EMPTY)).add(SessionCommand(EQUALIZER_COMMAND, Bundle.EMPTY)).build()
                builder.setAvailableSessionCommands(commands)
            }
            return Futures.immediateFuture(builder.build())
        }
        override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
            if (controller.packageName != packageName) return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            if (customCommand.customAction == EQUALIZER_COMMAND) {
                val eq = equalizer ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_INVALID_STATE))
                val mode = args.getString("mode", "get")
                if (mode == "get") return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, eq.snapshot()))
                return Futures.immediateFuture(SessionResult(if (eq.apply(mode, args)) SessionResult.RESULT_SUCCESS else SessionError.ERROR_INVALID_STATE))
            }
            if (customCommand.customAction == RETRY_QUEUE_COMMAND) {
                persistence?.retryRestore()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            if (customCommand.customAction == SMART_SHUFFLE_COMMAND) {
                val shuffle = smartShuffle ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_INVALID_STATE))
                return when (args.getString(SMART_SHUFFLE_MODE)) {
                    "off" -> { shuffle.disable(); Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS)) }
                    "on" -> shuffle.enable(false)
                    "identity" -> shuffle.enable(true)
                    "move" -> {
                        val indices = args.getIntArray("indices")
                        val ids = args.getStringArrayList("ids")
                        val ok = indices != null && ids != null && shuffle.move(args.getInt("from", -1), args.getInt("to", -1), indices, ids)
                        Futures.immediateFuture(SessionResult(if (ok) SessionResult.RESULT_SUCCESS else SessionError.ERROR_INVALID_STATE))
                    }
                    "next", "append" -> {
                        val id = args.getLong("id", -1)
                        val title = args.getString("title"); val artist = args.getString("artist"); val album = args.getString("album")
                        val ok = id >= 0 && title != null && artist != null && album != null && shuffle.add(
                            Track(id, title, artist, album, args.getLong("albumId", -1), args.getLong("duration", 0)), args.getString(SMART_SHUFFLE_MODE) == "next")
                        Futures.immediateFuture(SessionResult(if (ok) SessionResult.RESULT_SUCCESS else SessionError.ERROR_BAD_VALUE))
                    }
                    else -> Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                }
            }
            if (customCommand.customAction != SLEEP_COMMAND) return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            val duration = args.getLong(SLEEP_MILLIS, -1L)
            val ok = sleepTimer?.set(duration, args.getBoolean(SLEEP_END), args.getBoolean(SLEEP_FADE)) == true
            return Futures.immediateFuture(SessionResult(if (ok) SessionResult.RESULT_SUCCESS else SessionError.ERROR_BAD_VALUE))
        }
    }

    // The unflagged branch is only reachable below API 33; a per-service token guards it there.
    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter(SLEEP_ALARM)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(timerReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(timerReceiver, filter)
        alarmIntent = PendingIntent.getBroadcast(this, 0, Intent(SLEEP_ALARM).setPackage(packageName).putExtra("token", timerToken), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            setHandleAudioBecomingNoisy(true)
            setPlaybackSpeed(TunelystSettings.savedSpeed(this@PlaybackService))
        }
        visualState = PlaybackVisualState.Publisher(player)
        equalizer = EqualizerController(this, player)
        smartShuffle = SmartShuffleController(this, player)
        sharedAudioContinuation = SharedAudioContinuation(this, player)
        sharedMetadata = SharedAudioMetadataController(this, player)
        listeningTracker = ListeningTracker(this, player)
        crossfade = CrossfadeController(this, player, listeningTracker!!)
        sleepTimer = SleepTimerController(player, { crossfade?.setSuppressed(it) }, { armTimerAlarm(it) }, { cancelTimerAlarm() })
        persistence = PlaybackPersistence(this, player)
        artworkPublisher = MediaArtworkPublisher(this, player)
        contentResolver.registerContentObserver(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, artworkObserver)
        contentResolver.registerContentObserver(android.provider.MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, true, artworkObserver)
        val launch = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val sessionPlayer = ManualTrackSwitchPlayer(player, { crossfade?.finishPendingNavigation() }) { index, id, command -> crossfade!!.manualSwitch(index, id, command) }
        session = MediaSession.Builder(this, sessionPlayer).setSessionActivity(launch).setCallback(callback).build()
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        if (controllerInfo.packageName == packageName || controllerInfo.isTrusted) session else null

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // UI_HIDDEN is normal after a Recents swipe; retain the snapshot in that case.
        if (level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) { MediaLibraryWarmCache.clear(); HomeContentCache.clearWarm() }
    }
    override fun onLowMemory() { super.onLowMemory(); MediaLibraryWarmCache.clear(); HomeContentCache.clearWarm() }
    override fun onDestroy() {
        MediaLibraryWarmCache.clear(); HomeContentCache.clearWarm()
        visualState?.release(); visualState = null
        sleepTimer?.release(); sleepTimer = null
        timerHandler.removeCallbacks(artworkRefresh); cancelTimerAlarm()
        contentResolver.unregisterContentObserver(artworkObserver)
        unregisterReceiver(timerReceiver); alarmIntent?.cancel(); alarmIntent = null
        crossfade?.release(); crossfade = null
        equalizer?.release(); equalizer = null
        sharedMetadata?.release(); sharedMetadata = null
        sharedAudioContinuation?.release(); sharedAudioContinuation = null
        smartShuffle?.release(); smartShuffle = null
        artworkPublisher?.release(); artworkPublisher = null
        persistence?.release(); persistence = null
        listeningTracker?.release()
        listeningTracker = null
        session?.let { it.player.release(); it.release() }
        session = null
        super.onDestroy()
    }
}

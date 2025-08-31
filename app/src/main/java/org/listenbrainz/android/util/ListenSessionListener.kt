package org.listenbrainz.android.util

import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager.OnActiveSessionsChangedListener
import android.media.session.PlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.listenbrainz.android.repository.listenservicemanager.ListenServiceManager
import org.listenbrainz.android.repository.preferences.AppPreferences
import org.listenbrainz.android.util.ListenSessionListener.Companion.isPlaying
import java.util.concurrent.ConcurrentHashMap

class ListenSessionListener(
    val appPreferences: AppPreferences,
    val listenServiceManager: ListenServiceManager,
    private val serviceScope: CoroutineScope
) : OnActiveSessionsChangedListener {
    private val availableSessions: ConcurrentHashMap<MediaController, ListenCallback?> = ConcurrentHashMap()
    private val activeSessions: ConcurrentHashMap<MediaController, ListenCallback?> = ConcurrentHashMap()
    val isMediaPlaying get() = activeSessions.any { it.key.playbackState?.isPlaying == true }

    @Synchronized
    override fun onActiveSessionsChanged(controllers: List<MediaController>?) {
        // d("onActiveSessionsChanged: EXECUTED")
        if (controllers == null) return
        clearSessions()
        registerControllers(controllers)
    }

    init {
        serviceScope.launch {
            appPreferences
                .listeningWhitelist.getFlow()
                .distinctUntilChanged()
                .collectLatest { whitelist ->
                    // Unregistering callback is reactive.
                    for (entry in activeSessions) {
                        if (entry.key.packageName !in whitelist) {
                            // Unregister listen callback
                            entry.key.unregisterCallback(entry.value!!)

                            // remove the active session.
                            activeSessions.remove(entry.key)
                            Log.d("### UNREGISTERED MediaController Callback for ${entry.key.packageName}.")
                        }
                    }

                    // Registering callback is reactive.
                    for (entry in availableSessions) {
                        if (!activeSessions.contains(entry.key.packageName) && entry.key.packageName in whitelist) {
                            // register listen callback
                            entry.key.registerCallback(entry.value!!)

                            // add to active sessions.
                            activeSessions[entry.key] = entry.value!!
                            Log.d("### REGISTERED MediaController Callback for ${entry.key.packageName}.")
                            break
                        }
                    }
                }
        }
    }

    private fun registerControllers(controllers: List<MediaController>) {
        val whitelist = runBlocking { appPreferences.listeningWhitelist.get() }

        fun MediaController.shouldListen(): Boolean = packageName in whitelist

        for (controller in controllers) {
            // BlackList
            if (!controller.shouldListen()){
                continue
            }
            val callback = ListenCallback(controller.packageName)
            activeSessions[controller] = callback
            availableSessions[controller] = callback
            controller.registerCallback(callback)

            // Force call the callback for the first time a controller is registered.
            callback.onMetadataChanged(controller.metadata)
            callback.onPlaybackStateChanged(controller.playbackState)

            Log.d("### REGISTERED MediaController callback for ${controller.packageName}.")
        }

        updateAppsList(controllers)
    }

    private fun updateAppsList(controllers: List<MediaController>) {
        // Adding any new app packages found in the notification.
        serviceScope.launch(Dispatchers.Default) {
            val shouldListenNewPlayer = appPreferences.shouldListenNewPlayers.get()
            fun addToWhiteList(packageName: String) {
                launch {
                    appPreferences.listeningWhitelist.getAndUpdate { whitelist ->
                        whitelist.toMutableList().plus(packageName)
                    }
                }
            }

            appPreferences.listeningApps.getAndUpdate {
                val appList = it.toMutableList()
                controllers.forEach { controller ->
                    if (controller.packageName !in appList){
                        if (shouldListenNewPlayer)
                            addToWhiteList(controller.packageName)
                        appList.add(controller.packageName)
                    }
                }
                return@getAndUpdate appList
            }
        }
    }

    fun clearSessions() {
        for ((controller, callback) in activeSessions) {
            controller.unregisterCallback(callback!!)
            Log.d("### UNREGISTERED MediaController Callback for ${controller.packageName}.")
        }
        activeSessions.clear()
        availableSessions.clear()
    }

    private inner class ListenCallback(private val player: String) : MediaController.Callback() {

        @Synchronized
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            listenServiceManager.onMetadataChanged(
                metadata = metadata,
                packageName = player,
                isMediaPlaying = isMediaPlaying
            )
        }

        @Synchronized
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            listenServiceManager.onPlaybackStateChanged(state)
        }
    }

    companion object {
        inline val PlaybackState.isPlaying: Boolean
            get() = state in listOf(
                PlaybackState.STATE_PLAYING,
                PlaybackState.STATE_BUFFERING,
                PlaybackState.STATE_FAST_FORWARDING,
                PlaybackState.STATE_REWINDING,
                PlaybackState.STATE_SKIPPING_TO_NEXT,
                PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
                PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM
            )
    }
}

package com.torfilx.core.player

import com.torfilx.core.data.remote.MediaRemoteSource
import com.torfilx.core.model.PlaybackInfo
import com.torfilx.core.player.capability.DeviceCapabilitiesProvider
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches playback information and remembers which sources have already failed for an item.
 *
 * The failure memory is what implements "direct play failed once → use the transcode for this item"
 * (plan.md §7.2) without re-attempting the broken source on every seek or replay in this session.
 */
@Singleton
class PlaybackInfoRepository @Inject constructor(
    private val remote: MediaRemoteSource,
    private val capabilitiesProvider: DeviceCapabilitiesProvider,
) {

    private val failedSources = ConcurrentHashMap<String, MutableSet<String>>()

    suspend fun playbackInfo(itemId: String): PlaybackInfo =
        remote.playbackInfo(itemId, capabilitiesProvider.capabilities())

    fun failedSourceIds(itemId: String): Set<String> = failedSources[itemId]?.toSet() ?: emptySet()

    fun markSourceFailed(itemId: String, sourceId: String) {
        failedSources.getOrPut(itemId) { ConcurrentHashMap.newKeySet() }.add(sourceId)
    }

    fun clearFailures(itemId: String) {
        failedSources.remove(itemId)
    }

    fun capabilities() = capabilitiesProvider.capabilities()
}

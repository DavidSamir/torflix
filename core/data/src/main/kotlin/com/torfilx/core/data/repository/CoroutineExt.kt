package com.torfilx.core.data.repository

import com.torfilx.core.common.log.TorfilxLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Fire-and-forget remote call: failures are logged, never propagated.
 *
 * Used for best-effort server notifications whose local effect has already been persisted (e.g.
 * removing an item from Continue Watching). The pending-write outbox is what guarantees eventual
 * consistency, so losing one of these is harmless.
 */
internal fun CoroutineScope.runCatchingRemote(
    tag: String,
    description: String,
    block: suspend () -> Unit,
) {
    launch {
        runCatching { block() }
            .onFailure { TorfilxLog.w(tag, "Best-effort call failed: $description", it) }
    }
}

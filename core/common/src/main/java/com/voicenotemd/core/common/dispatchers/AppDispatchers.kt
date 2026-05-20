package com.voicenotemd.core.common.dispatchers

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Bundle of coroutine dispatchers, injected so tests can swap them for [TestDispatcher].
 * The default Android binding is provided in :core:common's Hilt module (when wired up).
 */
data class AppDispatchers(
    val default: CoroutineDispatcher,
    val io: CoroutineDispatcher,
    val main: CoroutineDispatcher,
    val unconfined: CoroutineDispatcher,
)

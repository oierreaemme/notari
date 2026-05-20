package com.voicenotemd.core.common.di

import com.voicenotemd.core.common.dispatchers.AppDispatchers
import kotlinx.coroutines.Dispatchers

/**
 * Pure Kotlin factory for the production dispatcher bundle. Lives in :core:common so
 * it has no Hilt / Android dependencies — the Hilt @Module that exposes this lives in
 * :app (see `app/.../di/AppModule.kt`).
 *
 * In tests, build [AppDispatchers] directly from a `TestDispatcher` instead.
 */
fun defaultAppDispatchers(): AppDispatchers =
    AppDispatchers(
        default = Dispatchers.Default,
        io = Dispatchers.IO,
        main = Dispatchers.Main,
        unconfined = Dispatchers.Unconfined,
    )

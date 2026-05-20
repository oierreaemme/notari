// The `Destinations` route-name constants in this file use PascalCase, matching the
// Compose-navigation convention used widely in the Now in Android sample and the
// AndroidX docs. The ktlint standard `property-naming` rule wants SCREAMING_SNAKE_CASE
// for top-level `const val`s; that rule fires on a stylistic difference, not on a real
// bug, and renaming the constants would ripple into every NavHost / composable call site
// for cosmetic gain only. Suppress the rule at file scope so the rest of the codebase
// still benefits from property-naming enforcement.
@file:Suppress("ktlint:standard:property-naming")

package com.voicenotemd.app.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.voicenotemd.feature.capture.CaptureRoute
import com.voicenotemd.feature.notedetail.NoteDetailRoute
import com.voicenotemd.feature.notes.NotesRoute
import com.voicenotemd.feature.onboarding.OnboardingRoute
import com.voicenotemd.feature.settings.SettingsRoute

internal object Destinations {
    const val Onboarding = "onboarding"
    const val Capture = "capture?appendId={appendId}"
    const val Notes = "notes"
    const val NoteDetail = "noteDetail/{noteId}"
    const val Settings = "settings"

    fun noteDetail(noteId: String) = "noteDetail/$noteId"

    fun capture(appendId: String? = null) = if (appendId != null) "capture?appendId=$appendId" else "capture"
}

/**
 * The single navigation graph. Per ADR 0001 the home screen IS capture — there's no hub.
 *
 * Onboarding sits at the start of the back stack. `OnboardingRoute` flips
 * `state.isCompleted` to true either via the user's `Finish` / `Skip` intent OR
 * synchronously in `OnboardingViewModel.init` when prior-launch settings already
 * carry `hasCompletedOnboarding = true`. Either way, the route's
 * `LaunchedEffect(state.isCompleted) { if (isCompleted) onCompleted() }` fires the
 * navigation below, which `popUpTo(Onboarding) { inclusive = true }` so back-press
 * never returns to the welcome flow. The state-based path replaces an earlier
 * event-based design that had a SharedFlow replay-0 race for returning users.
 */
@Composable
fun VoiceNoteNavHost() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Destinations.Onboarding,
    ) {
        composable(Destinations.Onboarding) {
            OnboardingRoute(
                onCompleted = {
                    navController.navigate(Destinations.capture()) {
                        popUpTo(Destinations.Onboarding) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }
        composable(
            route = Destinations.Capture,
            arguments =
                listOf(
                    navArgument("appendId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
        ) {
            CaptureRoute(
                onOpenNotes = { navController.navigate(Destinations.Notes) },
                onOpenSettings = { navController.navigate(Destinations.Settings) },
                onNoteSaved = { id -> navController.navigate(Destinations.noteDetail(id)) },
            )
        }
        composable(Destinations.Notes) {
            NotesRoute(
                onBack = { navController.popBackStack() },
                onNoteClick = { id -> navController.navigate(Destinations.noteDetail(id)) },
            )
        }
        composable(
            route = Destinations.NoteDetail,
            arguments = listOf(navArgument("noteId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val noteId = backStackEntry.arguments?.getString("noteId").orEmpty()
            NoteDetailRoute(
                noteId = noteId,
                onBack = { navController.popBackStack() },
                onAppendVoice = { appendId -> navController.navigate(Destinations.capture(appendId)) },
            )
        }
        composable(Destinations.Settings) {
            SettingsRoute(onBack = { navController.popBackStack() })
        }
    }
}

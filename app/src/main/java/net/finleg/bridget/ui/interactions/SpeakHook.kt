package net.finleg.bridget.ui.interactions

import android.content.Context

/**
 * Play aloud (UI-16) without a compile-time dependency on `interact/Speaker.kt`, which another worker writes.
 * The integrator sets `SpeakHook.speak = { ctx, agent, title, text -> Speaker.speak(ctx, agent, title, text) }`
 * (for example in BridgeApp.onCreate). While it is null, the detail screen hides Play aloud.
 */
object SpeakHook {
    /** (context, agent name, title, text) */
    @Volatile var speak: ((Context, String, String, String?) -> Unit)? = null
}

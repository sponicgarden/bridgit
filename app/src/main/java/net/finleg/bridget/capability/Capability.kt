package net.finleg.bridget.capability

import io.ktor.server.routing.Route
import kotlinx.coroutines.sync.Mutex
import net.finleg.bridget.BridgeApp
import net.finleg.bridget.api.ApiServer
import net.finleg.bridget.api.CapInfo
import net.finleg.bridget.api.EndpointSpec
import net.finleg.bridget.api.ErrorCatalog
import org.json.JSONObject

/**
 * A capability module (ARC-05): its switch, the Android permissions it needs,
 * its endpoints, and a status() used by /v1/capabilities, Features and Access.
 * Adding a capability means adding one module to [Registry].
 */
abstract class Capability(protected val app: BridgeApp) {
    abstract val id: String
    abstract val titleRes: Int
    abstract val descriptionRes: Int
    /** Features screen group (UI-18). */
    abstract val group: String
    /** Master switch key, or null for always-on modules (status, log). */
    open val switchKey: String? = null
    open val permissions: List<String> = emptyList()
    abstract val endpoints: List<EndpointSpec>

    val title: String get() = app.getString(titleRes)
    val enabled: Boolean get() = switchKey?.let { app.settings.isOn(it) } ?: true

    /** Null when ready; otherwise a blocked_reason like "permission:CAMERA" or "open_app_once" (API-09). */
    open fun blockedReason(): String? = null
    open fun details(): JSONObject = JSONObject()

    fun status(): CapInfo {
        val blocked = blockedReason()
        return CapInfo(id, title, switchKey, enabled, blocked == null, blocked, details())
    }

    abstract fun Route.install(api: ApiServer)

    protected fun missingPermission(): String? =
        permissions.firstOrNull { !app.granted(it) }?.let { "permission:" + it.substringAfterLast('.') }

    /** Throws the right error unless the switch is on and the module is ready (PRD-05: fail closed). */
    protected fun requireUsable() {
        if (!enabled) throw ErrorCatalog.error("capability_disabled", "$title is switched off on the phone. Ask Rahul to turn it on in Features.",
            mapOf("setting" to switchKey))
        val b = blockedReason() ?: return
        when {
            b.startsWith("permission:") -> throw ErrorCatalog.error("permission_denied",
                "Bridget doesn't have the ${b.removePrefix("permission:")} permission. Rahul can grant it on the Access screen.",
                mapOf("permission" to "android.permission." + b.removePrefix("permission:")))
            b == "notifications_off" -> throw ErrorCatalog.error("notifications_required",
                "Bridget's notifications are off, so captures are refused. Ask Rahul to turn them on.")
            b == "open_app_once" -> throw ErrorCatalog.error("service_start_blocked",
                "Android hasn't given Bridget's listener the $title yet. Ask Rahul to open Bridget once.")
            else -> throw ErrorCatalog.error("service_start_blocked", "$title isn't ready: $b")
        }
    }
}

/** Every capability module in this build. Unbuilt capabilities are simply absent. */
class Registry(app: BridgeApp) {
    /** One capture per sensor at a time (NFR-11). */
    val cameraLock = Mutex()
    val micLock = Mutex()

    val modules: List<Capability> = listOf(
        StatusModule(app), CameraModule(app, this), MicModule(app, this), GalleryModule(app), LogModule(app),
        net.finleg.bridget.feedback.FeedbackModule(app),
        net.finleg.bridget.interact.InteractionModule(app),
        LocationModule(app), SpeakerModule(app), net.finleg.bridget.tour.TourModule(app),
    )

    fun caps(): List<net.finleg.bridget.api.CapInfo> = modules.map { it.status() }
    fun switchable(): List<Capability> = modules.filter { it.switchKey != null }
    fun byId(id: String) = modules.firstOrNull { it.id == id }
}

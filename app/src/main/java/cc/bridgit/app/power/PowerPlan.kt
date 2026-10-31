package cc.bridgit.app.power

/**
 * Maximum powers: the ordered checklist of Android access this build can use.
 * Built from what the merged manifest declares (runtime permissions, special-access
 * permissions, accessibility and notification-listener services), so a build that
 * declares less (GATE-03) offers less, and access added later joins by itself.
 * Pure logic; [PowerAccess] feeds it from Android and opens the real pages.
 */
object PowerPlan {
    enum class Kind { RUNTIME, SPECIAL, RESTRICTED }

    /**
     * One row. [permissions] for RUNTIME steps; [target] is the declared permission
     * or the service's flattened component for SPECIAL steps.
     */
    data class Step(val id: String, val kind: Kind, val permissions: List<String> = emptyList(), val target: String? = null)

    enum class Status { ON, OFF, DECLINED }

    const val NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
    const val HEALTH_PREFIX = "android.permission.health."
    const val A11Y = "accessibility:"
    const val LISTENER = "listener:"
    const val RESTRICTED = "restricted"

    /** Runtime groups in display order; a dangerous permission not listed here gets its own row. */
    private val GROUPS = linkedMapOf(
        "notifications" to listOf(NOTIFICATIONS),
        "camera" to listOf("android.permission.CAMERA"),
        "mic" to listOf("android.permission.RECORD_AUDIO"),
        "photos" to listOf("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO",
            "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"),
        "location" to listOf("android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION"),
        "background_location" to listOf("android.permission.ACCESS_BACKGROUND_LOCATION"),
        "activity" to listOf("android.permission.ACTIVITY_RECOGNITION"),
    )

    /** Special access Android grants on its own settings page, keyed by the permission that unlocks it. */
    val SPECIAL = linkedMapOf(
        "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" to "battery",
        "android.permission.PACKAGE_USAGE_STATS" to "usage",
        "android.permission.WRITE_SETTINGS" to "write_settings",
        "android.permission.ACCESS_NOTIFICATION_POLICY" to "dnd",
        "android.permission.REQUEST_INSTALL_PACKAGES" to "installs",
    )

    /**
     * The steps for this build.
     * @param declared every permission the manifest requests
     * @param dangerous whether a declared permission is a runtime ("dangerous") one
     * @param accessibility flattened components of Bridgit's accessibility services
     * @param listeners flattened components of Bridgit's notification listeners
     * @param sideloaded installed from a file rather than a store, so Android may block
     *   "restricted settings" (accessibility, notification access) until the owner allows them
     */
    fun steps(declared: Collection<String>, dangerous: (String) -> Boolean, accessibility: List<String>,
              listeners: List<String>, sideloaded: Boolean): List<Step> {
        val runtime = declared.filter { dangerous(it) || it.startsWith(HEALTH_PREFIX) }.toSet()
        val out = mutableListOf<Step>()
        val grouped = mutableSetOf<String>()
        for ((id, perms) in GROUPS) {
            val mine = perms.filter { it in runtime }
            grouped += mine
            // "Select photos" alone isn't a row: it rides along with images or video.
            if (mine.isNotEmpty() && mine != listOf("android.permission.READ_MEDIA_VISUAL_USER_SELECTED"))
                out += Step(id, Kind.RUNTIME, mine)
        }
        val health = runtime.filter { it.startsWith(HEALTH_PREFIX) }.sorted()
        grouped += health
        runtime.filter { it !in grouped }.sorted().forEach { out += Step("perm:" + it.substringAfterLast('.'), Kind.RUNTIME, listOf(it)) }
        if (health.isNotEmpty()) out += Step("health", Kind.RUNTIME, health)
        for ((perm, id) in SPECIAL) if (perm in declared) out += Step(id, Kind.SPECIAL, target = perm)
        listeners.forEach { out += Step(LISTENER + it, Kind.SPECIAL, target = it) }
        accessibility.forEach { out += Step(A11Y + it, Kind.SPECIAL, target = it) }
        if (sideloaded && (listeners.isNotEmpty() || accessibility.isNotEmpty())) out += Step(RESTRICTED, Kind.RESTRICTED)
        return out
    }

    /** Steps Android may hold back as a "restricted setting" on a sideloaded install. */
    fun isRestrictable(s: Step) = s.id.startsWith(A11Y) || s.id.startsWith(LISTENER)

    /**
     * Live status of each step. [granted] says whether a step's access is on; [declined] holds
     * steps the owner was shown and came back from still off. The restricted-settings step is
     * on once every restrictable step is on, and only shown after one of them was declined.
     */
    fun status(steps: List<Step>, granted: (Step) -> Boolean, declined: Set<String>): Map<String, Status> {
        val out = LinkedHashMap<String, Status>()
        for (s in steps) if (s.kind != Kind.RESTRICTED) out[s.id] = when {
            granted(s) -> Status.ON
            s.id in declined -> Status.DECLINED
            else -> Status.OFF
        }
        steps.firstOrNull { it.kind == Kind.RESTRICTED }?.let { r ->
            val restrictable = steps.filter(::isRestrictable)
            when {
                restrictable.all { out[it.id] == Status.ON } -> out[r.id] = Status.ON
                restrictable.any { out[it.id] == Status.DECLINED } -> out[r.id] = if (r.id in declined) Status.DECLINED else Status.OFF
                // Not needed (yet): hidden.
            }
        }
        return out
    }

    /** The next step the guided pass opens: runtime prompts first, then each page in order. Declined steps are skipped. */
    fun next(steps: List<Step>, status: Map<String, Status>): Step? = steps.firstOrNull { status[it.id] == Status.OFF }

    /** Every runtime permission still to ask for, in one batch (Android shows its dialogs one after another). */
    fun pendingRuntime(steps: List<Step>, status: Map<String, Status>, permissionGranted: (String) -> Boolean): List<String> =
        steps.filter { it.kind == Kind.RUNTIME && status[it.id] == Status.OFF && it.id != "health" }
            .flatMap { it.permissions }.filter { !permissionGranted(it) }
}

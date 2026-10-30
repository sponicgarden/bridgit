package net.finleg.bridget.interact

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import net.finleg.bridget.BridgeApp
import net.finleg.bridget.R
import java.io.File

/**
 * Pure rules for interaction notifications and speech (CAP-31, CAP-33, CAP-41).
 * No Android types, so they are unit-tested.
 */
object NotifRules {
    /** Android shows at most three actions on a notification. */
    const val MAX_ACTIONS = 3
    const val SPEAK_TEXT_MAX = 500

    /**
     * Which controls become notification actions, in order:
     * - question: its buttons first (at most three); then its first text control as an inline "Reply"
     *   if a slot is left; links only when the question has no buttons (filling the slots left);
     *   choices never (tapping the notification opens the card);
     * - notice: its links (at most three).
     */
    fun pickActions(kind: IKind, controls: List<Control>): List<Control> = when (kind) {
        IKind.QUESTION -> {
            val buttons = controls.filterIsInstance<Control.Button>().take(MAX_ACTIONS)
            val out = buttons.toMutableList<Control>()
            controls.firstOrNull { it is Control.Text }?.let { if (out.size < MAX_ACTIONS) out += it }
            if (buttons.isEmpty()) controls.filterIsInstance<Control.Link>().forEach { if (out.size < MAX_ACTIONS) out += it }
            out
        }
        IKind.NOTICE -> controls.filterIsInstance<Control.Link>().take(MAX_ACTIONS)
        else -> emptyList()
    }

    /** "<Agent> says: <title>. <text>", text cut to 500 characters. */
    fun utterance(agent: String, title: String, text: String?): String {
        val t = title.trim().trimEnd('.', '!', '?').ifEmpty { title.trim() }
        val body = text?.trim()?.take(SPEAK_TEXT_MAX)?.takeIf { it.isNotEmpty() }
        return "$agent says: $t." + (body?.let { " $it" } ?: "")
    }

    /**
     * CAP-31 speak: never unless the agent asked; never during a phone call (SPK-03), urgent or not; when the
     * phone is silent, vibrate or DND only an urgent one, and only if the owner allowed it in Settings.
     */
    fun shouldSpeak(speak: Boolean, inCall: Boolean, quiet: Boolean, urgent: Boolean, urgentInQuietAllowed: Boolean): Boolean =
        speak && !inCall && (!quiet || (urgent && urgentInQuietAllowed))

    enum class LinkOpen { VIEW, DIAL, LAUNCH, NONE }

    /** How [LinkActivity] opens a link control. Re-checks the CAP-31 allowlist; anything else is NONE. */
    fun linkOpen(target: String?, pkg: String?): LinkOpen = when {
        target == null -> if (pkg.isNullOrBlank()) LinkOpen.NONE else LinkOpen.LAUNCH
        InteractionRules.linkShownAs(target) == null -> LinkOpen.NONE
        target.trim().lowercase().startsWith("tel:") -> LinkOpen.DIAL
        else -> LinkOpen.VIEW
    }
}

/** Builds, posts and clears interaction notifications (CAP-33) and starts speech (CAP-31). */
object InteractionNotifier {
    const val CHANNEL_QUESTIONS = "questions"
    const val CHANNEL_NOTICES = "notices"
    /** Same string as MainActivity.EXTRA_INTERACTION_ID (added by the screens worker). */
    const val EXTRA_INTERACTION_ID = "interaction_id"
    const val EXTRA_CONTROL_ID = "control_id"
    const val PREF_LOCKSCREEN_TEXT = "lockscreen_text"
    const val PREF_SPEAK_URGENT_IN_DND = "speak_urgent_in_dnd"

    fun notifId(interactionId: Long) = 7000 + interactionId.toInt()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Registered in BridgeApp: runs on the thread that changed the store, so it stays cheap. */
    fun onChange(ctx: Context, i: Interaction, what: String) {
        val app = ctx.applicationContext as BridgeApp
        runCatching {
            when (what) {
                "created" -> { post(ctx, i); maybeSpeak(ctx, i) }
                "answered", "dismissed", "cancelled" -> cancel(ctx, i.id)
                // A question stays in the shade after it expires: the owner can still answer it late.
                "expired" -> if (i.kind == IKind.NOTICE || i.kind == IKind.TEXT) cancel(ctx, i.id)
                "seen" -> if (i.kind == IKind.NOTICE || i.kind == IKind.TEXT) cancel(ctx, i.id)
            }
        }.onFailure { app.log.system("diagnostic", "interaction", "Couldn't update the notification for interaction ${i.id}: ${it.javaClass.simpleName}", level = "error") }
    }

    fun cancel(ctx: Context, interactionId: Long) =
        ctx.getSystemService(NotificationManager::class.java).cancel(notifId(interactionId))

    private fun post(ctx: Context, i: Interaction) {
        val app = ctx.applicationContext as BridgeApp
        val nid = notifId(i.id)
        // Questions and HTML cards wait for the owner's answer; notices and text panels only tell them.
        val question = i.kind == IKind.QUESTION || i.kind == IKind.HTML
        val title = SpannableStringBuilder().apply {
            val head = ctx.getString(when (i.kind) {
                IKind.QUESTION -> R.string.interact_notif_asks
                IKind.NOTICE -> R.string.interact_notif_notice
                IKind.HTML -> R.string.interact_notif_card // CAP-34: "<Agent> sent a card: <title>", opens the detail
                IKind.TEXT -> R.string.interact_notif_streaming // D26: "<Agent> is streaming: <title>", opens the panel
            }, i.agent)
            append(head, StyleSpan(Typeface.BOLD), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append(" ").append(i.title)
        }
        val open = PendingIntent.getActivity(ctx, nid * 10,
            Intent().setClassName(ctx, "net.finleg.bridget.ui.MainActivity").putExtra(EXTRA_INTERACTION_ID, i.id)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val urgent = i.priority == "urgent"
        val b = NotificationCompat.Builder(ctx, if (question) CHANNEL_QUESTIONS else CHANNEL_NOTICES)
            .setSmallIcon(R.drawable.ic_notif).setColor(ctx.getColor(R.color.brand_primary))
            .setContentTitle(title)
            .setContentText(i.text)
            .setContentIntent(open).setAutoCancel(true)
            .setWhen(i.createdAt).setShowWhen(true)
            .setCategory(if (question) NotificationCompat.CATEGORY_MESSAGE else NotificationCompat.CATEGORY_REMINDER)
            .setPriority(if (urgent) NotificationCompat.PRIORITY_MAX else if (question) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setLargeIcon(avatar(ctx, i.agent))
        if (!question && i.expiresAt > i.createdAt) b.setTimeoutAfter((i.expiresAt - System.currentTimeMillis()).coerceAtLeast(1000))

        val picture = app.interactions.imageFile(i.image)?.let { decode(it, 1024) }
        if (picture != null) b.setStyle(NotificationCompat.BigPictureStyle().bigPicture(picture).setSummaryText(i.text))
        else if (!i.text.isNullOrEmpty()) b.setStyle(NotificationCompat.BigTextStyle().bigText(i.text))

        NotifRules.pickActions(i.kind, i.controls).forEachIndexed { slot, c ->
            val code = nid * 10 + 1 + slot
            when (c) {
                is Control.Button -> b.addAction(0, c.label, PendingIntent.getBroadcast(ctx, code,
                    InteractionActionReceiver.answerIntent(ctx, i.id, c.id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                is Control.Text -> {
                    // The system writes the typed reply into this intent, so it must be mutable (explicit component).
                    val pi = PendingIntent.getBroadcast(ctx, code, InteractionActionReceiver.replyIntent(ctx, i.id, c.id),
                        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                    val ri = RemoteInput.Builder(InteractionActionReceiver.KEY_REPLY)
                        .setLabel(c.placeholder ?: c.label).build()
                    b.addAction(NotificationCompat.Action.Builder(0, ctx.getString(R.string.interact_reply), pi)
                        .addRemoteInput(ri).setAllowGeneratedReplies(false)
                        .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY).build())
                }
                is Control.Link -> b.addAction(0, ctx.getString(R.string.interact_open_link, c.label), PendingIntent.getActivity(ctx, code,
                    LinkActivity.intent(ctx, i.id, c.id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                is Control.Choice -> {}
            }
        }

        // CAP-33: the lock screen shows only who, unless the owner allowed the full text there.
        if (prefs(ctx).getBoolean(PREF_LOCKSCREEN_TEXT, false)) b.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        else b.setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(
            NotificationCompat.Builder(ctx, if (question) CHANNEL_QUESTIONS else CHANNEL_NOTICES)
                .setSmallIcon(R.drawable.ic_notif).setColor(ctx.getColor(R.color.brand_primary))
                .setContentTitle(ctx.getString(when (i.kind) {
                    IKind.QUESTION -> R.string.interact_public_question
                    IKind.NOTICE -> R.string.interact_public_notice
                    IKind.HTML -> R.string.interact_public_card
                    IKind.TEXT -> R.string.interact_public_streaming
                }, i.agent))
                .setContentIntent(open).setAutoCancel(true)
                .build())

        ctx.getSystemService(NotificationManager::class.java).notify(nid, b.build())
    }

    private fun maybeSpeak(ctx: Context, i: Interaction) {
        if (!i.speak) return
        val app = ctx.applicationContext as BridgeApp
        if (!app.settings.isOn("speaker") || app.agents.get(i.agentId)?.allows("speaker") != true) return
        val speaker = app.speaker
        val quiet = speaker.silenced()
        val allowed = prefs(ctx).getBoolean(PREF_SPEAK_URGENT_IN_DND, false)
        if (!NotifRules.shouldSpeak(true, speaker.inCall(), quiet, i.priority == "urgent", allowed)) return
        // SPK-01: the shared speaker queue, so the volume ceiling, the 5-item queue and Stop apply.
        speaker.speakText(i.agentId, i.agent, NotifRules.utterance(i.agent, i.title, i.text), allowInQuiet = quiet)
        val what = if (i.kind == IKind.QUESTION) "question" else "notice"
        app.log.system("audit", "interaction", "Read aloud: ${i.agent}'s $what")
    }

    /** A circle with the agent's initial, in the same colours as AgentAvatarView. */
    private fun avatar(ctx: Context, name: String): Bitmap {
        val size = (64 * ctx.resources.displayMetrics.density).toInt()
        val idx = Math.floorMod(name.lowercase().hashCode(), 8)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawCircle(size / 2f, size / 2f, size / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ctx.getColor(AVATAR_BG[idx]) })
        val fg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ctx.getColor(AVATAR_FG[idx]); textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD; textSize = size * 0.475f
        }
        c.drawText(name.firstOrNull()?.uppercase() ?: "?", size / 2f, size / 2f - (fg.descent() + fg.ascent()) / 2, fg)
        return bmp
    }

    /** Decodes [f] with its longest side at most about [maxDim] px. */
    private fun decode(f: File, maxDim: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    private val AVATAR_BG = intArrayOf(R.color.avatar_bg_0, R.color.avatar_bg_1, R.color.avatar_bg_2, R.color.avatar_bg_3,
        R.color.avatar_bg_4, R.color.avatar_bg_5, R.color.avatar_bg_6, R.color.avatar_bg_7)
    private val AVATAR_FG = intArrayOf(R.color.avatar_fg_0, R.color.avatar_fg_1, R.color.avatar_fg_2, R.color.avatar_fg_3,
        R.color.avatar_fg_4, R.color.avatar_fg_5, R.color.avatar_fg_6, R.color.avatar_fg_7)
}

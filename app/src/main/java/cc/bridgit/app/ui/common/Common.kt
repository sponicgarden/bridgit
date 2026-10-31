package cc.bridgit.app.ui.common

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.format.DateUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import cc.bridgit.app.R
import cc.bridgit.app.core.AgentRegistry
import cc.bridgit.app.databinding.RowLogEntryBinding
import cc.bridgit.app.log.LogEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** App version beside the outlined wordmark on Home. */
fun brandVersion(ctx: Context): String = runCatching {
    ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName?.substringBefore(' ') ?: ""
}.getOrDefault("")

/** Theme colour lookup. */
fun Context.themeColor(@AttrRes attr: Int): Int {
    val tv = TypedValue()
    theme.resolveAttribute(attr, tv, true)
    return tv.data
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

object Fmt {
    fun ago(ts: Long): String = if (ts <= 0) "never" else
        DateUtils.getRelativeTimeSpanString(ts, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
    fun time(ts: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))
    fun dateTime(ts: Long): String = SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(ts))
    fun day(ts: Long): String = when {
        DateUtils.isToday(ts) -> "Today"
        DateUtils.isToday(ts + DateUtils.DAY_IN_MILLIS) -> "Yesterday"
        else -> SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(ts))
    }
    fun bytes(n: Long?): String = when {
        n == null -> ""
        n < 1024 -> "$n B"
        n < 1024 * 1024 -> "${n / 1024} KB"
        else -> String.format(Locale.US, "%.1f MB", n / 1048576.0)
    }
    fun duration(ms: Long?): String = when {
        ms == null -> ""
        ms < 1000 -> String.format(Locale.US, "%.2f s", ms / 1000.0)
        else -> String.format(Locale.US, "%.1f s", ms / 1000.0)
    }
    fun uptime(ms: Long): String {
        val m = ms / 60_000
        return if (m < 60) "Up $m m" else "Up ${m / 60} h ${m % 60} m"
    }
}

/**
 * Plain sentences for log entries (UI-06, ui-spec 5.7): "Grok fetched the last
 * screenshot", "Claude couldn't use the camera". Technical detail goes in chips.
 */
object Sentences {
    fun of(e: LogEntry, agentName: String?): String {
        val who = agentName ?: e.agent
        if (e.kind == "request" && who != null) {
            val ok = (e.http ?: 0) < 400
            val ep = e.endpoint ?: ""
            val type = e.params?.optString("type", "") ?: ""
            val what = when {
                ep.endsWith("/image/last") -> when (type) { "screenshot" -> "the last screenshot"; "photo" -> "the last photo"; else -> "the last image" }
                ep.endsWith("/camera/snapshot") -> if (ok) return "$who took a photo" else "the camera"
                ep.endsWith("/screen/capture") -> if (ok) return "$who took a screenshot" else "a screenshot"
                ep.endsWith("/mic/record") -> if (ok) return "$who recorded a clip" else "the microphone"
                ep.contains("/gallery/") -> "a gallery photo"
                ep.endsWith("/health") -> if (ok) return "$who checked in" else "the health check"
                ep.endsWith("/guide") -> if (ok) return "$who read the guide" else "the guide"
                ep.endsWith("/capabilities") -> if (ok) return "$who checked what's on" else "the capability list"
                ep.startsWith("/v1/log") || ep.endsWith("/audit") -> if (ok) return "$who read the log" else "the log"
                ep.endsWith("/pair") -> return if (ok) "$who fetched its key with a code" else "Pairing code failed"
                else -> ep
            }
            return if (ok) "$who fetched $what" else "$who couldn't use $what"
        }
        if (e.kind == "request" && who == null) return when (e.result) {
            "unauthorized" -> "A call with a wrong or old key was refused"
            "rate_limited" -> "Calls from ${e.sourceIp ?: "an address"} were held back after wrong keys"
            "forbidden_source" -> "A call from outside Tailscale was refused"
            "not_found" -> "A call to an unknown address was refused"
            else -> e.message
        }
        if (e.kind == "note" && who != null) return "$who wrote a note"
        return e.message
    }

    /** Second line for errors and notes (the reason, or the note text). */
    fun detail(e: LogEntry): String? = when {
        e.kind == "note" -> "\"${e.message}\""
        (e.http ?: 0) >= 400 -> reason(e.result)
        else -> null
    }

    fun reason(code: String?): String? = when (code) {
        "capability_disabled" -> "It's switched off in Features."
        "permission_denied" -> "Android permission missing. See Access."
        "notifications_required" -> "Bridgit's notifications are off."
        "service_start_blocked" -> "Open Bridgit once to enable camera and mic."
        "device_locked" -> "The phone was locked."
        "unauthorized" -> "Wrong or old key."
        "rate_limited" -> "Too many requests."
        "sensor_busy" -> "Another capture was running."
        null, "ok" -> null
        else -> code
    }
}

/**
 * Agent avatar (UI-12): first letter on a tinted circle, colour picked by
 * hashing the name into 8 fixed pairs (ui-spec 2.1). Gear-less grey circle for Bridgit.
 */
class AgentAvatarView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fg = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private var letter = "?"

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun setAgent(name: String?) {
        letter = name?.firstOrNull()?.uppercase() ?: "B"
        val i = if (name == null) -1 else Math.floorMod(name.lowercase().hashCode(), 8)
        if (i < 0) {
            bg.color = context.themeColor(com.google.android.material.R.attr.colorSurfaceContainerHigh)
            fg.color = context.themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
        } else {
            bg.color = context.getColor(BG[i]); fg.color = context.getColor(FG[i])
        }
        contentDescription = name ?: "Bridget"
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val r = minOf(width, height) / 2f
        c.drawCircle(width / 2f, height / 2f, r, bg)
        fg.textSize = r * 0.95f
        c.drawText(letter, width / 2f, height / 2f - (fg.descent() + fg.ascent()) / 2, fg)
    }

    companion object {
        private val BG = intArrayOf(R.color.avatar_bg_0, R.color.avatar_bg_1, R.color.avatar_bg_2, R.color.avatar_bg_3,
            R.color.avatar_bg_4, R.color.avatar_bg_5, R.color.avatar_bg_6, R.color.avatar_bg_7)
        private val FG = intArrayOf(R.color.avatar_fg_0, R.color.avatar_fg_1, R.color.avatar_fg_2, R.color.avatar_fg_3,
            R.color.avatar_fg_4, R.color.avatar_fg_5, R.color.avatar_fg_6, R.color.avatar_fg_7)
    }
}

/**
 * One activity row (UI-17), used by Activity and Agent detail: time, avatar,
 * sentence, optional reason, and mono chips; error rows get a stripe and say "Error:" to TalkBack.
 */
class LogRowAdapter(private val agents: AgentRegistry, private val onClick: (LogEntry) -> Unit) :
    ListAdapter<LogEntry, LogRowAdapter.Holder>(DIFF) {

    class Holder(val b: RowLogEntryBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(RowLogEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(h: Holder, position: Int) {
        val e = getItem(position)
        val name = e.agentId?.let { agents.get(it)?.name } ?: e.agent
        val sentence = Sentences.of(e, name)
        val detail = Sentences.detail(e)
        h.b.avatar.setAgent(name)
        h.b.time.text = Fmt.time(e.ts)
        h.b.sentence.text = sentence
        h.b.detail.isVisible = detail != null
        h.b.detail.text = detail
        val chips = listOfNotNull(
            e.endpoint?.let { "${e.method ?: ""} $it".trim() },
            e.http?.let { if (it >= 400) "$it ${e.result}" else "$it" },
            e.bytesOut?.takeIf { it > 0 }?.let { Fmt.bytes(it) },
            e.durationMs?.let { Fmt.duration(it) },
        )
        bindChip(h.b.chip1, chips.getOrNull(0), false)
        bindChip(h.b.chip2, chips.getOrNull(1), e.isError)
        bindChip(h.b.chip3, chips.getOrNull(2), false)
        bindChip(h.b.chip4, chips.getOrNull(3), false)
        h.b.errorStripe.isVisible = e.isError
        h.b.root.contentDescription = (if (e.isError) "Error: " else "") + "${Fmt.time(e.ts)}, $sentence" +
            (detail?.let { ", $it" } ?: "") + (if (chips.isNotEmpty()) ", " + chips.joinToString(", ") else "")
        h.b.root.setOnClickListener { onClick(e) }
    }

    private fun bindChip(v: TextView, text: String?, error: Boolean) {
        v.isVisible = text != null
        v.text = text
        val ctx = v.context
        if (error) {
            v.setBackgroundResource(R.drawable.bg_mono_chip_error)
            v.setTextColor(ctx.themeColor(com.google.android.material.R.attr.colorOnErrorContainer))
        } else {
            v.setBackgroundResource(R.drawable.bg_mono_chip)
            v.setTextColor(ctx.themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<LogEntry>() {
            override fun areItemsTheSame(a: LogEntry, b: LogEntry) = a.id == b.id
            override fun areContentsTheSame(a: LogEntry, b: LogEntry) = a == b
        }
    }
}

/** What an agent card shows under the name: the model the agent reported (POST /v1/me), not the network path. */
fun cc.bridgit.app.core.Agent.modelText(ctx: Context): String =
    modelLabel ?: ctx.getString(cc.bridgit.app.R.string.agents_model_unknown)

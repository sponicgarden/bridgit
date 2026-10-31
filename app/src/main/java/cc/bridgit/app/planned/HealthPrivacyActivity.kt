package cc.bridgit.app.planned
/** Health Connect's permissions rationale: what Bridgit reads, and the privacy policy link Play requires. */
class HealthPrivacyActivity : androidx.appcompat.app.AppCompatActivity() {
    override fun onCreate(s: android.os.Bundle?) { super.onCreate(s)
        setContentView(android.widget.TextView(this).apply {
            text = getString(cc.bridgit.app.R.string.health_rationale, getString(cc.bridgit.app.R.string.settings_privacy_url))
            autoLinkMask = android.text.util.Linkify.WEB_URLS
            movementMethod = android.text.method.LinkMovementMethod.getInstance()
            textSize = 18f; setPadding(32, 48, 32, 32)
        })
    }
}

package cc.bridgit.app.planned
class HealthPrivacyActivity : androidx.appcompat.app.AppCompatActivity() {
    override fun onCreate(s: android.os.Bundle?) { super.onCreate(s)
        setContentView(android.widget.TextView(this).apply { text = "Health access in Bridgit\n\nBridgit reads only the steps, sleep and heart-rate source records you grant. It never writes health data. Reads require the Health Connect feature switch and the calling agent's grant. Data is returned to that agent on request; its provider may retain a copy. No health records are retained by Bridgit. Revoke permissions in Health Connect at any time."; textSize = 18f; setPadding(32, 48, 32, 32) })
    }
}

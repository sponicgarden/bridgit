package net.finleg.bridget

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Robolectric
import org.robolectric.shadows.ShadowDialog
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.ImageView
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentProfileTest {
    private val context get() = RuntimeEnvironment.getApplication() as Context
    private lateinit var policy: Policy

    @Before fun setup() {
        context.getSharedPreferences("policy", Context.MODE_PRIVATE).edit().clear().commit()
        policy = Policy(context)
    }

    private fun png(width: Int = 32, height: Int = 32): String {
        val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream()
        image.compress(Bitmap.CompressFormat.PNG, 100, bytes)
        image.recycle()
        return Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
    }

    private fun rejects(status: Int = 422, action: () -> Unit) {
        try { action(); fail("Expected ApiError") } catch (e: ApiError) { assertEquals(status, e.http) }
    }

    @Test fun profilePersistsAndSurvivesTouchAndRotation() {
        val (agent, token) = policy.addAgent("Original")
        val icon = png()
        val wordmark = png(300, 60)
        policy.updateProfile(agent.id, JSONObject().put("name", "Muse").put("icon_base64", icon).put("wordmark_base64", wordmark))
        policy.touch(agent.id)
        val reloaded = Policy(context).agents().single()
        assertEquals("Muse", policy.authenticate(token)!!.name)
        assertEquals(icon, reloaded.iconBase64)
        assertEquals(wordmark, reloaded.wordmarkBase64)
        val (rotated, newToken) = policy.rotate(agent.id)
        assertEquals(icon, rotated.iconBase64)
        assertEquals(wordmark, rotated.wordmarkBase64)
        assertNull(policy.authenticate(token))
        assertEquals(agent.id, policy.authenticate(newToken)!!.id)
        assertFalse(AgentBranding.json(rotated).has("hash"))
        assertFalse(AgentBranding.json(rotated).has("salt"))
    }

    @Test fun updatesOnlyCallerAndSupportsPartialUpdateAndClear() {
        val (first, _) = policy.addAgent("First")
        val (other, _) = policy.addAgent("Other")
        policy.updateProfile(first.id, JSONObject().put("icon_base64", png()).put("wordmark_base64", png()))
        policy.updateProfile(first.id, JSONObject().put("name", "Renamed"))
        assertNotNull(policy.agents().first { it.id == first.id }.iconBase64)
        policy.updateProfile(first.id, JSONObject().put("icon_base64", JSONObject.NULL))
        assertNull(policy.agents().first { it.id == first.id }.iconBase64)
        assertNotNull(policy.agents().first { it.id == first.id }.wordmarkBase64)
        assertEquals(other, policy.agents().first { it.id == other.id })
        rejects { policy.updateProfile(first.id, JSONObject().put("name", "other")) }
        rejects { policy.updateProfile(first.id, JSONObject().put("id", other.id)) }
        rejects { policy.updateProfile(first.id, JSONObject().put("name", JSONObject.NULL)) }
    }

    @Test fun invalidAssetsAreRejectedWithoutPartialSave() {
        val (agent, _) = policy.addAgent("Original")
        for (invalid in listOf("", "https://example.com/icon.png", "<svg/>", "abcd", 123)) {
            rejects { policy.updateProfile(agent.id, JSONObject().put("name", "Changed").put("icon_base64", invalid)) }
            assertEquals("Original", policy.agents().single().name)
        }
        rejects { policy.updateProfile(agent.id, JSONObject().put("icon_base64", png(513, 1))) }
        rejects { policy.updateProfile(agent.id, JSONObject().put("wordmark_base64", png(2049, 1))) }
        rejects { policy.updateProfile(agent.id, JSONObject().put("wordmark_base64", png(1, 513))) }
        rejects(413) { policy.updateProfile(agent.id, JSONObject().put("icon_base64", "A".repeat(180000))) }
    }

    @Test fun legacyAgentsWorkWithoutArtworkAndRemovedAgentCannotUpdate() {
        val (agent, _) = policy.addAgent("Legacy")
        val profile = AgentBranding.json(policy.agents().single())
        assertTrue(profile.isNull("icon_base64"))
        assertTrue(profile.isNull("wordmark_base64"))
        policy.remove(agent.id)
        rejects(401) { policy.updateProfile(agent.id, JSONObject()) }
    }
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    @Test fun confirmationDialogsRepeatNameIconAndWordmark() {
        val (initial, _) = policy.addAgent("Muse")
        val agent = policy.updateProfile(initial.id, JSONObject().put("icon_base64", png()).put("wordmark_base64", png(300, 60)))
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        for (method in listOf("rotateAgent", "removeAgent")) {
            MainActivity::class.java.getDeclaredMethod(method, Policy.Agent::class.java).apply { isAccessible = true }.invoke(activity, agent)
            val dialog = ShadowDialog.getLatestDialog()
            val views = descendants(dialog.window!!.decorView)
            assertTrue(views.filterIsInstance<TextView>().any { it.text.toString() == "Muse" })
            assertTrue(views.filterIsInstance<ImageView>().any { it.contentDescription == "Muse icon" })
            assertTrue(views.filterIsInstance<ImageView>().any { it.contentDescription == "Muse wordmark" })
            dialog.dismiss()
        }
        controller.destroy()
    }

    @Test fun identityWithoutArtworkStillDisplaysName() {
        val (agent, _) = policy.addAgent("Muse")
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        val view = MainActivity::class.java.getDeclaredMethod("agentIdentity", Policy.Agent::class.java)
            .apply { isAccessible = true }.invoke(activity, agent) as View
        assertTrue(descendants(view).filterIsInstance<TextView>().any { it.text.toString() == "Muse" })
        assertTrue(descendants(view).filterIsInstance<ImageView>().isEmpty())
        controller.destroy()
    }

    @Test fun activeAgentIdentityIsPinnedAboveScrollableContent() {
        val (initial, _) = policy.addAgent("Muse")
        val agent = policy.updateProfile(initial.id, JSONObject().put("icon_base64", png()).put("wordmark_base64", png(300, 60)))
        val serviceController = Robolectric.buildService(BridgeService::class.java).create()
        val service = serviceController.get()
        val running = BridgeService::class.java.getDeclaredField("running").apply { isAccessible = true }
        running.set(null, service)
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            service.showCapture("Muse is taking a photo", agent.id)
            val activity = controller.get()
            MainActivity::class.java.getDeclaredMethod("refresh").apply { isAccessible = true }.invoke(activity)
            val header = MainActivity::class.java.getDeclaredField("interactionBrand").apply { isAccessible = true }.get(activity) as ViewGroup
            assertEquals(View.VISIBLE, header.visibility)
            assertTrue(descendants(header).filterIsInstance<ImageView>().any { it.contentDescription == "Muse icon" })
            assertTrue(descendants(header).filterIsInstance<ImageView>().any { it.contentDescription == "Muse wordmark" })
            val frame = header.parent as ViewGroup
            assertSame(header, frame.getChildAt(0))
            assertTrue(frame.getChildAt(1) is android.widget.ScrollView)
            service.showCapture(null)
            MainActivity::class.java.getDeclaredMethod("refresh").apply { isAccessible = true }.invoke(activity)
            assertEquals(View.GONE, header.visibility)
        } finally {
            running.set(null, null)
            controller.destroy()
            serviceController.destroy()
        }
    }

}

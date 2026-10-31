package cc.bridgit.app.api

import cc.bridgit.app.ui.more.WhatCanData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The public API page: every card link and call resolves. Also writes build/apidocs/api.html for publish-apk.sh. */
class ApiDocsTest {
    private val titles = WhatCanData.all.map { it.title }

    @Test fun everyMappedCardAndCallExists() {
        for ((title, calls) in WhatCanData.endpoints) {
            assertTrue("No card titled \"$title\"", title in titles)
            calls.forEach { assertNotNull("$title: no endpoint $it", ApiDocs.find(it)) }
        }
    }

    @Test fun everyBuiltAbilityHasCalls() {
        val missing = WhatCanData.all.filter { it.stage == WhatCanData.Stage.NOW && it.kind == WhatCanData.Kind.ABILITY }
            .map { it.title }.filter { WhatCanData.endpoints[it].isNullOrEmpty() }
        assertEquals(emptyList<String>(), missing)
    }

    @Test fun anchorsAreUniqueAndStable() {
        val slugs = titles.map { ApiDocs.slug(it) }
        assertEquals(slugs.size, slugs.toSet().size)
        assertEquals("take-a-photo", ApiDocs.slug("Take a photo"))
        assertEquals("agents-learn-what-s-new", ApiDocs.slug("Agents learn what's new"))
        assertEquals("${ApiDocs.URL}#city-tour", ApiDocs.link("City tour"))
    }

    @Test fun pageHasEveryCardAndEndpointAndNoSecrets() {
        val html = ApiDocs.html(WhatCanData.apiCards(), "test")
        titles.forEach { assertTrue(it, html.contains("id=\"${ApiDocs.slug(it)}\"")) }
        Endpoints.all.forEach { assertTrue(it.fullPath, html.contains(ApiDocs.endpointKey(it))) }
        assertFalse(html.contains("brg_"))
        assertFalse(Regex("""100\.\d+\.\d+\.\d+""").containsMatchIn(html))
        val out = File("build/apidocs/api.html")
        out.parentFile.mkdirs()
        out.writeText(ApiDocs.html(WhatCanData.apiCards(), System.getProperty("bridget.version") ?: "dev"))
    }
}

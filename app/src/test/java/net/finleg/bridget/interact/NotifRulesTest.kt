package net.finleg.bridget.interact

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotifRulesTest {
    private fun btn(id: String) = Control.Button(id, id, "normal")
    private fun txt(id: String) = Control.Text(id, id, null)
    private fun ch(id: String) = Control.Choice(id, id, listOf("a", "b"))
    private fun link(id: String, url: String? = "https://example.com", pkg: String? = null) = Control.Link(id, id, url, pkg, "x")
    private fun ids(l: List<Control>) = l.map { it.id }

    @Test fun questionButtonsThenReply() {
        assertEquals(listOf("yes", "no", "note"), ids(NotifRules.pickActions(IKind.QUESTION, listOf(btn("yes"), btn("no"), txt("note")))))
    }

    @Test fun firstThreeButtonsWinOverReply() {
        val c = listOf(btn("a"), btn("b"), txt("t"), btn("c"), btn("d"))
        assertEquals(listOf("a", "b", "c"), ids(NotifRules.pickActions(IKind.QUESTION, c)))
    }

    @Test fun choicesNeverShown() {
        assertEquals(listOf("t"), ids(NotifRules.pickActions(IKind.QUESTION, listOf(ch("c"), txt("t"), txt("t2")))))
        assertTrue(NotifRules.pickActions(IKind.QUESTION, listOf(ch("c"))).isEmpty())
    }

    @Test fun linksSkippedWhenQuestionHasButtons() {
        assertEquals(listOf("yes"), ids(NotifRules.pickActions(IKind.QUESTION, listOf(link("l"), btn("yes")))))
    }

    @Test fun linksFillSlotsWithoutButtons() {
        val c = listOf(txt("t"), link("l1"), link("l2"), link("l3"), ch("c"))
        assertEquals(listOf("t", "l1", "l2"), ids(NotifRules.pickActions(IKind.QUESTION, c)))
    }

    @Test fun noticeLinksUpToThree() {
        val c = listOf(link("1"), link("2"), link("3"), link("4"))
        assertEquals(listOf("1", "2", "3"), ids(NotifRules.pickActions(IKind.NOTICE, c)))
        assertTrue(NotifRules.pickActions(IKind.NOTICE, emptyList()).isEmpty())
    }

    @Test fun utterance() {
        assertEquals("Grok says: Pay this invoice. Hostinger, due Oct 30.", NotifRules.utterance("Grok", "Pay this invoice?", "Hostinger, due Oct 30."))
        assertEquals("Grok says: Done.", NotifRules.utterance("Grok", "Done", null))
        assertEquals("Grok says: Done.", NotifRules.utterance("Grok", "Done", "  "))
        val long = "x".repeat(900)
        assertEquals("Grok says: T. " + "x".repeat(500), NotifRules.utterance("Grok", "T", long))
    }

    @Test fun speakGate() {
        // (speak, inCall, quiet, urgent, urgentInQuietAllowed)
        assertFalse(NotifRules.shouldSpeak(false, false, false, true, true))
        assertTrue(NotifRules.shouldSpeak(true, false, false, false, false))
        assertFalse(NotifRules.shouldSpeak(true, false, true, false, true))
        assertFalse(NotifRules.shouldSpeak(true, false, true, true, false))
        assertTrue(NotifRules.shouldSpeak(true, false, true, true, true))
        // SPK-03: a call always blocks speech, even urgent speech the owner allowed through DND.
        assertFalse(NotifRules.shouldSpeak(true, true, false, false, false))
        assertFalse(NotifRules.shouldSpeak(true, true, true, true, true))
        assertFalse(NotifRules.shouldSpeak(true, true, false, true, true))
    }

    @Test fun linkOpen() {
        assertEquals(NotifRules.LinkOpen.VIEW, NotifRules.linkOpen("https://example.com/a", null))
        assertEquals(NotifRules.LinkOpen.VIEW, NotifRules.linkOpen("geo:52.1,4.3", null))
        assertEquals(NotifRules.LinkOpen.VIEW, NotifRules.linkOpen("mailto:a@b.com", null))
        assertEquals(NotifRules.LinkOpen.DIAL, NotifRules.linkOpen("tel:+31201234567", null))
        assertEquals(NotifRules.LinkOpen.LAUNCH, NotifRules.linkOpen(null, "com.example.app"))
        assertEquals(NotifRules.LinkOpen.NONE, NotifRules.linkOpen("http://example.com", null))
        assertEquals(NotifRules.LinkOpen.NONE, NotifRules.linkOpen("javascript:alert(1)", null))
        assertEquals(NotifRules.LinkOpen.NONE, NotifRules.linkOpen("intent://x#Intent;end", null))
        assertEquals(NotifRules.LinkOpen.NONE, NotifRules.linkOpen(null, null))
    }
}

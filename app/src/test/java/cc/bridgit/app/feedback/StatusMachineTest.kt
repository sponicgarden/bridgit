package cc.bridgit.app.feedback

import cc.bridgit.app.feedback.ReportStatus.CONFIRMED
import cc.bridgit.app.feedback.ReportStatus.DUPLICATE
import cc.bridgit.app.feedback.ReportStatus.FIXED
import cc.bridgit.app.feedback.ReportStatus.IN_PROGRESS
import cc.bridgit.app.feedback.ReportStatus.NEW
import cc.bridgit.app.feedback.ReportStatus.REOPENED
import cc.bridgit.app.feedback.ReportStatus.SEEN
import cc.bridgit.app.feedback.ReportStatus.WONT_FIX
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Ways the FBK-11a status machine can fail:
 * a developer agent closes a report (confirmed) or reopens it; the owner's Confirm works on a report nobody
 * fixed; a fixed report jumps back to in_progress; `reopened` doesn't behave as `new`; the automatic
 * first-read move fires on a report already in progress; a disallowed move is silently accepted.
 * The test enumerates every (from, to, actor) triple against the spec table.
 */
class StatusMachineTest {
    private val expected: Set<Triple<ReportStatus, ReportStatus, Actor>> = buildSet {
        add(Triple(NEW, SEEN, Actor.AUTO)); add(Triple(REOPENED, SEEN, Actor.AUTO))
        for (f in listOf(NEW, SEEN, REOPENED)) for (t in listOf(IN_PROGRESS, DUPLICATE, WONT_FIX)) add(Triple(f, t, Actor.DEVELOPER))
        for (t in listOf(FIXED, WONT_FIX)) add(Triple(IN_PROGRESS, t, Actor.DEVELOPER))
        for (f in listOf(FIXED, WONT_FIX, DUPLICATE)) for (t in listOf(CONFIRMED, REOPENED)) add(Triple(f, t, Actor.OWNER))
    }

    @Test fun everyTransitionMatchesTheSpecTable() {
        var checked = 0
        for (f in ReportStatus.values()) for (t in ReportStatus.values()) for (a in Actor.values()) {
            assertEquals("$f -> $t by $a", Triple(f, t, a) in expected, StatusMachine.allowed(f, t, a))
            checked++
        }
        assertEquals(8 * 8 * 3, checked)
    }

    @Test fun ownerIsToldOnlyWhenThereIsSomethingToCheck() {
        assertEquals(setOf(FIXED, WONT_FIX, DUPLICATE), ReportStatus.values().filter { StatusMachine.notifiesOwner(it) }.toSet())
        assertEquals(listOf(IN_PROGRESS, WONT_FIX, DUPLICATE), StatusMachine.nextForDeveloper(REOPENED))
    }
}

package net.finleg.bridget.interact

import org.junit.Assert.*
import org.junit.Test

class AnswerResubmissionTest {
    @Test fun ordinarySubmissionsOnlyAcceptOpenInteractions() {
        IStatus.values().forEach { status ->
            assertEquals(status.name, status.open, status.acceptsAnswer())
        }
    }

    @Test fun explicitResubmissionAllowsAnsweredButNeverOtherClosedStates() {
        assertTrue(IStatus.ANSWERED.acceptsAnswer(resubmission = true))
        listOf(IStatus.CANCELLED, IStatus.DISMISSED, IStatus.EXPIRED).forEach { status ->
            assertFalse(status.name, status.acceptsAnswer(resubmission = true))
        }
        assertTrue(IStatus.PENDING.acceptsAnswer(resubmission = true))
        assertTrue(IStatus.SEEN.acceptsAnswer(resubmission = true))
    }
}

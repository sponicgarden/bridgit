package cc.bridgit.app.api

import io.ktor.server.cio.HttpServerSettings
import kotlinx.coroutines.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The listener's accept loop when its socket dies (Tailscale went away while bound). Ktor's own loop retries
 * a failed accept with no suspension point, so it spins and ignores cancel; ours must not.
 * A closed server socket stands in for one Android destroyed: every accept() then throws an IOException.
 */
class AcceptLoopTest {
    @Test fun stopCancelsLoopOnDeadSocket() = runBlocking {
        val s = CoroutineScope(Dispatchers.IO).httpServer(HttpServerSettings("127.0.0.1", 0)) { }
        s.serverSocket.await().close()
        delay(200)
        s.rootServerJob.cancel()
        assertNotNull("accept loop ignored cancel", withTimeoutOrNull(2000) { s.acceptJob.join(); true })
    }

    @Test fun deadSocketEndsServer() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val s = scope.httpServer(HttpServerSettings("127.0.0.1", 0), backoffMs = { 1L }) { }
        s.serverSocket.await().close()
        assertNotNull("dead listener did not end", withTimeoutOrNull(2000) { s.rootServerJob.join(); true })
        assertTrue(s.acceptJob.isCancelled)
        scope.cancel()
    }

    @Test fun backoffGrowsAndCaps() {
        assertEquals(50L, AcceptBackoff.delayMs(1))
        assertEquals(100L, AcceptBackoff.delayMs(2))
        assertEquals(800L, AcceptBackoff.delayMs(5))
        assertEquals(800L, AcceptBackoff.delayMs(50))
    }
}

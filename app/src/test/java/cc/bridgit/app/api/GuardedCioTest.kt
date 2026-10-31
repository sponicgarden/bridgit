package cc.bridgit.app.api

import cc.bridgit.app.api.engine.acceptBackoffMillis
import cc.bridgit.app.api.engine.acceptUntilClosed
import cc.bridgit.app.api.engine.guardedHttpServer
import io.ktor.server.cio.HttpServerSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.channels.ClosedChannelException
import java.util.concurrent.atomic.AtomicInteger

class GuardedCioTest {
    @Test
    fun backoffDoublesUpToOneSecond() {
        assertEquals(listOf(10L, 20L, 40L, 80L, 160L, 320L, 640L, 1000L, 1000L), (1..9).map { acceptBackoffMillis(it) })
    }

    /** Android destroys the socket when Tailscale drops and every accept fails at once: no spin, and stop still works. */
    @Test
    fun failingAcceptBacksOffAndCancels() = runBlocking {
        val calls = AtomicInteger()
        val job = launch(Dispatchers.IO) {
            acceptUntilClosed<Unit>({ calls.incrementAndGet(); throw IOException("Software caused connection abort") }) { }
        }
        delay(600)
        withTimeout(2000) { job.cancelAndJoin() }
        // 10+20+40+80+160+320 ms of backoff fit in 600 ms: about 7 tries. Stock CIO makes millions.
        assertTrue("accept tried ${calls.get()} times", calls.get() in 3..10)
    }

    @Test
    fun closedSocketEndsLoop() = runBlocking {
        var accepted = 0
        var n = 0
        withTimeout(2000) {
            acceptUntilClosed({ if (++n <= 2) n else throw ClosedChannelException() }) { accepted++ }
        }
        assertEquals(2, accepted)
    }

    @Test
    fun stopEndsRealServer() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = scope.guardedHttpServer(HttpServerSettings(host = "127.0.0.1", port = 0)) { }
        withTimeout(5000) { server.serverSocket.await() }
        server.rootServerJob.cancel()
        withTimeout(5000) { server.rootServerJob.join() }
        scope.cancel()
    }
}

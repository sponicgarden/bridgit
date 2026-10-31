/*
 * Adapted from Ktor 2.3.12 (Apache 2.0, Copyright 2014-2021 JetBrains s.r.o and contributors):
 * ktor-server/ktor-server-cio/jvmAndNix/src/io/ktor/server/cio/CIOApplicationEngine.kt and backend/HttpServer.kt.
 * Re-diff against upstream when Ktor is upgraded. Changes from upstream:
 * - accept loop: a failed accept() backs off before retrying, and 20 in a row throw [DeadListener];
 * - `backoffMs` parameter on [httpServer] (for tests) and the [BridgitCioEngine.stopped] accessor;
 * - no JVM shutdown hook (Android; [ApiServer] stops the engine itself);
 * - `Dispatchers.IO` for upstream's internal `Dispatchers.IOBridge` (the same dispatcher on JVM);
 * - connection failures are logged through a Ktor logger instead of upstream's internal handler.
 * The file suppresses INVISIBLE_* to reuse CIO's internal call classes: it is tied to Ktor 2.3.12.
 */
@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(io.ktor.util.InternalAPI::class)

package cc.bridgit.app.api

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.awaitClosed
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.events.raiseCatching
import io.ktor.server.application.ApplicationStopPreparing
import io.ktor.server.application.ServerReady
import io.ktor.network.sockets.toJavaAddress
import io.ktor.server.cio.CIOApplicationCall
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.cio.CIOApplicationRequest
import io.ktor.server.cio.HttpServer
import io.ktor.server.cio.HttpServerSettings
import io.ktor.server.cio.HttpRequestHandler
import io.ktor.server.cio.backend.ServerIncomingConnection
import io.ktor.server.cio.backend.ServerRequestScope
import io.ktor.server.cio.backend.startServerConnectionPipeline
import io.ktor.server.cio.internal.WeakTimeoutQueue
import io.ktor.server.engine.*
import io.ktor.server.request.ApplicationReceivePipeline
import io.ktor.server.request.httpVersion
import io.ktor.server.response.respond
import io.ktor.http.cio.Request
import io.ktor.util.logging.KtorSimpleLogger
import io.ktor.util.pipeline.PipelinePhase
import io.ktor.util.pipeline.execute
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.*
import java.io.IOException
import java.net.InetSocketAddress

/**
 * Ktor's CIO engine with one change: the accept loop. Upstream retries a failed `accept()` at once with no
 * suspension point, so when the address goes away (Android destroys sockets bound to a removed Tailscale
 * address, and every later `accept()` fails) it spins at 100% CPU and `stop()` can never cancel it.
 * Here a failed accept waits before retrying (so cancellation lands), and a socket that keeps failing fails its
 * connector, which ends the engine (the failure reaches the engine's job); [ApiServer] then asks the service to rebind.
 */
object BridgitCio : ApplicationEngineFactory<BridgitCioEngine, CIOApplicationEngine.Configuration> {
    override fun create(environment: ApplicationEngineEnvironment, configure: CIOApplicationEngine.Configuration.() -> Unit) =
        BridgitCioEngine(environment, configure)
}

class BridgitCioEngine(environment: ApplicationEngineEnvironment, configure: CIOApplicationEngine.Configuration.() -> Unit) :
    BaseApplicationEngine(environment) {

    private val configuration = CIOApplicationEngine.Configuration().apply(configure)
    private val engineDispatcher = Dispatchers.IO
    private val userDispatcher = Dispatchers.IO
    private val startupJob: CompletableDeferred<Unit> = CompletableDeferred()
    private val stopRequest: CompletableJob = Job()
    private val serverJob: Job = initServerJob()

    init {
        serverJob.invokeOnCompletion { cause ->
            cause?.let { stopRequest.completeExceptionally(it) }
            cause?.let { startupJob.completeExceptionally(it) }
            environment.stop()
        }
    }

    /** Completes when the engine is gone, whether stopped or because a listener died. */
    val stopped: Job get() = serverJob

    override fun start(wait: Boolean): ApplicationEngine {
        serverJob.start()
        runBlocking {
            startupJob.await()
            environment.monitor.raiseCatching(ServerReady, environment, environment.log)
            if (wait) serverJob.join()
        }
        return this
    }

    override fun stop(gracePeriodMillis: Long, timeoutMillis: Long) {
        stopRequest.complete()
        runBlocking {
            val result = withTimeoutOrNull(gracePeriodMillis) { serverJob.join(); true }
            if (result == null) {
                serverJob.cancel()
                val forceShutdown = withTimeoutOrNull(timeoutMillis - gracePeriodMillis) { serverJob.join(); false } ?: true
                if (forceShutdown) environment.stop()
            }
        }
    }

    private fun CoroutineScope.startConnector(host: String, port: Int): HttpServer {
        val settings = HttpServerSettings(host = host, port = port,
            connectionIdleTimeoutSeconds = configuration.connectionIdleTimeoutSeconds.toLong(),
            reuseAddress = configuration.reuseAddress)
        return httpServer(settings) { request -> handleRequest(request) }
    }

    private suspend fun addHandlerForExpectedHeader(output: ByteWriteChannel, call: CIOApplicationCall) {
        val expectedHeaderPhase = PipelinePhase("ExpectedHeaderPhase")
        call.request.pipeline.insertPhaseBefore(ApplicationReceivePipeline.Before, expectedHeaderPhase)
        call.request.pipeline.intercept(expectedHeaderPhase) {
            val request = call.request
            val version = HttpProtocolVersion.parse(request.httpVersion)
            val expectHeader = call.request.headers[HttpHeaders.Expect]?.lowercase()
            if (expectHeader == null || version == HttpProtocolVersion.HTTP_1_0 || !hasBody(request)) return@intercept
            if (expectHeader != "100-continue") {
                call.respond(HttpStatusCode.ExpectationFailed)
            } else {
                output.writeStringUtf8("HTTP/1.1 100 Continue\r\n")
                output.flush()
            }
        }
    }

    private fun hasBody(request: CIOApplicationRequest): Boolean {
        val contentLength = request.headers[HttpHeaders.ContentLength]?.toLong()
        val transferEncoding = request.headers[HttpHeaders.TransferEncoding]
        return transferEncoding != null || (contentLength != null && contentLength > 0)
    }

    private suspend fun ServerRequestScope.handleRequest(request: Request) {
        withContext(userDispatcher) {
            val call = CIOApplicationCall(application, request, input, output, engineDispatcher, userDispatcher,
                upgraded, remoteAddress, localAddress)
            try {
                addHandlerForExpectedHeader(output, call)
                pipeline.execute(call)
            } catch (error: Throwable) {
                handleFailure(call, error)
            } finally {
                call.release()
            }
        }
    }

    private fun initServerJob(): Job {
        val environment = environment
        val cioConnectors = resolvedConnectors
        return CoroutineScope(environment.parentCoroutineContext + engineDispatcher).launch(start = CoroutineStart.LAZY) {
            val connectors = ArrayList<HttpServer>(environment.connectors.size)
            try {
                if (environment.connectors.any { it.type == ConnectorType.HTTPS }) throw UnsupportedOperationException("CIO does not support HTTPS")
                withContext(userDispatcher) { environment.start() }
                val connectorsAndServers = environment.connectors.map { it to startConnector(it.host, it.port) }
                connectors.addAll(connectorsAndServers.map { it.second })
                cioConnectors.complete(connectorsAndServers
                    .map { (connector, server) -> connector to server.serverSocket.await() }
                    .map { (connector, socket) -> connector.withPort(socket.localAddress.toJavaAddress().let { (it as InetSocketAddress).port }) })
            } catch (cause: Throwable) {
                connectors.forEach { it.rootServerJob.cancel() }
                stopRequest.completeExceptionally(cause)
                startupJob.completeExceptionally(cause)
                throw cause
            }
            startupJob.complete(Unit)
            stopRequest.join()
            connectors.forEach { it.acceptJob.cancel() }
            withContext(userDispatcher) { environment.monitor.raise(ApplicationStopPreparing, environment) }
        }
    }
}

/** The listening socket kept failing; it will not recover. */
class DeadListener(cause: IOException) : IOException("Listener socket failed repeatedly: ${cause.message}", cause)

/** Backoff after the n-th consecutive accept failure, and how many failures in a row mean the socket is dead. */
internal object AcceptBackoff {
    const val GIVE_UP_AFTER = 20
    fun delayMs(failures: Int): Long = (50L shl (failures - 1).coerceIn(0, 4)).coerceAtMost(1000)
}

/** Upstream `httpServer`, with a failed accept backing off instead of spinning (see [BridgitCio]). */
internal fun CoroutineScope.httpServer(settings: HttpServerSettings, backoffMs: (Int) -> Long = AcceptBackoff::delayMs,
                                       handler: HttpRequestHandler): HttpServer {
    val socket = CompletableDeferred<ServerSocket>()
    val serverLatch: CompletableJob = Job()
    val serverJob = launch(context = CoroutineName("server-root-${settings.port}"), start = CoroutineStart.UNDISPATCHED) {
        serverLatch.join()
    }
    val selector = SelectorManager(coroutineContext)
    val timeout = WeakTimeoutQueue(settings.connectionIdleTimeoutSeconds * 1000L)
    val logger = KtorSimpleLogger("BridgitCio")

    val acceptJob = launch(serverJob + CoroutineName("accept-${settings.port}")) {
        aSocket(selector).tcp().bind(settings.host, settings.port) { reuseAddress = settings.reuseAddress }.use { server ->
            socket.complete(server)
            val exceptionHandler = coroutineContext[CoroutineExceptionHandler]
                ?: CoroutineExceptionHandler { _, cause -> logger.error("Unhandled exception in connection", cause) }
            val connectionScope = CoroutineScope(coroutineContext + SupervisorJob(serverJob) + exceptionHandler + CoroutineName("request"))
            try {
                var failures = 0
                while (true) {
                    val client: Socket = try {
                        server.accept().also { failures = 0 }
                    } catch (cause: IOException) {
                        if (++failures >= AcceptBackoff.GIVE_UP_AFTER) throw DeadListener(cause)
                        delay(backoffMs(failures))
                        continue
                    }
                    val connection = ServerIncomingConnection(client.openReadChannel(), client.openWriteChannel(autoFlush = false),
                        client.remoteAddress.toJavaAddress() as InetSocketAddress, client.localAddress.toJavaAddress() as InetSocketAddress)
                    val clientJob = connectionScope.startServerConnectionPipeline(connection, timeout, handler)
                    clientJob.invokeOnCompletion { client.close() }
                }
            } finally {
                server.close()
                server.awaitClosed()
                connectionScope.coroutineContext.cancel()
            }
        }
    }

    acceptJob.invokeOnCompletion { cause ->
        cause?.let { socket.completeExceptionally(it) }
        serverLatch.complete()
        timeout.process()
    }
    @OptIn(InternalCoroutinesApi::class)
    serverJob.invokeOnCompletion(onCancelling = true) { timeout.cancel() }
    serverJob.invokeOnCompletion { selector.close() }
    return HttpServer(serverJob, acceptJob, socket)
}

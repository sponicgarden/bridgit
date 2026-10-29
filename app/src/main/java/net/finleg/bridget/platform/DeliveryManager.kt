package net.finleg.bridget.platform

import kotlinx.coroutines.*
import net.finleg.bridget.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.*

/** Owner-selected destinations only, durable retries and recipient-scoped acknowledgements. */
class DeliveryManager(private val runtime:AgentPlatform) {
    private val sockets=java.util.concurrent.ConcurrentHashMap<String,Socket>()
    fun stopAgent(agent:String) {
        runtime.store.list(agent,"outbox",1000).filter { it.optString("status")=="queued" }.forEach { row ->
            runtime.store.save(agent,"outbox",row.put("status","cancelled")); runCatching { sockets.remove(row.getString("id"))?.close() }
        }
        runtime.store.list(agent,"alert").filter { it.optString("status") in setOf("queued","delivered","escalated") }.forEach { cancel(agent,it.getString("id")) }
    }
    private val sending=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    fun destination(name:String,url:String,allowPrivate:Boolean):JSONObject {
        val uri=URI(url)
        if(name.isBlank() || name.length>80 || uri.scheme!="https" || uri.host.isNullOrBlank() || uri.userInfo!=null || uri.fragment!=null || uri.port !in listOf(-1,443)) throw ApiError(422,"invalid_destination","Use a named HTTPS destination on port 443, without credentials or fragments")
        if(runtime.store.list("owner","destination").size>=10) throw ApiError(429,"destination_limit","At most ten destinations")
        return runtime.store.save("owner","destination",JSONObject().put("name",name).put("url",url).put("allow_private",allowPrivate).put("secret",random()).put("enabled",true))
    }
    fun revoke(id:String) {
        val d=runtime.store.require(id,"owner","destination"); runtime.store.save("owner","destination",d.put("enabled",false))
        runtime.store.list(kind="outbox",limit=1000).filter { it.optString("destination_id")==id }.forEach { runtime.store.save(it.getString("agent_id"),"outbox",it.put("status","cancelled")) }
    }
    fun validateDestinations(ids:List<String>) {
        if(ids.isEmpty() || ids.size>3) throw ApiError(422,"invalid_destination","Choose one to three approved destinations")
        ids.forEach { if(runtime.store.require(it,"owner","destination").optBoolean("enabled")!=true) throw ApiError(403,"destination_disabled","Destination was disabled by the owner") }
    }
    fun trigger(agent:String,args:JSONObject):JSONObject {
        args.only("destinations","text")
        runtime.authorize(agent,"alerts"); val ids=args.strings("destinations",3); validateDestinations(ids)
        val active=runtime.store.list(agent,"alert").firstOrNull { it.optString("status") in listOf("queued","delivered","escalated") && System.currentTimeMillis()-it.optLong("created_at")<600_000 }
        if(active!=null) return public(active) // Repeated help triggers join the active incident.
        val alert=runtime.store.save(agent,"alert",JSONObject().put("status","queued").put("destinations",JSONArray(ids)).put("text",args.text("text",1024)).put("ack_secret",random()).put("next_contact",0).put("next_at",System.currentTimeMillis()+5000))
        runtime.store.emit(agent,"alert.queued",alert.getString("id")); runtime.changed(); return public(alert)
    }
    fun cancel(agent:String,id:String):JSONObject {
        val r=runtime.store.require(id,agent,"alert"); runtime.store.save(agent,"alert",r.put("status","cancelled"))
        runtime.store.list(agent,"outbox",1000).filter { it.optString("alert_id")==id }.forEach { runtime.store.save(agent,"outbox",it.put("status","cancelled")) }
        runtime.store.emit(agent,"alert.cancelled",id); return public(r)
    }
    private fun public(record:JSONObject)=PlatformStore.copy(record).apply { remove("ack_secret"); remove("text") }
    fun list(agent:String)=JSONObject().put("alerts",JSONArray(runtime.store.list(agent,"alert").map(::public)))
    fun ack(id:String,destinationId:String,token:String):JSONObject {
        val record=runtime.store.find(id,kind="alert") ?: throw ApiError(404,"alert_not_found","Unknown alert")
        if(destinationId !in record.strings("destinations")) throw ApiError(401,"unauthorized","Invalid recipient")
        val expected=hmac(record.getString("ack_secret"),destinationId)
        if(!MessageDigest.isEqual(expected.toByteArray(),token.toByteArray())) throw ApiError(401,"unauthorized","Invalid recipient credential")
        if(record.optString("status")=="acknowledged") return public(record)
        if(record.optString("status") in setOf("cancelled","expired")) throw ApiError(409,"alert_closed","Alert is no longer active")
        val destination=runtime.store.require(destinationId,"owner","destination")
        if(!destination.optBoolean("enabled")) throw ApiError(403,"destination_disabled","Recipient was revoked")
        val saved=runtime.store.save(record.getString("agent_id"),"alert",record.put("status","acknowledged").put("acknowledged_by",destination.getString("name")).put("acknowledged_at",System.currentTimeMillis()))
        runtime.store.list(record.getString("agent_id"),"outbox",1000).filter { it.optString("alert_id")==id && it.optString("status")=="queued" }.forEach { runtime.store.save(it.getString("agent_id"),"outbox",it.put("status","cancelled")) }
        runtime.store.emit(record.getString("agent_id"),"alert.acknowledged",id); runtime.changed(); return public(saved)
    }
    fun subscribe(agent:String,destinationId:String) { validateDestinations(listOf(destinationId)); runtime.store.setting("callback:$agent",destinationId,true); runtime.store.setting("callback_cursor:$agent",runtime.store.latestCursor().toString(),true) }
    fun tick() {
        val now=System.currentTimeMillis()
        runtime.store.list(kind="alert").filter { it.optString("status") in setOf("queued","delivered","escalated") }.forEach { alert ->
            val agent=alert.getString("agent_id"); val id=alert.getString("id")
            if(!runtime.hasGrant(agent,"alerts") || !Capabilities.enabled(runtime.app,"alerts")) { cancel(agent,id); return@forEach }
            if(now-alert.getLong("created_at")>600_000) { runtime.store.save(agent,"alert",alert.put("status","expired")); runtime.store.emit(agent,"alert.expired",id); return@forEach }
            val ids=alert.strings("destinations"); val next=alert.optInt("next_contact")
            if(next<ids.size && now>=alert.optLong("next_at")) {
                val dest=ids[next]
                val payload=JSONObject().put("type","help.requested").put("alert_id",id).put("device_id",runtime.store.deviceId).put("text",alert.getString("text")).put("created_at",alert.getLong("created_at")).put("recipient_id",dest)
                    .put("ack_path","/v2/alerts/$id/ack").put("ack_credential",hmac(alert.getString("ack_secret"),dest))
                if(enqueue(agent,dest,payload,id)) runtime.store.save(agent,"alert",alert.put("next_contact",next+1).put("status",if(next==0) "queued" else "escalated").put("next_at",alert.getLong("created_at")+if(next==0) 30_000 else 90_000))
            }
        }
        runtime.app.policy.agents().forEach { agent ->
            val destinationId=runtime.store.setting("callback:${agent.id}")
            if(destinationId.isNotEmpty() && runtime.hasGrant(agent.id,"events.read") && Capabilities.enabled(runtime.app,"events.read")) {
                queueCallbacks(agent.id,destinationId)
            }
        }
        runtime.store.list(kind="outbox",limit=1000).filter { it.optString("status")=="queued" && it.optLong("next_at")<=now }.take(4).forEach { row ->
            val id=row.getString("id"); if(sending.add(id)) runtime.scope.launch { try { deliver(row) } finally { sending.remove(id) } }
        }
    }
    internal fun queueCallbacks(agent:String,destination:String) {
        val after=runtime.store.setting("callback_cursor:$agent","0").toLongOrNull() ?: 0
        val events=runtime.store.events(agent,after); val rows=events.getJSONArray("events")
        var cursor=after
        for(i in 0 until rows.length()) {
            val payload=rows.getJSONObject(i)
            if(i==0 && events.optBoolean("gap")) payload.put("gap",true)
            if(!enqueue(agent,destination,payload,"")) break
            cursor=payload.getLong("cursor")
        }
        if(rows.length()==0) {
            if(!events.optBoolean("gap") || enqueue(agent,destination,JSONObject().put("type","events.gap").put("gap",true).put("cursor",events.getLong("cursor")),"")) cursor=events.getLong("cursor")
        }
        runtime.store.setting("callback_cursor:$agent",cursor.toString(),true)
    }
    private fun enqueue(agent:String,destination:String,payload:JSONObject,alert:String):Boolean {
        if(runtime.store.list(agent,"outbox",1000).count { it.optString("status")=="queued" }>=100) return false
        runtime.store.save(agent,"outbox",JSONObject().put("destination_id",destination).put("payload",payload).put("alert_id",alert).put("status","queued").put("attempts",0).put("next_at",System.currentTimeMillis()))
        return true
    }
    private fun deliver(row:JSONObject) {
        val agent=row.getString("agent_id"); val id=row.getString("id"); val attempt=row.optInt("attempts")+1
        try {
            val d=runtime.store.require(row.getString("destination_id"),"owner","destination")
            if(!d.optBoolean("enabled")) throw ApiError(403,"destination_disabled","Destination disabled")
            if(row.optString("alert_id").isNotEmpty() && runtime.store.require(row.getString("alert_id"),agent,"alert").optString("status") in setOf("acknowledged","cancelled","expired")) { runtime.store.save(agent,"outbox",row.put("status","cancelled")); return }
            val payload=PlatformStore.copy(row.getJSONObject("payload")).put("event_id",id)
            val code=postPinned(d,payload.toString(),id,agent)
            if(code !in 200..299) throw ApiError(503,"delivery_failed","Recipient returned $code")
            runtime.store.save(agent,"outbox",row.put("status","accepted").put("attempts",attempt).put("accepted_at",System.currentTimeMillis()))
            if(row.optString("alert_id").isNotEmpty()) {
                val a=runtime.store.require(row.getString("alert_id"),agent,"alert")
                if(a.optString("status")=="queued") runtime.store.save(agent,"alert",a.put("status","delivered"))
            }
        } catch(e:Exception) {
            if(runtime.store.find(id,agent)?.optString("status")=="cancelled") return
            val retry=when(attempt) { 1 -> 2000L; 2 -> 10_000L; 3 -> 30_000L; else -> 300_000L }
            runtime.store.save(agent,"outbox",row.put("attempts",attempt).put("status",if(attempt>=6) "failed" else "queued").put("next_at",System.currentTimeMillis()+retry).put("error",if(e is ApiError) e.code else "transport_failed"))
        } finally { sockets.remove(id)?.let { runCatching { it.close() } } }
    }
    /** DNS is validated once and the TLS socket is pinned to that exact address. No redirects. */
    private fun postPinned(destination:JSONObject,body:String,id:String,agent:String):Int {
        val uri=URI(destination.getString("url")); val host=uri.host
        val addresses=InetAddress.getAllByName(host)
        val address=addresses.firstOrNull { publicAddress(it) || (destination.optBoolean("allow_private") && privateAddress(it)) } ?: throw ApiError(403,"destination_address_blocked","Destination is not a permitted public address")
        if(!destination.optBoolean("allow_private") && addresses.any { !publicAddress(it) }) throw ApiError(403,"destination_address_blocked","Mixed public/private destination addresses")
        val raw=Socket(); sockets[id]=raw; if(!runtime.app.settings.listenerEnabled || runtime.store.find(id,agent)?.optString("status")!="queued") { raw.close(); throw ApiError(409,"delivery_cancelled","Delivery was cancelled") }; raw.connect(InetSocketAddress(address,443),5000); raw.soTimeout=5000
        val tls=try { (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw,host,443,true) as SSLSocket } catch(e:Exception) { raw.close(); throw e }
        tls.use { socket ->
            socket.sslParameters=socket.sslParameters.apply { endpointIdentificationAlgorithm="HTTPS"; if(!host.matches(Regex("[0-9.]+"))) serverNames=listOf(SNIHostName(host)) }
            socket.startHandshake()
            val bytes=body.toByteArray(Charsets.UTF_8); val timestamp=System.currentTimeMillis().toString(); val nonce=UUID.randomUUID().toString(); val signature=hmac(destination.getString("secret"),"$timestamp.$nonce.$id.$body")
            val path=(uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/")+(uri.rawQuery?.let { "?$it" } ?: "")
            val headers="POST $path HTTP/1.1\r\nHost: $host\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\nIdempotency-Key: $id\r\nX-Bridgit-Timestamp: $timestamp\r\nX-Bridgit-Nonce: $nonce\r\nX-Bridgit-Event: $id\r\nX-Bridgit-Signature: $signature\r\n\r\n"
            if(!runtime.app.settings.listenerEnabled || !runtime.store.require(destination.getString("id"),"owner","destination").optBoolean("enabled") || runtime.store.find(id,agent)?.optString("status")!="queued") throw ApiError(409,"delivery_cancelled","Delivery was cancelled")
            socket.outputStream.write(headers.toByteArray(Charsets.US_ASCII)); socket.outputStream.write(bytes); socket.outputStream.flush()
            val input=socket.inputStream; val status=StringBuilder()
            while(status.length<1024) { val ch=input.read(); if(ch<0 || ch==10) break; status.append(ch.toChar()) }
            return status.toString().split(' ').getOrNull(1)?.toIntOrNull() ?: throw ApiError(503,"delivery_failed","Invalid recipient response")
        }
    }
    companion object {
        private fun random()=ByteArray(32).also { SecureRandom().nextBytes(it) }.toHex()
        fun hmac(secret:String,text:String):String=Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(),"HmacSHA256")) }.doFinal(text.toByteArray()).toHex()
        fun privateAddress(address:InetAddress):Boolean {
            if(address.isLoopbackAddress || address.isLinkLocalAddress || address.isAnyLocalAddress || address.isMulticastAddress) return false
            val b=address.address
            return address.isSiteLocalAddress || (b.size==4 && (b[0].toInt() and 255)==100 && (b[1].toInt() and 255) in 64..127) || (b.size==16 && (b[0].toInt() and 0xfe)==0xfc)
        }
        fun publicAddress(address:InetAddress):Boolean {
            if(address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress) return false
            val b=address.address
            if(b.size==4) {
                val first=b[0].toInt() and 255; val second=b[1].toInt() and 255; val third=b[2].toInt() and 255
                return first !in setOf(0,10,127) && first<224 && !(first==100 && second in 64..127) && !(first==172 && second in 16..31) && !(first==169 && second==254) && !(first==192 && (second==168 || (second==0 && third in setOf(0,2)))) && !(first==198 && (second in 18..19 || (second==51 && third==100))) && !(first==203 && second==0 && third==113)
            }
            return (b[0].toInt() and 0xfe)!=0xfc && !(b.take(12).all { it==0.toByte() })
        }
    }
}

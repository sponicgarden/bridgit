package cc.bridgit.app.relay

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import cc.bridgit.app.BridgeApp
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/** No agent plaintext token is retained. HPKE private material is wrapped by a Keystore AES key. */
class RelayStore(private val app:BridgeApp) {
    private val prefs=app.getSharedPreferences("relay_v1",Context.MODE_PRIVATE)
    private val keys get()=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val alias="bridgit-relay-identity-v1"
    var enabled:Boolean get()=prefs.getBoolean("enabled",false);set(v){check(prefs.edit().putBoolean("enabled",v).commit())}
    var base:String get()=prefs.getString("base","") ?: "";set(v){
        val u=java.net.URI(v);require(u.scheme=="https"&&u.host!=null&&u.userInfo==null&&u.query==null&&u.fragment==null&&u.path.matches(Regex("/d/[A-Za-z0-9_-]{8,128}")))
        check(prefs.edit().putString("base",v).commit())
    }
    val device get()=app.platform.store.deviceId
    private fun identity(){if(!keys.containsAlias(alias))KeyPairGenerator.getInstance("EC","AndroidKeyStore").apply { initialize(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY).setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1")).setDigests(KeyProperties.DIGEST_SHA256).build()) }.generateKeyPair()}
    fun identityPublic():String { identity();return Sealed.b64(keys.getCertificate(alias).publicKey.encoded) }
    fun challengeProof():JSONObject {
        val nonce=java.util.UUID.randomUUID().toString();val at=System.currentTimeMillis()
        return JSONObject().put("nonce",nonce).put("at",at).put("signature",signText("bridgit-relay-challenge-v1\n$device\n$nonce\n$at"))
    }
    fun sign(nonce:String,until:Long):String=signText("bridgit-relay-v1\n$device\n$nonce\n$until")
    private fun signText(message:String):String {
        identity();val der=Signature.getInstance("SHA256withECDSA").apply { initSign(keys.getKey(alias,null) as java.security.PrivateKey);update(message.toByteArray()) }.sign()
        // WebCrypto uses IEEE P1363 r||s, Android Keystore emits DER.
        val seq=org.bouncycastle.asn1.ASN1Sequence.getInstance(der)
        return Sealed.b64(ByteArray(64).also { out -> for(i in 0..1){val n=org.bouncycastle.asn1.ASN1Integer.getInstance(seq.getObjectAt(i)).positiveValue.toByteArray().takeLast(32).toByteArray();n.copyInto(out,i*32+32-n.size)} })
    }
    @Synchronized fun hpke():org.bouncycastle.crypto.AsymmetricCipherKeyPair {
        val wrap="bridgit-relay-wrap-v1"
        if(!keys.containsAlias(wrap))KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply { init(KeyGenParameterSpec.Builder(wrap,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build()) }.generateKey()
        val secret=keys.getKey(wrap,null)
        val saved=prefs.getString("hpke_secret",null)
        if(saved!=null){val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,secret,GCMParameterSpec(128,Sealed.un64(prefs.getString("hpke_iv","")!!)));return Sealed.restore(c.doFinal(Sealed.un64(saved)),prefs.getString("hpke_public","")!!)}
        val pair=Sealed.generate();val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,secret)
        check(prefs.edit().putString("hpke_secret",Sealed.b64(c.doFinal(Sealed.privateKey(pair)))).putString("hpke_iv",Sealed.b64(c.iv)).putString("hpke_public",Sealed.publicKey(pair)).commit());return pair
    }
    @Synchronized fun agents():List<JSONObject> {
        val all=JSONArray(prefs.getString("agents","[]"));return (0 until all.length()).map { all.getJSONObject(it) }.filter { c -> app.agents.get(c.optString("id"))?.keyHash==c.optString("generation") }
    }
    @Synchronized fun addAgent(id:String,mode:String,peer:String?,routes:Set<String>,tools:Set<String>):String {
        val a=app.agents.get(id) ?: error("No such agent");require(mode in setOf("sealed","connector"));require(a.caps!=null) { "Relay agents need explicit capability grants" }
        if(mode=="sealed")Sealed.validatePublic(peer ?: "")
        val token="rly_"+java.util.UUID.randomUUID()+java.util.UUID.randomUUID()
        val config=JSONObject().put("id",id).put("generation",a.keyHash).put("mode",mode).put("token_hash",hash(token)).put("peer",peer ?: "").put("routes",JSONArray(routes.sorted())).put("tools",JSONArray(tools.sorted()))
        check(prefs.edit().putString("agents",JSONArray(agents().filter { it.optString("id")!=id }+config).toString()).commit());return token
    }
    @Synchronized fun remove(id:String){check(prefs.edit().putString("agents",JSONArray(agents().filter { it.optString("id")!=id }).toString()).commit());app.platform.invalidate(id)}
    /** Strict monotonic sequence per pinned agent generation. Persist BEFORE execution; survives process death. */
    @Synchronized fun claim(id:String,generation:String,seq:Long,requestId:String?=null):Boolean {
        val key="seq:$id:$generation";if(seq<=prefs.getLong(key,0)||seq<=0)return false
        val now=System.currentTimeMillis();val edit=prefs.edit()
        if(requestId!=null){
            val prefix="rid:$id:$generation:";val rid=prefix+requestId
            if(prefs.getLong(rid,0)>now)return false
            val recent=prefs.all.filter { it.key.startsWith(prefix)&&(it.value as? Long ?: 0)>now };if(recent.size>=400)return false
            prefs.all.filter { it.key.startsWith("rid:")&&(it.value as? Long ?: 0)<=now }.keys.forEach { edit.remove(it) }
            edit.putLong(rid,now+24*3600000L)
        }
        check(edit.putLong(key,seq).commit());return true
    }
    fun enrollment()=JSONObject().put("device_id",device).put("identity_spki",identityPublic()).put("hpke_public",Sealed.publicKey(hpke()))
    companion object { fun hash(s:String)=MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }
}

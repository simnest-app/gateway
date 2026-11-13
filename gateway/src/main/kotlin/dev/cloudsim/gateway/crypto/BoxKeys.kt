package dev.cloudsim.gateway.crypto

import android.content.Context
import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.KeyTemplate
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.hybrid.HpkePublicKey
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import dev.cloudsim.gateway.identity.Signer
import dev.cloudsim.gateway.net.ControlClient
import dev.cloudsim.gateway.net.ControlClient.Companion.b64
import dev.cloudsim.gateway.net.ControlClient.Companion.unb64
import dev.cloudsim.gateway.protocol.Protocol
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * This gateway's HPKE recipient key and the recipient keys of the phones it talks to (CALLER_DIAL_3E.md). The private
 * key is a Tink keyset kept in private storage and encrypted by an Android Keystore master key; it never leaves the phone.
 */
class BoxKeys(context: Context, private val client: ControlClient, private val signer: Signer) {
    private val handle = AndroidKeysetManager.Builder()
        .withSharedPref(context, "box_keyset", "box")
        .withKeyTemplate(KeyTemplate.createFrom(Seal.parameters))
        .withMasterKeyUri("android-keystore://cloudsim_box_master")
        .build()
        .keysetHandle

    val publicKey: ByteArray = (handle.publicKeysetHandle.primary.key as HpkePublicKey).publicKeyBytes.toByteArray()
    val decrypter: HybridDecrypt = handle.getPrimitive(RegistryConfiguration.get(), HybridDecrypt::class.java)
    private val others = ConcurrentHashMap<String, ByteArray>()

    /** Publishes the recipient key, signed by the device key so the service cannot swap it. */
    fun publish() {
        client.call("POST", "/v1/box-key", JSONObject().put("public_key", b64(publicKey))
            .put("signature", b64(signer.sign(Protocol.boxKeyMessage(client.deviceId, publicKey)))))
    }

    /** A phone's recipient key, accepted only if that phone's own device key signed it. Null if it has none yet. */
    fun recipient(deviceId: String): ByteArray? = others[deviceId] ?: runCatching {
        val r = client.call("GET", "/v1/devices/$deviceId/box-key")
        val key = unb64(r.getString("public_key"))!!
        val signing = unb64(r.getString("signing_key"))!!
        require(Protocol.deviceId(signing) == deviceId && Protocol.verify(signing, Protocol.boxKeyMessage(deviceId, key), unb64(r.getString("signature"))))
        key
    }.getOrNull()?.also { others[deviceId] = it }
}

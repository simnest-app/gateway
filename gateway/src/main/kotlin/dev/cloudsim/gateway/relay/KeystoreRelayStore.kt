package dev.cloudsim.gateway.relay

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The own relay in this app's private storage, its secret encrypted with a key that lives in the system keystore and
 * cannot be taken out of it: a copy of the app's files alone does not give the secret away.
 */
class KeystoreRelayStore(context: Context) : RelayStore {
    private val prefs = context.getSharedPreferences("own_relay", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
            generateKey()
        }
    }

    override fun load(): OwnRelay? {
        val host = prefs.getString("host", null) ?: return null
        val sealed = Base64.getDecoder().decode(prefs.getString("secret", null) ?: return null)
        val iv = Base64.getDecoder().decode(prefs.getString("iv", null) ?: return null)
        val secret = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            doFinal(sealed).decodeToString()
        }
        val lan = JSONArray(prefs.getString("lan", "[]")).let { a -> (0 until a.length()).map(a::getString) }
        return OwnRelay(host, prefs.getInt("port", 3478), lan, secret, prefs.getString("ver", "").orEmpty())
    }

    override fun save(relay: OwnRelay) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.doFinal(relay.secret.toByteArray())
        prefs.edit().putString("host", relay.host).putInt("port", relay.port).putString("lan", JSONArray(relay.lan).toString()).putString("ver", relay.version)
            .putString("secret", Base64.getEncoder().encodeToString(sealed)).putString("iv", Base64.getEncoder().encodeToString(cipher.iv)).apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) }
    }

    private companion object {
        const val ALIAS = "own_relay_secret"
    }
}

/** The one keeper of this app: the service checks the relay and answers with it, the screen adds and removes it. */
object OwnRelays {
    /** The owner's choice from the policy the service last confirmed; set by the gateway service. */
    @Volatile var mode: () -> String = { "" }
    @Volatile private var keeper: OwnRelayKeeper? = null

    fun get(context: Context, log: (String) -> Unit = {}): OwnRelayKeeper = keeper ?: synchronized(this) {
        keeper ?: OwnRelayKeeper(KeystoreRelayStore(context.applicationContext), { mode() }, log = log).also { keeper = it }
    }
}

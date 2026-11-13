package dev.cloudsim.gateway.identity

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/** Signs with the device identity key. */
interface Signer {
    /** SubjectPublicKeyInfo DER. */
    val publicKey: ByteArray
    fun sign(message: ByteArray): ByteArray
}

/** P-256 key in Android Keystore; the private key never leaves it. Created on first use. */
class KeystoreSigner(private val alias: String = "cloudsim-device-v1") : Signer {
    private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    init {
        if (!store.containsAlias(alias)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").run {
                initialize(
                    KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .build()
                )
                generateKeyPair()
            }
        }
    }

    override val publicKey: ByteArray get() = store.getCertificate(alias).publicKey.encoded

    override fun sign(message: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(store.getKey(alias, null) as PrivateKey)
        update(message)
        sign()
    }
}

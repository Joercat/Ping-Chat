package com.ping.app.core.crypto

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.Signature
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac

/**
 * Central place where every JCA/JCE algorithm is resolved.
 *
 * Ping only ever uses the standard JCE interfaces. On Android the modern curves (X25519/Ed25519)
 * are provided by Bouncy Castle for API < 33 and by the platform (Conscrypt) on newer releases;
 * on the JVM the JDK providers are used. Resolving algorithms through one helper keeps that
 * difference in a single file.
 */
object PingCrypto {

    private const val BC = "BC"
    private const val JSL = "Conscrypt"

    /** Names of providers we are willing to use, best first. */
    private val preferredProviders = listOf(BC, JSL)

    @Volatile
    private var installed = false

    /**
     * Installs the modern Bouncy Castle provider.
     *
     * Android ships a stripped-down, ancient "BC" provider. It is replaced so that X25519,
     * Ed25519 and ChaCha20-Poly1305 are available on every supported Android release.
     * Safe to call multiple times.
     */
    fun install() {
        if (installed) return
        synchronized(this) {
            if (installed) return
            try {
                // Loaded reflectively: the Android build always ships Bouncy Castle, but the core
                // must also run on a plain JVM (unit tests) where the provider is absent.
                val providerClass = Class.forName("org.bouncycastle.jce.provider.BouncyCastleProvider")
                val provider = providerClass.getDeclaredConstructor().newInstance() as Provider
                // Drop whichever "BC" is already registered (Android ships an ancient one).
                Security.removeProvider(BC)
                Security.addProvider(provider)
            } catch (e: Throwable) {
                // Never let provider setup take the app down: the platform provider still offers
                // AES-GCM and HMAC-SHA256, which is why every fallback path below exists.
            }
            installed = true
        }
    }

    private fun <T> resolve(
        algorithm: String,
        preferred: List<String>,
        obtain: (String) -> T,
        obtainDefault: () -> T
    ): T {
        install()
        for (name in preferred) {
            if (Security.getProvider(name) == null) continue
            try {
                return obtain(name)
            } catch (e: Exception) {
                // try the next provider
            }
        }
        return obtainDefault()
    }

    fun keyPairGenerator(algorithm: String): KeyPairGenerator =
        resolve(algorithm, preferredProviders, { p -> KeyPairGenerator.getInstance(algorithm, p) }) {
            KeyPairGenerator.getInstance(algorithm)
        }

    fun keyFactory(algorithm: String): KeyFactory =
        resolve(algorithm, preferredProviders, { p -> KeyFactory.getInstance(algorithm, p) }) {
            KeyFactory.getInstance(algorithm)
        }

    fun keyAgreement(algorithm: String): KeyAgreement =
        resolve(algorithm, preferredProviders, { p -> KeyAgreement.getInstance(algorithm, p) }) {
            KeyAgreement.getInstance(algorithm)
        }

    fun signature(algorithm: String): Signature =
        resolve(algorithm, preferredProviders, { p -> Signature.getInstance(algorithm, p) }) {
            Signature.getInstance(algorithm)
        }

    /**
     * AEAD ciphers prefer the platform implementation (hardware accelerated on Android),
     * falling back to Bouncy Castle for older releases.
     */
    fun cipher(transformation: String): Cipher =
        resolve(transformation, listOf(JSL, BC), { p -> Cipher.getInstance(transformation, p) }) {
            Cipher.getInstance(transformation)
        }

    fun mac(algorithm: String): Mac = Mac.getInstance(algorithm)

    fun secureRandom(): SecureRandom = SecureRandom()
}

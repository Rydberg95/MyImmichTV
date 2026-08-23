package dev.myimmich.tv.tls

import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.time.Duration
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

object TlsSupport {

    data class CertInfo(
        val fingerprint: String,
        val spkiFingerprint: String,
        val subject: String,
        val issuer: String,
    )

    /**
     * Result of probing a server's TLS chain.
     * [cert] is the leaf; [chain] holds every presented cert, leaf first;
     * [top] is the topmost presented certificate (the issuing CA), null for single-cert chains.
     */
    sealed interface ProbeResult {
        val chain: List<CertInfo>
        val cert: CertInfo get() = chain.first()
        val top: CertInfo? get() = chain.lastOrNull()?.takeIf { chain.size > 1 }

        data class Trusted(override val chain: List<CertInfo>) : ProbeResult
        data class Untrusted(override val chain: List<CertInfo>) : ProbeResult
        data class Error(val message: String) : ProbeResult {
            override val chain: List<CertInfo> get() = emptyList()
        }
    }

    /** SHA-256 over the full DER-encoded certificate — changes on every renewal. */
    fun fingerprintOf(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            .joinToString(":") { "%02X".format(it) }

    /**
     * SHA-256 over the certificate's SubjectPublicKeyInfo. Survives renewals that reuse
     * the same key pair — which Caddy does for its local CA intermediates — so this is
     * what we pin for long-lived trust while leaves rotate every few hours.
     */
    fun spkiFingerprintOf(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded)
            .joinToString(":") { "%02X".format(it) }

    private fun certInfo(cert: X509Certificate) = CertInfo(
        fingerprint = fingerprintOf(cert),
        spkiFingerprint = spkiFingerprintOf(cert),
        subject = cert.subjectX500Principal.name,
        issuer = cert.issuerX500Principal.name,
    )

    private fun defaultTrustManager(): X509TrustManager {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as java.security.KeyStore?)
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    private class RecordingTrustManager(
        private val delegate: X509TrustManager?,
    ) : X509TrustManager {
        var lastChain: Array<X509Certificate> = emptyArray()
        var lastError: CertificateException? = null

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
            delegate?.checkClientTrusted(chain, authType)
        }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            lastChain = chain
            if (delegate == null) throw CertificateException("capture-only")
            try {
                delegate.checkServerTrusted(chain, authType)
            } catch (e: CertificateException) {
                lastError = e
                throw e
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> =
            delegate?.acceptedIssuers ?: emptyArray()
    }

    /**
     * Accepts if ANY presented certificate matches ANY accepted value, comparing both the
     * full-certificate fingerprint and the SPKI fingerprint. This lets a pinned issuing CA
     * vouch for endlessly rotating short-lived leaf certificates.
     */
    private class PinnedTrustManager(
        acceptedRaw: Collection<String>,
    ) : X509TrustManager {
        private val accepted = acceptedRaw.map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }.toSet()

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            if (accepted.isEmpty()) throw CertificateException("no certificate pins configured")
            for (cert in chain) {
                if (fingerprintOf(cert).lowercase() in accepted) return
                if (spkiFingerprintOf(cert).lowercase() in accepted) return
            }
            throw CertificateException("certificate pin mismatch")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private class TrustAllManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    fun probe(url: String): ProbeResult {
        val recorder = RecordingTrustManager(defaultTrustManager())
        val client = baseBuilder()
            .sslSocketFactory(sslContext(recorder).socketFactory, recorder)
            .hostnameVerifier { _, _ -> true }
            .build()
        return try {
            client.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { resp ->
                when {
                    recorder.lastChain.isNotEmpty() && recorder.lastError != null ->
                        ProbeResult.Untrusted(recorder.lastChain.map { certInfo(it) })
                    recorder.lastChain.isNotEmpty() ->
                        ProbeResult.Trusted(recorder.lastChain.map { certInfo(it) })
                    !resp.isSuccessful -> ProbeResult.Error("HTTP ${resp.code}")
                    else -> ProbeResult.Error("no TLS chain captured")
                }
            }
        } catch (e: Exception) {
            val chain = recorder.lastChain.toList()
            if (chain.isNotEmpty()) ProbeResult.Untrusted(chain.map { certInfo(it) })
            else ProbeResult.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    /** True when the presented chain contains a certificate matching any accepted value. */
    fun chainMatches(probe: ProbeResult, accepted: Collection<String>): Boolean {
        val set = accepted.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        if (set.isEmpty()) return false
        return probe.chain.any { c ->
            c.fingerprint.lowercase() in set || c.spkiFingerprint.lowercase() in set
        }
    }

    fun buildClient(
        certFingerprint: String?,
        trustAny: Boolean,
        extraAccepted: List<String> = emptyList(),
    ): OkHttpClient {
        val builder = baseBuilder()
        when {
            !certFingerprint.isNullOrBlank() || extraAccepted.any { it.isNotBlank() } -> {
                val tm = PinnedTrustManager(listOfNotNull(certFingerprint) + extraAccepted)
                builder.sslSocketFactory(sslContext(tm).socketFactory, tm)
                builder.hostnameVerifier { _, _ -> true }
            }
            trustAny -> {
                val tm = TrustAllManager()
                builder.sslSocketFactory(sslContext(tm).socketFactory, tm)
                builder.hostnameVerifier { _, _ -> true }
            }
        }
        return builder.build()
    }

    private fun baseBuilder(): OkHttpClient.Builder = OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(15))
        .readTimeout(Duration.ofMinutes(10))

    private fun sslContext(tm: X509TrustManager): SSLContext =
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), SecureRandom()) }
}

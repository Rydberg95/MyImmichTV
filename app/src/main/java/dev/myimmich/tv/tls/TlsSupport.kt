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
        val subject: String,
        val issuer: String,
    )

    sealed interface ProbeResult {
        data class Trusted(val cert: CertInfo) : ProbeResult
        data class Untrusted(val cert: CertInfo) : ProbeResult
        data class Error(val message: String) : ProbeResult
    }

    fun fingerprintOf(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            .joinToString(":") { "%02X".format(it) }

    private fun certInfo(cert: X509Certificate) = CertInfo(
        fingerprint = fingerprintOf(cert),
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

    private class PinnedTrustManager(
        private val pin: String,
        private val allowUntrustedFallback: Boolean,
    ) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            val leaf = chain.firstOrNull() ?: throw CertificateException("empty chain")
            if (fingerprintOf(leaf).equals(pin, ignoreCase = true)) return
            if (!allowUntrustedFallback) {
                throw CertificateException("certificate pin mismatch")
            }
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
                val leaf = recorder.lastChain.firstOrNull()
                if (leaf != null && recorder.lastError != null) {
                    ProbeResult.Untrusted(certInfo(leaf))
                } else {
                    val l2 = recorder.lastChain.firstOrNull()
                    if (l2 != null) ProbeResult.Trusted(certInfo(l2))
                    else if (!resp.isSuccessful) ProbeResult.Error("HTTP ${resp.code}")
                    else ProbeResult.Trusted(CertInfo("", "", ""))
                }
            }
        } catch (e: Exception) {
            val leaf = recorder.lastChain.firstOrNull()
            if (leaf != null) ProbeResult.Untrusted(certInfo(leaf))
            else ProbeResult.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    fun buildClient(certFingerprint: String?, trustAny: Boolean): OkHttpClient {
        val builder = baseBuilder()
        when {
            !certFingerprint.isNullOrBlank() -> {
                val tm = PinnedTrustManager(certFingerprint, false)
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

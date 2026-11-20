package org.aprsdroid.app.export

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory

/**
 * TLS for the TAK Server connection, using a client certificate (.p12)
 * from your own CA — e.g. the Magnolia OpenXPKI.
 *
 * Trust: the CA certificates bundled in the .p12's chain are the trust
 * anchors for the server certificate (so no globally trusted CA is
 * needed). If the .p12 has no CA certificates, the system trust store is
 * used. Unlike the APRS-IS SSL code, the server certificate and hostname
 * ARE verified (hostname check can be switched off for testing).
 */
object TakTls {

    /** Internal password for the copy stored in app-private storage. */
    private val STORE_PASS = "APRSdroid-TAK".toCharArray()

    data class ClientCertInfo(val subject: String, val issuer: String, val notAfter: java.util.Date, val caCount: Int)

    /**
     * Import a user-supplied .p12: verify it opens with [password] and has a
     * key entry, then store it at [dest] under the internal password.
     */
    @JvmStatic
    fun importClientP12(input: InputStream, password: CharArray, dest: File): ClientCertInfo {
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(input, password)
        val alias = ks.aliases().toList().firstOrNull { ks.isKeyEntry(it) }
            ?: throw IOException("No private key in the .p12 file")
        val key = ks.getKey(alias, password)
        val chain = ks.getCertificateChain(alias)
        val out = KeyStore.getInstance("PKCS12")
        out.load(null, null)
        out.setKeyEntry("tak-client", key, STORE_PASS, chain)
        // Keep any separately stored CA certificates as trust entries.
        for (a in ks.aliases()) {
            if (ks.isCertificateEntry(a)) out.setCertificateEntry("ca-$a", ks.getCertificate(a))
        }
        dest.parentFile?.mkdirs()
        dest.outputStream().use { out.store(it, STORE_PASS) }
        return info(out)
    }

    @JvmStatic
    fun loadInfo(file: File): ClientCertInfo? = try {
        info(load(file))
    } catch (_: Exception) {
        null
    }

    private fun load(file: File): KeyStore {
        val ks = KeyStore.getInstance("PKCS12")
        file.inputStream().use { ks.load(it, STORE_PASS) }
        return ks
    }

    private fun info(ks: KeyStore): ClientCertInfo {
        val chain = ks.getCertificateChain("tak-client")
        val leaf = chain[0] as X509Certificate
        return ClientCertInfo(
            leaf.subjectX500Principal.name,
            leaf.issuerX500Principal.name,
            leaf.notAfter,
            caCertificates(ks).size,
        )
    }

    private fun caCertificates(ks: KeyStore): List<X509Certificate> {
        val cas = ArrayList<X509Certificate>()
        for (a in ks.aliases()) {
            if (ks.isKeyEntry(a)) {
                ks.getCertificateChain(a)?.drop(1)?.forEach { cas.add(it as X509Certificate) }
            } else if (ks.isCertificateEntry(a)) {
                cas.add(ks.getCertificate(a) as X509Certificate)
            }
        }
        return cas
    }

    /**
     * Build an SSLContext: client key from [clientP12] (if it exists) and
     * trust anchors from its CA chain (or the system store).
     */
    @JvmStatic
    fun sslContext(clientP12: File?): SSLContext {
        var keyManagers: Array<javax.net.ssl.KeyManager>? = null
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        if (clientP12 != null && clientP12.exists()) {
            val ks = load(clientP12)
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(ks, STORE_PASS)
            keyManagers = kmf.keyManagers
            val cas = caCertificates(ks)
            if (cas.isNotEmpty()) {
                val trust = KeyStore.getInstance(KeyStore.getDefaultType())
                trust.load(null, null)
                cas.forEachIndexed { i, c -> trust.setCertificateEntry("ca$i", c) }
                tmf.init(trust)
            } else {
                tmf.init(null as KeyStore?)
            }
        } else {
            tmf.init(null as KeyStore?)
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(keyManagers, tmf.trustManagers, null)
        return ctx
    }

    /** Connector for [CotStreamSink]: TLS handshake + optional hostname check. */
    @JvmStatic
    fun connector(host: String, port: Int, ctx: SSLContext, verifyHostname: Boolean, timeoutMs: Int = 10_000): () -> Socket = {
        val raw = Socket()
        raw.connect(InetSocketAddress(host, port), timeoutMs)
        raw.tcpNoDelay = true
        val s = ctx.socketFactory.createSocket(raw, host, port, true) as SSLSocket
        s.soTimeout = 0
        if (verifyHostname) {
            // RFC 2818 host name check during the handshake (Android API 24+ and desktop Java).
            val params = s.sslParameters
            params.endpointIdentificationAlgorithm = "HTTPS"
            s.sslParameters = params
        }
        try {
            s.startHandshake()
        } catch (e: IOException) {
            s.close()
            throw e
        }
        s
    }
}

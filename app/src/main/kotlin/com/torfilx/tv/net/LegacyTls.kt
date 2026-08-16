package com.torfilx.tv.net

import android.content.Context
import android.os.Build
import android.util.Base64
import com.torfilx.core.common.log.TorfilxLog
import com.torfilx.tv.R
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

private const val TAG = "Tls"

/**
 * Makes HTTPS work on a Fire TV stick that has not had a system update since 2015.
 *
 * Fire OS 5 is Android 5.1, and it carries the certificate authorities of its day. Most of the web
 * has since moved to roots that store has never heard of — Let's Encrypt's ISRG Root X1 above all —
 * so every poster fetch dies with "Trust anchor for certification path not found" and the UI shows
 * placeholder cards with no explanation.
 *
 * The fix is additive, never subtractive: the platform's own trust manager is asked first and only
 * when it rejects a chain is the bundled Mozilla root list consulted. Nothing is blindly accepted,
 * no hostname check is skipped, and on API 24+ — where the system store is maintained by the
 * platform and updatable — this whole path is skipped.
 */
internal fun OkHttpClient.Builder.trustModernCertificateAuthorities(
    context: Context,
): OkHttpClient.Builder {
    TorfilxLog.i(TAG, "Configuring HTTPS trust (API ${Build.VERSION.SDK_INT})")
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) return this

    val system = systemTrustManager()
    if (system == null) {
        TorfilxLog.w(TAG, "No system X509 trust manager; leaving TLS configuration alone")
        return this
    }
    val bundled = runCatching { bundledTrustManager(context) }.getOrElse { error ->
        TorfilxLog.w(TAG, "Bundled CA store unreadable; falling back to system trust only", error)
        null
    }
    if (bundled == null) {
        TorfilxLog.w(TAG, "Bundled CA store produced no trust manager; system trust only")
        return this
    }

    return runCatching {
        val composite = CompositeTrustManager(system, bundled)
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<javax.net.ssl.TrustManager>(composite), null)
        }
        TorfilxLog.i(TAG, "Bundled certificate authorities enabled for API ${Build.VERSION.SDK_INT}")
        sslSocketFactory(ModernProtocolSocketFactory(sslContext.socketFactory), composite)
    }.getOrElse { error ->
        // A device that will not give us an SSLContext still runs; it just keeps the stock trust.
        TorfilxLog.w(TAG, "Could not install the bundled CA store", error)
        this
    }
}

/** The platform's own trust manager, which stays authoritative. */
private fun systemTrustManager(): X509TrustManager? = runCatching {
    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        .apply { init(null as KeyStore?) }
        .trustManagers
        .filterIsInstance<X509TrustManager>()
        .firstOrNull()
}.getOrNull()

/**
 * A trust manager over the CA bundle shipped in `res/raw/cacerts.pem`.
 *
 * The blocks are cut out and decoded by hand rather than handing the whole file to
 * `generateCertificates`: the Mozilla bundle carries a name and a comment header above every
 * certificate, and the CertificateFactory on Android 5.1 returns an empty collection when it meets
 * them — silently, which is how this went unnoticed the first time.
 */
private fun bundledTrustManager(context: Context): X509TrustManager? {
    val factory = CertificateFactory.getInstance("X.509")
    val pem = context.resources.openRawResource(R.raw.cacerts).use { stream ->
        stream.reader().readText()
    }

    val certificates = PEM_CERTIFICATE.findAll(pem).mapNotNull { match ->
        runCatching {
            val der = Base64.decode(match.groupValues[1], Base64.DEFAULT)
            factory.generateCertificate(der.inputStream())
        }.getOrNull()
    }.toList()

    TorfilxLog.i(TAG, "Bundled CA store: ${certificates.size} certificates")
    if (certificates.isEmpty()) return null

    val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
        load(null, null)
        certificates.forEachIndexed { index, certificate ->
            setCertificateEntry("ca-$index", certificate)
        }
    }
    return TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        .apply { init(keyStore) }
        .trustManagers
        .filterIsInstance<X509TrustManager>()
        .firstOrNull()
}

/**
 * System store first, bundled roots second.
 *
 * Order matters: anything the device already trusts keeps validating exactly as before, so this can
 * only ever widen what connects, never change how an already-working chain is judged.
 */
private class CompositeTrustManager(
    private val system: X509TrustManager,
    private val bundled: X509TrustManager,
) : X509TrustManager {

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        try {
            system.checkServerTrusted(chain, authType)
        } catch (systemFailure: CertificateException) {
            try {
                bundled.checkServerTrusted(chain, authType)
            } catch (bundledFailure: CertificateException) {
                // Neither store accepts it: report the system's verdict, which is the meaningful one.
                bundledFailure.initCause(systemFailure)
                throw bundledFailure
            }
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        system.checkClientTrusted(chain, authType)

    override fun getAcceptedIssuers(): Array<X509Certificate> =
        system.acceptedIssuers + bundled.acceptedIssuers
}

/**
 * Turns on every TLS version the device actually supports.
 *
 * Android 5.1 supports TLS 1.1 and 1.2 but does not always enable them on a new socket, and a
 * TLS-1.0-only handshake is refused outright by most hosts today.
 */
private class ModernProtocolSocketFactory(
    private val delegate: SSLSocketFactory,
) : SSLSocketFactory() {

    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites

    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    override fun createSocket(socket: Socket?, host: String?, port: Int, autoClose: Boolean) =
        delegate.createSocket(socket, host, port, autoClose).enableModernProtocols()

    override fun createSocket(host: String?, port: Int) =
        delegate.createSocket(host, port).enableModernProtocols()

    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int) =
        delegate.createSocket(host, port, localHost, localPort).enableModernProtocols()

    override fun createSocket(host: InetAddress?, port: Int) =
        delegate.createSocket(host, port).enableModernProtocols()

    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int) =
        delegate.createSocket(address, port, localAddress, localPort).enableModernProtocols()

    private fun Socket.enableModernProtocols(): Socket = apply {
        if (this is SSLSocket) {
            runCatching { enabledProtocols = supportedProtocols }
        }
    }
}

/** One base64 body per `-----BEGIN CERTIFICATE-----` block. */
private val PEM_CERTIFICATE =
    Regex("-----BEGIN CERTIFICATE-----(.*?)-----END CERTIFICATE-----", RegexOption.DOT_MATCHES_ALL)

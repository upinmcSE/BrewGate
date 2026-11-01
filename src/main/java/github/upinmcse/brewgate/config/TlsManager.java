package github.upinmcse.brewgate.config;

import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Collection;
import java.util.concurrent.Executors;

public class TlsManager {
    private static final Logger log = LoggerFactory.getLogger(TlsManager.class);

    /**
     * Loads a certificate file (.crt) and a private key file (.key) to build an SSLContext.
     */
    public static SSLContext createSSLContext(String certPath, String keyPath) throws Exception {
        log.info("Loading TLS Certificate from '{}' and Key from '{}'...", certPath, keyPath);

        // 1. Load the certificate chain (X.509 certificates)
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        Collection<? extends Certificate> certs;
        try (InputStream is = Files.newInputStream(Path.of(certPath))) {
            certs = cf.generateCertificates(is);
        }
        Certificate[] certChain = certs.toArray(new Certificate[0]);

        // 2. Load the RSA private key in PKCS#8 format
        String keyContent = Files.readString(Path.of(keyPath))
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");

        byte[] keyBytes = Base64.getDecoder().decode(keyContent);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        PrivateKey privateKey = kf.generatePrivate(spec);

        // 3. Create an in-memory PKCS12 KeyStore
        char[] password = "changeit".toCharArray();
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        keyStore.setKeyEntry("gateway-tls-key", privateKey, password, certChain);

        // 4. Initialize the KeyManagerFactory and SSLContext
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, password);
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), null, null);

        log.info("SSLContext initialized successfully with TLS 1.3 support.");
        return sslContext;
    }

    /**
     * Starts a secondary HTTP server that redirects all traffic to HTTPS (301 Moved Permanently).
     */
    public static void startHttpToHttpsRedirectServer(int httpPort, String httpsPort) {
        Thread.ofVirtual().name("http-redirect-server").start(() -> {
            try {
                HttpServer redirectServer = HttpServer.create(new InetSocketAddress(httpPort), 0);
                redirectServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
                redirectServer.createContext("/", exchange -> {
                    // Strip the port from the Host header, if present
                    String host = exchange.getRequestHeaders().getFirst("Host");
                    if (host != null && host.contains(":")) {
                        host = host.substring(0, host.indexOf(":"));
                    }
                    // Fall back to localhost when the Host header is missing or blank
                    if (host == null || host.isBlank()) {
                        host = "localhost";
                    }
                    // Rebuild the target URL, preserving the raw path and query string
                    URI reqUri = exchange.getRequestURI();
                    String redirectUrl = "https://" + host + ":" + httpsPort + reqUri.getRawPath()
                            + (reqUri.getRawQuery() != null ? "?" + reqUri.getRawQuery() : "");

                    log.debug("Redirecting HTTP request to HTTPS: {}", redirectUrl);

                    exchange.getResponseHeaders().set("Location", redirectUrl);
                    exchange.sendResponseHeaders(301, -1);
                });

                log.info("Starting HTTP-to-HTTPS redirect server on port {}", httpPort);
                redirectServer.start();
            } catch (Exception e) {
                log.error("Failed to start HTTP-to-HTTPS redirect server: {}", e.getMessage());
            }
        });
    }
}

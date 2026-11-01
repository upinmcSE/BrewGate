package github.upinmcse.brewgate.middleware.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class JwksKeyResolver {
    private static final Logger log = LoggerFactory.getLogger(JwksKeyResolver.class);

    private final String jwksUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Map<String, PublicKey> keyCache = new ConcurrentHashMap<>();
    private long lastFetchTime = 0;
    private static final long CACHE_TTL_MILLIS = 3600 * 1000;

    public JwksKeyResolver(String jwksUrl) {
        this.jwksUrl = jwksUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.objectMapper = new ObjectMapper();
        refreshKeys();
    }

    public synchronized PublicKey getKey(String kid) {
        if (kid == null) {
            if (keyCache.size() == 1) {
                return keyCache.values().iterator().next();
            }
            return null;
        }
        PublicKey key = keyCache.get(kid);
        if (key != null && (System.currentTimeMillis() - lastFetchTime < CACHE_TTL_MILLIS)) {
            return key;
        }
        log.info("Key ID '{}' not found in cache. Refreshing JWKS from IdP: {}", kid, jwksUrl);
        refreshKeys();
        return keyCache.get(kid);
    }

    private void refreshKeys() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(jwksUrl))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.error("Failed to fetch JWKS from {}: HTTP {}", jwksUrl, response.statusCode());
                return;
            }

            JsonNode rootNode = objectMapper.readTree(response.body());
            JsonNode keysNode = rootNode.get("keys");
            if (keysNode == null || !keysNode.isArray()) {
                log.warn("Invalid JWKS payload: missing 'keys' array");
                return;
            }

            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            for (JsonNode kNode : keysNode) {
                String kty = kNode.path("kty").asString();
                if (!"RSA".equalsIgnoreCase(kty)) {
                    continue;
                }
                String kid = kNode.path("kid").asString(null);
                String nStr = kNode.path("n").asString();
                String eStr = kNode.path("e").asString();
                if (nStr != null && eStr != null) {
                    byte[] nBytes = Base64.getUrlDecoder().decode(nStr);
                    byte[] eBytes = Base64.getUrlDecoder().decode(eStr);
                    BigInteger modulus = new BigInteger(1, nBytes);
                    BigInteger exponent = new BigInteger(1, eBytes);
                    RSAPublicKeySpec spec = new RSAPublicKeySpec(modulus, exponent);
                    PublicKey publicKey = keyFactory.generatePublic(spec);
                    if (kid != null) {
                        keyCache.put(kid, publicKey);
                        log.debug("Cached RSA Public Key with kid '{}' from JWKS.", kid);
                    }
                }
            }
            lastFetchTime = System.currentTimeMillis();
            log.info("Successfully refreshed {} RSA Public Keys from JWKS.", keyCache.size());
        } catch (Exception e) {
            log.error("Error fetching/parsing JWKS from {}: {}", jwksUrl, e.getMessage());
        }
    }
}

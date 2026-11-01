package github.upinmcse.brewgate.middleware.auth;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import github.upinmcse.brewgate.config.AuthConfig;
import github.upinmcse.brewgate.middleware.HttpMiddleware;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.JwtParserBuilder;
import io.jsonwebtoken.Jwts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

public class JwtAuthMiddleware implements HttpMiddleware {
    private static final Logger log = LoggerFactory.getLogger(JwtAuthMiddleware.class);
    private final JwtParser jwtParser;

    public JwtAuthMiddleware(AuthConfig authConfig) {
        JwtParserBuilder parserBuilder = Jwts.parser();
        // 1. IDP mode: Keycloak, AWS Cognito, Auth0... (use JWKS)
        if (authConfig.getJwksUrl() != null && !authConfig.getJwksUrl().isBlank()) {
            JwksKeyResolver jwksResolver = new JwksKeyResolver(authConfig.getJwksUrl());
            parserBuilder.keyLocator(header -> {
                Object kid = header.get("kid");
                PublicKey key = jwksResolver.getKey(kid != null ? kid.toString() : null);
                if (key == null) {
                    log.warn("Cannot find matching public key for kid: {}", kid);
                }
                return key;
            });
            log.info("JWT Auth initialized in IdP Mode using JWKS URL: {}", authConfig.getJwksUrl());
            // 2. static key file mod : .pem file
        } else if (authConfig.getPublicKeyPath() != null && !authConfig.getPublicKeyPath().isBlank()) {
            PublicKey localKey = loadLocalPublicKey(authConfig.getPublicKeyPath());
            parserBuilder.verifyWith(localKey);
            log.info("JWT Auth initialized in Local Mode using file: {}", authConfig.getPublicKeyPath());
        } else {
            throw new IllegalArgumentException("JWT Auth enabled but neither 'jwksUrl' nor 'publicKeyPath' is configured!");
        }

        if (authConfig.getExpectedIssuer() != null && !authConfig.getExpectedIssuer().isBlank()) {
            parserBuilder.requireIssuer(authConfig.getExpectedIssuer());
            log.info("Enforcing expected issuer: {}", authConfig.getExpectedIssuer());
        }
        this.jwtParser = parserBuilder.build();
    }

    private PublicKey loadLocalPublicKey(String path) {
        try {
            String content = Files.readString(Path.of(path))
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s+", "");
            byte[] decoded = Base64.getDecoder().decode(content);
            KeyFactory kf = KeyFactory.getInstance("RSA");
            return kf.generatePublic(new X509EncodedKeySpec(decoded));
        } catch (Exception e) {
            throw new RuntimeException("Failed to read local public key from " + path + ": " + e.getMessage(), e);
        }
    }

    @Override
    public HttpHandler apply(HttpHandler next) {
        return exchange -> {
            String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                log.warn("Missing or invalid Authorization header from {}", exchange.getRemoteAddress());
                sendErrorResponse(exchange, 401, "401 Unauthorized: Missing or invalid Authorization header");
                return;
            }

            String token = authHeader.substring(7).trim();
            try {
                Claims claims = jwtParser.parseSignedClaims(token).getPayload();
                String userId = claims.getSubject();
                if (userId == null || userId.isBlank()) {
                    log.warn("Unauthorized: Missing subject (sub) claim in token");
                    sendErrorResponse(exchange, 401, "401 Unauthorized: Missing subject (sub)");
                    return;
                }

                exchange.setAttribute("userID", userId);
                log.debug("Authenticated user '{}' for path '{}'", userId, exchange.getRequestURI().getPath());

                next.handle(exchange);
            } catch (Exception e) {
                log.warn("Token validation failed: {}", e.getMessage());
                sendErrorResponse(exchange, 401, "401 Unauthorized: " + e.getMessage());
            }
        };
    }
    private void sendErrorResponse(HttpExchange exchange, int statusCode, String message) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/plain");
        exchange.sendResponseHeaders(statusCode, message.getBytes().length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(message.getBytes());
        }
    }
}

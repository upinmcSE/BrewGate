package github.upinmcse.brewgate;

import io.jsonwebtoken.Jwts;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;

public class TokenGenerator {

    public static void main(String[] args) throws Exception {
        new File("config").mkdirs();
        Path privPath = Path.of("config/private.pem");
        Path pubPath = Path.of("config/public.pem");
        PrivateKey privateKey;

        if (!Files.exists(privPath) || !Files.exists(pubPath)) {
            System.out.println("Generating new RSA 2048-bit keypair...");

            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);

            KeyPair kp = kpg.generateKeyPair();
            privateKey = kp.getPrivate();
            PublicKey publicKey = kp.getPublic();

            String pubPem = "-----BEGIN PUBLIC KEY-----\n" +
                    Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(publicKey.getEncoded()) +
                    "\n-----END PUBLIC KEY-----\n";

            Files.writeString(pubPath, pubPem);

            String privPem = "-----BEGIN PRIVATE KEY-----\n" +
                    Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(privateKey.getEncoded()) +
                    "\n-----END PRIVATE KEY-----\n";

            Files.writeString(privPath, privPem);

            System.out.println("Saved keys to config/public.pem and config/private.pem");
        } else {
            String privStr = Files.readString(privPath)
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s+", "");
            byte[] privBytes = Base64.getDecoder().decode(privStr);
            KeyFactory kf = KeyFactory.getInstance("RSA");
            privateKey = kf.generatePrivate(new PKCS8EncodedKeySpec(privBytes));
        }

        String token = Jwts.builder()
                .header().keyId("local-test-key-1").and()
                .subject("alice-999")
                .issuer("MyIdentityServer")
                .claim("role", "admin")
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plus(1, ChronoUnit.DAYS)))
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();

        System.out.println("\n================ VALID RS256 JWT TOKEN ================");
        System.out.println(token);
        System.out.println("========================================================\n");
    }
}
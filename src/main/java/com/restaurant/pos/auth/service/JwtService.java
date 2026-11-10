package com.restaurant.pos.auth.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.io.Decoders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

@Service
public class JwtService {

    /** Claim carrying the token scope. Absent on normal login tokens. */
    public static final String SCOPE_CLAIM = "scope";
    /** Scope of short-lived, read-only tokens issued to the CafeQR AI assistant / MCP server. */
    public static final String AI_READ_SCOPE = "ai:read";

    @Value("${jwt.private-key}")
    private String privateKeyStr;

    @Value("${jwt.public-key}")
    private String publicKeyStr;

    @Value("${jwt.expiration}")
    private long jwtExpiration;

    @Value("${jwt.refresh-expiration}")
    private long refreshExpiration;

    private PrivateKey privateKey;
    private PublicKey publicKey;

    @PostConstruct
    public void init() {
        try {
            if (privateKeyStr == null || privateKeyStr.trim().isEmpty() || publicKeyStr == null || publicKeyStr.trim().isEmpty()) {
                java.security.KeyPairGenerator keyPairGenerator = java.security.KeyPairGenerator.getInstance("RSA");
                keyPairGenerator.initialize(2048);
                java.security.KeyPair keyPair = keyPairGenerator.generateKeyPair();
                this.privateKey = keyPair.getPrivate();
                this.publicKey = keyPair.getPublic();
            } else {
                this.privateKey = loadPrivateKey(privateKeyStr);
                this.publicKey = loadPublicKey(publicKeyStr);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize JWT keys. Please check your application.yml configuration for jwt.private-key and jwt.public-key.", e);
        }
    }

    private PrivateKey loadPrivateKey(String key) throws NoSuchAlgorithmException, InvalidKeySpecException {
        try {
            byte[] keyBytes = Decoders.BASE64.decode(key.trim());
            PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
            KeyFactory kf = KeyFactory.getInstance("RSA");
            return kf.generatePrivate(spec);
        } catch (IllegalArgumentException e) {
            throw new InvalidKeySpecException("Invalid Base64 encoding for private key", e);
        }
    }

    private PublicKey loadPublicKey(String key) throws NoSuchAlgorithmException, InvalidKeySpecException {
        try {
            byte[] keyBytes = Decoders.BASE64.decode(key.trim());
            X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
            KeyFactory kf = KeyFactory.getInstance("RSA");
            return kf.generatePublic(spec);
        } catch (IllegalArgumentException e) {
            throw new InvalidKeySpecException("Invalid Base64 encoding for public key", e);
        }
    }

    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    public String generateToken(UserDetails userDetails) {
        return generateToken(new HashMap<>(), userDetails);
    }

    public String generateToken(Map<String, Object> extraClaims, UserDetails userDetails) {
        return buildToken(extraClaims, userDetails, jwtExpiration);
    }

    /** Issues a token with a custom lifetime (used for short-lived scoped tokens such as {@link #AI_READ_SCOPE}). */
    public String generateToken(Map<String, Object> extraClaims, UserDetails userDetails, long expirationMs) {
        return buildToken(extraClaims, userDetails, expirationMs);
    }

    /** Extracts the scope claim, or null for normal (unscoped) tokens. */
    public String extractScope(String token) {
        return extractClaim(token, claims -> {
            Object scope = claims.get(SCOPE_CLAIM);
            return scope != null ? scope.toString() : null;
        });
    }

    /** Base64 (X.509) encoded RSA public key, so other services can verify tokens without the signing key. */
    public String getPublicKeyBase64() {
        return java.util.Base64.getEncoder().encodeToString(publicKey.getEncoded());
    }

    public String generateRefreshToken(UserDetails userDetails) {
        return buildToken(new HashMap<>(), userDetails, refreshExpiration);
    }

    private String buildToken(Map<String, Object> extraClaims, UserDetails userDetails, long expiration) {
        return Jwts.builder()
                .setClaims(extraClaims)
                .setSubject(userDetails.getUsername())
                .setIssuedAt(new Date(System.currentTimeMillis()))
                .setExpiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(privateKey, SignatureAlgorithm.RS256)
                .compact();
    }

    public boolean isTokenValid(String token, UserDetails userDetails) {
        final String username = extractUsername(token);
        return (username.equals(userDetails.getUsername())) && !isTokenExpired(token);
    }

    private boolean isTokenExpired(String token) {
        return extractExpiration(token).before(new Date());
    }

    private Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(publicKey)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }
}

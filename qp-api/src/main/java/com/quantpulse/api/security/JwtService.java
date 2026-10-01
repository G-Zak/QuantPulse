package com.quantpulse.api.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * Creates and checks JWTs.
 *
 * The API is stateless, so any instance can check a token without a session store.
 * Downside: a JWT can't be revoked before it expires, so the TTL is short (30 min).
 * HS256 is fine because this service is the only one that issues and checks tokens.
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final Duration ttl;
    private final String issuer;

    public JwtService(@Value("${quantpulse.security.jwt-secret}") String secret,
                      @Value("${quantpulse.security.jwt-ttl-minutes:30}") long ttlMinutes,
                      @Value("${quantpulse.security.jwt-issuer:quantpulse}") String issuer) {
        // HS256 needs a key of at least 256 bits. A short secret fails at startup.
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttl = Duration.ofMinutes(ttlMinutes);
        this.issuer = issuer;
    }

    public String issue(String username, List<String> roles) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(username)
                .issuer(issuer)
                .claim("roles", roles)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }

    /** @return the claims, or null if the token is invalid, expired or changed. */
    public Claims parse(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(issuer)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (Exception e) {
            // Whatever the reason, it's just "not authenticated". We don't tell the client why.
            return null;
        }
    }

    public long ttlSeconds() {
        return ttl.toSeconds();
    }
}

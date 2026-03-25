package com.sismics.util.filter;

import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.JWT;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.auth0.jwt.interfaces.JWTVerifier;

import java.io.IOException;
import java.io.Reader;
import java.util.Base64;

import com.sismics.docs.core.constant.Constants;
import com.sismics.docs.core.dao.UserDao;
import com.sismics.docs.core.model.jpa.User;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.http.HttpServletRequest;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.interfaces.RSAPublicKey;
import java.util.Objects;
import java.util.UUID;

import static java.util.Optional.ofNullable;

/**
 * This filter is used to authenticate the user having an active session by validating a jwt token.
 * The filter extracts the jwt token stored from Authorization header.
 * It validates the token by calling an Identity Broker like KeyCloak.
 * If validated, the user is retrieved, and the filter injects a UserPrincipal into the request attribute.
 *
 * @author smitra
 */
public class JwtBasedSecurityFilter extends SecurityFilter {
    private static final Logger log = LoggerFactory.getLogger(JwtBasedSecurityFilter.class);
    private static final okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();

    public static final String HEADER_NAME = "Authorization";

    private boolean enabled;
    private String expectedIssuer;
    private String expectedAudience;

    @Override
    public void init(FilterConfig filterConfig) {
        enabled = Boolean.parseBoolean(filterConfig.getInitParameter("enabled"))
                || Boolean.parseBoolean(System.getProperty("docs.jwt_authentication"));

        if (enabled) {
            expectedIssuer = System.getProperty("docs.jwt_expected_issuer");
            expectedAudience = System.getProperty("docs.jwt_expected_audience");
            if (expectedIssuer == null || expectedIssuer.isBlank()
                    || expectedAudience == null || expectedAudience.isBlank()) {
                log.error("JWT authentication is enabled but docs.jwt_expected_issuer and/or "
                        + "docs.jwt_expected_audience are not configured. Disabling JWT auth.");
                enabled = false;
            } else {
                log.info("JWT authentication enabled: issuer={}, audience={}", expectedIssuer, expectedAudience);
            }
        }
    }

    @Override
    protected User authenticate(final HttpServletRequest request) {
        if (!enabled) {
            return null;
        }
        log.debug("JWT authentication started");
        User user = null;
        String token = extractAuthToken(request).replace("Bearer ", "");
        if (token.isEmpty()) {
            return null;
        }
        DecodedJWT jwt = JWT.decode(token);
        if (verifyJwt(jwt, token)) {
            String username = jwt.getClaim("preferred_username").asString();
            if (username == null || username.isBlank()) {
                log.warn("JWT token missing preferred_username claim");
                return null;
            }
            UserDao userDao = new UserDao();
            user = userDao.getActiveByUsername(username);
            if (user == null) {
                user = new User();
                user.setRoleId(Constants.DEFAULT_USER_ROLE);
                user.setUsername(username);
                user.setEmail(username);
                user.setStorageQuota(Long.parseLong(ofNullable(System.getenv(Constants.GLOBAL_QUOTA_ENV))
                        .orElse("1073741824")));
                user.setPassword(UUID.randomUUID().toString());
                try {
                    userDao.create(user, username);
                    log.info("Provisioned JWT user: {}", username);
                } catch (Exception e) {
                    log.error("Error creating JWT user: {}", username, e);
                    return null;
                }
            }
        }
        return user;
    }

    private boolean verifyJwt(final DecodedJWT jwt, final String token) {
        try {
            buildJWTVerifier(jwt).verify(token);
            log.debug("JWT token verified successfully");
            return true;
        } catch (CertificateException e) {
            log.warn("JWT verification failed (certificate): {}", e.getMessage());
            return false;
        } catch (JWTVerificationException e) {
            log.warn("JWT verification failed: {}", e.getMessage());
            return false;
        } catch (Exception e) {
            log.warn("JWT verification failed (unexpected): {}", e.getMessage());
            return false;
        }
    }

    private String extractAuthToken(final HttpServletRequest request) {
        return ofNullable(request.getHeader("Authorization")).orElse("");
    }

    // TODO: JWKS is fetched on every request; should be cached with TTL
    private RSAPublicKey getPublicKey(DecodedJWT jwt) {
        String jwtIssuerCerts = jwt.getIssuer() + "/protocol/openid-connect/certs";
        RSAPublicKey rsaPublicKey = null;
        Request request = new Request.Builder()
                .url(jwtIssuerCerts)
                .get()
                .build();
        try (Response response = client.newCall(request).execute()) {
            log.debug("Called JWT issuer JWKS at: {} - {}", jwtIssuerCerts, response.code());
            assert response.body() != null;
            if (response.isSuccessful()) {
                try (Reader reader = response.body().charStream()) {
                    try (JsonReader jsonReader = Json.createReader(reader)) {
                        JsonObject jwks = jsonReader.readObject();
                        JsonArray keys = jwks.getJsonArray("keys");
                        String publicKey = keys.stream().filter(key -> Objects.equals(key.asJsonObject().getString("kid"),
                                        jwt.getKeyId()))
                                .findFirst()
                                .map(k -> k.asJsonObject().getJsonArray("x5c").getString(0))
                                .orElse("");
                        var decode = Base64.getDecoder().decode(publicKey);
                        var certificate = CertificateFactory.getInstance("X.509")
                                .generateCertificate(new ByteArrayInputStream(decode));
                        rsaPublicKey = (RSAPublicKey) certificate.getPublicKey();
                    }
                }
            }
        } catch (IOException e) {
            log.error("Error calling JWT issuer JWKS at: {}", jwtIssuerCerts, e);
        } catch (CertificateException e) {
            log.error("Error parsing certificate from JWKS", e);
        }
        return rsaPublicKey;
    }

    private JWTVerifier buildJWTVerifier(DecodedJWT jwt) throws CertificateException {
        var algo = Algorithm.RSA256(getPublicKey(jwt), null);
        return JWT.require(algo)
                .withIssuer(expectedIssuer)
                .withAudience(expectedAudience)
                .build();
    }
}

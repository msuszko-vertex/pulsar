/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.broker.authentication;

import static java.nio.charset.StandardCharsets.UTF_8;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwsHeader;
import io.jsonwebtoken.Jwt;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.JwtParserBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.RequiredTypeException;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.SigningKeyResolver;
import io.jsonwebtoken.security.SignatureException;
import io.prometheus.client.Counter;
import io.prometheus.client.Histogram;
import java.io.IOException;
import java.math.BigInteger;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.Key;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.InvalidParameterSpecException;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import javax.naming.AuthenticationException;
import javax.net.ssl.SSLSession;
import javax.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.pulsar.broker.ServiceConfiguration;
import org.apache.pulsar.broker.authentication.metrics.AuthenticationMetrics;
import org.apache.pulsar.broker.authentication.utils.AuthTokenUtils;
import org.apache.pulsar.common.api.AuthData;

public class AuthenticationProviderToken implements AuthenticationProvider {

    static final String HTTP_HEADER_NAME = "Authorization";
    static final String HTTP_HEADER_VALUE_PREFIX = "Bearer ";

    // When symmetric key is configured
    static final String CONF_TOKEN_SETTING_PREFIX = "tokenSettingPrefix";

    // When symmetric key is configured
    static final String CONF_TOKEN_SECRET_KEY = "tokenSecretKey";

    // When public/private key pair is configured
    static final String CONF_TOKEN_PUBLIC_KEY = "tokenPublicKey";
    static final String CONF_TOKEN_KEY_SET_KEY = "tokenKeySetKey";

    // The token's claim that corresponds to the "role" string
    static final String CONF_TOKEN_AUTH_CLAIM = "tokenAuthClaim";

    // When using public key's, the algorithm of the key
    static final String CONF_TOKEN_PUBLIC_ALG = "tokenPublicAlg";

    // The token audience "claim" name, e.g. "aud", that will be used to get the audience from token.
    static final String CONF_TOKEN_AUDIENCE_CLAIM = "tokenAudienceClaim";

    // The token audience stands for this broker. The field `tokenAudienceClaim` of a valid token, need contains this.
    static final String CONF_TOKEN_AUDIENCE = "tokenAudience";

    static final String TOKEN = "token";

    private static final Counter expiredTokenMetrics = Counter.build()
            .name("pulsar_expired_token_count")
            .help("Pulsar expired token")
            .register();

    private static final Histogram expiringTokenMinutesMetrics = Histogram.build()
            .name("pulsar_expiring_token_minutes")
            .help("The remaining time of expiring token in minutes")
            .buckets(5, 10, 60, 240)
            .register();

    private String roleClaim;
    private SignatureAlgorithm publicKeyAlg;
    private String audienceClaim;
    private String audience;
    private JwtParser parser;

    // config keys
    private String confTokenSecretKeySettingName;
    private String confTokenPublicKeySettingName;
    private String confTokenKeySetSettingName;
    private String confTokenAuthClaimSettingName;
    private String confTokenPublicAlgSettingName;
    private String confTokenAudienceClaimSettingName;
    private String confTokenAudienceSettingName;

    @Override
    public void close() throws IOException {
        // noop
    }

    @VisibleForTesting
    public static void resetMetrics() {
        expiredTokenMetrics.clear();
        expiringTokenMinutesMetrics.clear();
    }

    @Override
    public void initialize(ServiceConfiguration config) throws IOException, IllegalArgumentException {
        String prefix = (String) config.getProperty(CONF_TOKEN_SETTING_PREFIX);
        if (null == prefix) {
            prefix = "";
        }
        this.confTokenSecretKeySettingName = prefix + CONF_TOKEN_SECRET_KEY;
        this.confTokenPublicKeySettingName = prefix + CONF_TOKEN_PUBLIC_KEY;
        this.confTokenAuthClaimSettingName = prefix + CONF_TOKEN_AUTH_CLAIM;
        this.confTokenPublicAlgSettingName = prefix + CONF_TOKEN_PUBLIC_ALG;
        this.confTokenAudienceClaimSettingName = prefix + CONF_TOKEN_AUDIENCE_CLAIM;
        this.confTokenAudienceSettingName = prefix + CONF_TOKEN_AUDIENCE;
        this.confTokenKeySetSettingName = prefix + CONF_TOKEN_KEY_SET_KEY;

        // we need to fetch the algorithm before we fetch the key
        this.publicKeyAlg = getPublicKeyAlgType(config);
        this.roleClaim = getTokenRoleClaim(config);
        this.audienceClaim = getTokenAudienceClaim(config);
        this.audience = getTokenAudience(config);

        Key validationKey = getValidationKey(config);
        JwtParserBuilder jwtParserBuilder = Jwts.parserBuilder();
        if (validationKey != null) {
            jwtParserBuilder.setSigningKey(validationKey);
        } else {
            String jwks = (String) config.getProperty(confTokenKeySetSettingName);
            if (StringUtils.isEmpty(jwks)) {
                throw new IOException("No secret/Public key was provided for token authentication");
            }
            jwtParserBuilder.setSigningKeyResolver(new TokenSigningKeyResolver(jwks));
        }

        this.parser = jwtParserBuilder.build();

        if (audienceClaim != null && audience == null) {
            throw new IllegalArgumentException("Token Audience Claim [" + audienceClaim
                    + "] configured, but Audience stands for this broker not.");
        }
    }

    @Override
    public String getAuthMethodName() {
        return TOKEN;
    }

    @Override
    public String authenticate(AuthenticationDataSource authData) throws AuthenticationException {
        try {
            // Get Token
            String token;
            token = getToken(authData);
            // Parse Token by validating
            String role = getPrincipal(authenticateToken(token));
            AuthenticationMetrics.authenticateSuccess(getClass().getSimpleName(), getAuthMethodName());
            return role;
        } catch (AuthenticationException exception) {
            AuthenticationMetrics.authenticateFailure(getClass().getSimpleName(), getAuthMethodName(),
                    exception.getMessage());
            throw exception;
        }
    }

    @Override
    public AuthenticationState newAuthState(AuthData authData, SocketAddress remoteAddress, SSLSession sslSession)
            throws AuthenticationException {
        return new TokenAuthenticationState(this, authData, remoteAddress, sslSession);
    }

    public static String getToken(AuthenticationDataSource authData) throws AuthenticationException {
        if (authData.hasDataFromCommand()) {
            // Authenticate Pulsar binary connection
            return validateToken(authData.getCommandData());
        } else if (authData.hasDataFromHttp()) {
            // Authentication HTTP request. The format here should be compliant to RFC-6750
            // (https://tools.ietf.org/html/rfc6750#section-2.1). Eg: Authorization: Bearer xxxxxxxxxxxxx
            String httpHeaderValue = authData.getHttpHeader(HTTP_HEADER_NAME);
            if (httpHeaderValue == null || !httpHeaderValue.startsWith(HTTP_HEADER_VALUE_PREFIX)) {
                throw new AuthenticationException("Invalid HTTP Authorization header");
            }

            // Remove prefix
            String token = httpHeaderValue.substring(HTTP_HEADER_VALUE_PREFIX.length());
            return validateToken(token);
        } else {
            throw new AuthenticationException("No token credentials passed");
        }
    }

    private static String validateToken(final String token) throws AuthenticationException {
        if (StringUtils.isNotBlank(token)) {
            return token;
        } else {
            throw new AuthenticationException("Blank token found");
        }
    }

    @SuppressWarnings("unchecked")
    private Jwt<?, Claims> authenticateToken(final String token) throws AuthenticationException {
        try {
            Jwt<?, Claims> jwt = parser.parseClaimsJws(token);

            if (audienceClaim != null) {
                Object object = jwt.getBody().get(audienceClaim);
                if (object == null) {
                    throw new JwtException("Found null Audience in token, for claimed field: " + audienceClaim);
                }

                if (object instanceof List) {
                    List<String> audiences = (List<String>) object;
                    // audience not contains this broker, throw exception.
                    if (audiences.stream().noneMatch(audienceInToken -> audienceInToken.equals(audience))) {
                        throw new AuthenticationException("Audiences in token: [" + String.join(", ", audiences)
                                                          + "] not contains this broker: " + audience);
                    }
                } else if (object instanceof String) {
                    if (!object.equals(audience)) {
                        throw new AuthenticationException("Audiences in token: [" + object
                                                          + "] not contains this broker: " + audience);
                    }
                } else {
                    // should not reach here.
                    throw new AuthenticationException("Audiences in token is not in expected format: " + object);
                }
            }

            if (jwt.getBody().getExpiration() != null) {
                expiringTokenMinutesMetrics.observe(
                        (double) (jwt.getBody().getExpiration().getTime() - new Date().getTime()) / (60 * 1000));
            }
            return jwt;
        } catch (JwtException e) {
            if (e instanceof ExpiredJwtException) {
                expiredTokenMetrics.inc();
            }
            throw new AuthenticationException("Failed to authentication token: " + e.getMessage());
        }
    }

    private String getPrincipal(Jwt<?, Claims> jwt) {
        try {
            return jwt.getBody().get(roleClaim, String.class);
        } catch (RequiredTypeException requiredTypeException) {
            List list = jwt.getBody().get(roleClaim, List.class);
            if (list != null && !list.isEmpty() && list.get(0) instanceof String) {
                return (String) list.get(0);
            }
            return null;
        }
    }

    /**
     * Try to get the validation key for tokens from several possible config options.
     */
    private Key getValidationKey(ServiceConfiguration conf) throws IOException {
        String tokenSecretKey = (String) conf.getProperty(confTokenSecretKeySettingName);
        String tokenPublicKey = (String) conf.getProperty(confTokenPublicKeySettingName);
        if (StringUtils.isNotBlank(tokenSecretKey)) {
            final byte[] validationKey = AuthTokenUtils.readKeyFromUrl(tokenSecretKey);
            return AuthTokenUtils.decodeSecretKey(validationKey);
        } else if (StringUtils.isNotBlank(tokenPublicKey)) {
            final byte[] validationKey = AuthTokenUtils.readKeyFromUrl(tokenPublicKey);
            return AuthTokenUtils.decodePublicKey(validationKey, publicKeyAlg);
        } else {
            return null;
        }
    }

    private String getTokenRoleClaim(ServiceConfiguration conf) throws IOException {
        String tokenAuthClaim = (String) conf.getProperty(confTokenAuthClaimSettingName);
        if (StringUtils.isNotBlank(tokenAuthClaim)) {
            return tokenAuthClaim;
        } else {
            return Claims.SUBJECT;
        }
    }

    private SignatureAlgorithm getPublicKeyAlgType(ServiceConfiguration conf) throws IllegalArgumentException {
        String tokenPublicAlg = (String) conf.getProperty(confTokenPublicAlgSettingName);
        if (StringUtils.isNotBlank(tokenPublicAlg)) {
            try {
                return SignatureAlgorithm.forName(tokenPublicAlg);
            } catch (SignatureException ex) {
                throw new IllegalArgumentException("invalid algorithm provided " + tokenPublicAlg, ex);
            }
        } else {
            return SignatureAlgorithm.RS256;
        }
    }

    // get Token Audience Claim from configuration, if not configured return null.
    private String getTokenAudienceClaim(ServiceConfiguration conf) throws IllegalArgumentException {
        String tokenAudienceClaim = (String) conf.getProperty(confTokenAudienceClaimSettingName);
        if (StringUtils.isNotBlank(tokenAudienceClaim)) {
            return tokenAudienceClaim;
        } else {
            return null;
        }
    }

    // get Token Audience that stands for this broker from configuration, if not configured return null.
    private String getTokenAudience(ServiceConfiguration conf) throws IllegalArgumentException {
        String tokenAudience = (String) conf.getProperty(confTokenAudienceSettingName);
        if (StringUtils.isNotBlank(tokenAudience)) {
            return tokenAudience;
        } else {
            return null;
        }
    }

    private static final class TokenAuthenticationState implements AuthenticationState {
        private final AuthenticationProviderToken provider;
        private AuthenticationDataSource authenticationDataSource;
        private Jwt<?, Claims> jwt;
        private final SocketAddress remoteAddress;
        private final SSLSession sslSession;
        private long expiration;

        TokenAuthenticationState(
                AuthenticationProviderToken provider,
                AuthData authData,
                SocketAddress remoteAddress,
                SSLSession sslSession) throws AuthenticationException {
            this.provider = provider;
            this.remoteAddress = remoteAddress;
            this.sslSession = sslSession;
            this.authenticate(authData);
        }

        @Override
        public String getAuthRole() throws AuthenticationException {
            return provider.getPrincipal(jwt);
        }

        @Override
        public AuthData authenticate(AuthData authData) throws AuthenticationException {
            String token = new String(authData.getBytes(), UTF_8);

            this.jwt = provider.authenticateToken(token);
            this.authenticationDataSource = new AuthenticationDataCommand(token, remoteAddress, sslSession);
            if (jwt.getBody().getExpiration() != null) {
                this.expiration = jwt.getBody().getExpiration().getTime();
            } else {
                // Disable expiration
                this.expiration = Long.MAX_VALUE;
            }

            // There's no additional auth stage required
            return null;
        }

        @Override
        public AuthenticationDataSource getAuthDataSource() {
            return authenticationDataSource;
        }

        @Override
        public boolean isComplete() {
            // The authentication of tokens is always done in one single stage
            return true;
        }

        @Override
        public boolean isExpired() {
            return expiration < System.currentTimeMillis();
        }
    }
    public static final class HttpServletRequestWrapper extends javax.servlet.http.HttpServletRequestWrapper {
        private final HttpServletRequest request;

        public HttpServletRequestWrapper(HttpServletRequest request) {
            super(request);
            this.request = request;
        }

        @Override
        public String getHeader(String name) {
            // The browser javascript WebSocket client couldn't add the auth param to the request header, use the
            // query param `token` to transport the auth token for the browser javascript WebSocket client.
            if (name.equals(HTTP_HEADER_NAME) && request.getHeader(HTTP_HEADER_NAME) == null) {
                String token = request.getParameter(TOKEN);
                if (token != null) {
                    return !token.startsWith(HTTP_HEADER_VALUE_PREFIX) ? HTTP_HEADER_VALUE_PREFIX + token : token;
                }
            }
            return super.getHeader(name);
        }
    }

    private static final class TokenSigningKeyResolver implements SigningKeyResolver {
        private final Jwk jwk;

        public TokenSigningKeyResolver(String data) {
            jwk = new Jwk(data);
        }

        @Override
        public Key resolveSigningKey(JwsHeader header, Claims claims) {
            return jwk.get(header.getKeyId());
        }

        @Override
        public Key resolveSigningKey(JwsHeader header, String plaintext) {
            return jwk.get(header.getKeyId());
        }
    }

    // https://datatracker.ietf.org/doc/html/rfc7517
    @Slf4j
    private static final class Jwk {
        private static final String ALGORITHM_RSA = "RSA";
        private static final String ALGORITHM_EC = "EC";

        private static final Map<String, String> CURVE_MAP = new HashMap<>();

        static {
            // https://openid.net/specs/draft-jones-json-web-key-03.html#anchor7
            // https://docs.oracle.com/en/java/javase/17/docs/specs/security/standard-names.html#parameterspec-names
            CURVE_MAP.put("P-256", "secp256r1");
            CURVE_MAP.put("P-384", "secp384r1");
            CURVE_MAP.put("P-521", "secp521r1");
        }

        private final Map<String, Key> keyMap = new HashMap<>();

        public Jwk(String data) {
            String json;
            try {
                byte[] bytes = AuthTokenUtils.readKeyFromUrl(data);
                if (bytes == null || bytes.length == 0) {
                    throw new IOException("invalid JWKs data");
                }
                json = new String(AuthTokenUtils.readKeyFromUrl(data), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalArgumentException(e);
            }

            if (log.isDebugEnabled()) {
                log.debug("JWKs.json: {}", json);
            }

            JsonNode rootNode;
            try {
                rootNode = new ObjectMapper().readTree(json);
            } catch (IOException e) {
                throw new IllegalArgumentException(e);
            }

            if (rootNode == null) {
                return;
            }

            JsonNode keysNode = rootNode.get("keys");
            if (keysNode == null) {
                return;
            }

            Iterator<JsonNode> elements = keysNode.elements();
            while (elements.hasNext()) {
                JsonNode node = elements.next();
                String type = node.get("kty").textValue();
                String kid = node.get("kid").textValue();
                KeyFactory kf;
                // Reference from:
                // https://github.com/auth0/jwks-rsa-java/blob/0.21.2/src/main/java/com/auth0/jwk/Jwk.java#L176
                switch (type) {
                    case ALGORITHM_RSA:
                        try {
                            kf = KeyFactory.getInstance(ALGORITHM_RSA);

                            BigInteger n = new BigInteger(1, Base64.getUrlDecoder().decode(node.get("n").textValue()));
                            BigInteger e = new BigInteger(1, Base64.getUrlDecoder().decode(node.get("e").textValue()));

                            keyMap.put(kid, kf.generatePublic(new RSAPublicKeySpec(n, e)));
                        } catch (NoSuchAlgorithmException e) {
                            throw new IllegalArgumentException("failed to get KeyFactory by algorithm: " + type, e);
                        } catch (InvalidKeySpecException e) {
                            throw new IllegalArgumentException("failed to generate the RSA public key", e);
                        }
                        break;
                    case ALGORITHM_EC:
                        try {
                            kf = KeyFactory.getInstance(ALGORITHM_EC);

                            BigInteger x = new BigInteger(1, Base64.getUrlDecoder().decode(node.get("x").textValue()));
                            BigInteger y = new BigInteger(1, Base64.getUrlDecoder().decode(node.get("y").textValue()));
                            ECPoint ecPoint = new ECPoint(x, y);

                            AlgorithmParameters algorithmParameters = AlgorithmParameters.getInstance(ALGORITHM_EC);
                            String crv = node.get("crv").textValue();
                            String stdName = CURVE_MAP.get(crv);
                            if (stdName == null) {
                                throw new UnsupportedOperationException("unsupported crv: " + crv);
                            }
                            algorithmParameters.init(new ECGenParameterSpec(stdName));
                            ECParameterSpec parameterSpec = algorithmParameters.getParameterSpec(ECParameterSpec.class);
                            ECPublicKeySpec ecPublicKeySpec = new ECPublicKeySpec(ecPoint, parameterSpec);

                            keyMap.put(kid, kf.generatePublic(ecPublicKeySpec));
                        } catch (NoSuchAlgorithmException e) {
                            throw new IllegalArgumentException("failed to get KeyFactory by algorithm: " + type, e);
                        } catch (InvalidKeySpecException | InvalidParameterSpecException e) {
                            throw new IllegalArgumentException("failed to generate the EC public key", e);
                        }
                        break;
                    default:
                        throw new UnsupportedOperationException("unsupported kty: " + type);
                }
            }
        }

        public Key get(String kid) {
            if (StringUtils.isEmpty(kid)) {
                return null;
            }
            return keyMap.get(kid);
        }
    }
}

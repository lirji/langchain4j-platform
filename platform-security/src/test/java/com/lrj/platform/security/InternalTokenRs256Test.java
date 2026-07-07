package com.lrj.platform.security;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.Base64;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RS256（非对称）内部 JWT 的确定性单测：签发→验签、篡改、过期、跨算法、纯签发/纯验签节点、配置校验。
 * keypair 由 JDK 在测试内临时生成，无外部依赖。
 */
class InternalTokenRs256Test {

    private static final String HS_SECRET = "test-secret-at-least-32-bytes-long-000";

    private static String privateKeyBase64;
    private static String publicKeyBase64;

    @BeforeAll
    static void generateKeypair() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        // 私钥 PKCS#8、公钥 X.509，正是 InternalToken 期望的编码
        privateKeyBase64 = Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded());
        publicKeyBase64 = Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());
    }

    private static InternalToken rs256(String priv, String pub) {
        return InternalToken.forAlgorithm("RS256", null, priv, pub, Duration.ofMinutes(5));
    }

    @Test
    void rs256_mintThenVerify_roundTripsTenant() {
        InternalToken t = rs256(privateKeyBase64, publicKeyBase64);
        TenantContext.Tenant in = new TenantContext.Tenant("acme", "alice", Set.of("approve", "read"));

        TenantContext.Tenant out = t.verify(t.mint(in));

        assertEquals("acme", out.tenantId());
        assertEquals("alice", out.userId());
        assertTrue(out.hasScope("approve"));
        assertTrue(out.hasScope("read"));
    }

    @Test
    void rs256_splitSignerVerifier_roundTrips() {
        // gateway：只持私钥签发；下游：只持公钥验签
        InternalToken signer = rs256(privateKeyBase64, null);
        InternalToken verifier = rs256(null, publicKeyBase64);

        String jwt = signer.mint(new TenantContext.Tenant("acme", "alice", Set.of("read")));
        TenantContext.Tenant out = verifier.verify(jwt);

        assertEquals("acme", out.tenantId());
        assertTrue(out.hasScope("read"));
    }

    @Test
    void rs256_tamperedToken_returnsNull() {
        InternalToken t = rs256(privateKeyBase64, publicKeyBase64);
        String jwt = t.mint(new TenantContext.Tenant("acme", "alice", Set.of()));

        String tampered = jwt.substring(0, jwt.length() - 1) + (jwt.endsWith("A") ? "B" : "A");

        assertNull(t.verify(tampered));
    }

    @Test
    void rs256_wrongPublicKey_returnsNull() throws Exception {
        InternalToken signer = rs256(privateKeyBase64, null);
        String jwt = signer.mint(new TenantContext.Tenant("acme", "alice", Set.of()));

        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        String otherPub = Base64.getEncoder().encodeToString(kpg.generateKeyPair().getPublic().getEncoded());

        assertNull(rs256(null, otherPub).verify(jwt));
    }

    @Test
    void rs256_expiredToken_returnsNull() {
        InternalToken t = InternalToken.forAlgorithm(
                "RS256", null, privateKeyBase64, publicKeyBase64, Duration.ofSeconds(-1));
        String jwt = t.mint(new TenantContext.Tenant("acme", "alice", Set.of()));
        assertNull(t.verify(jwt));
    }

    @Test
    void crossAlgorithm_hs256TokenVerifiedWithRs256PublicKey_returnsNull() {
        InternalToken hs = new InternalToken(HS_SECRET, Duration.ofMinutes(5));
        String hsJwt = hs.mint(new TenantContext.Tenant("acme", "alice", Set.of("read")));

        InternalToken rsVerifier = rs256(null, publicKeyBase64);
        assertNull(rsVerifier.verify(hsJwt));
    }

    @Test
    void crossAlgorithm_rs256TokenVerifiedWithHs256Secret_returnsNull() {
        InternalToken rsSigner = rs256(privateKeyBase64, null);
        String rsJwt = rsSigner.mint(new TenantContext.Tenant("acme", "alice", Set.of("read")));

        InternalToken hs = new InternalToken(HS_SECRET, Duration.ofMinutes(5));
        assertNull(hs.verify(rsJwt));
    }

    @Test
    void rs256_verifyOnlyNode_cannotMint() {
        InternalToken verifier = rs256(null, publicKeyBase64);
        assertThrows(IllegalStateException.class,
                () -> verifier.mint(new TenantContext.Tenant("acme", "alice", Set.of())));
    }

    @Test
    void rs256_pemWrappedKeys_parseAndRoundTrip() {
        String privPem = "-----BEGIN PRIVATE KEY-----\n" + privateKeyBase64 + "\n-----END PRIVATE KEY-----";
        String pubPem = "-----BEGIN PUBLIC KEY-----\n" + publicKeyBase64 + "\n-----END PUBLIC KEY-----";

        InternalToken t = rs256(privPem, pubPem);
        TenantContext.Tenant out = t.verify(t.mint(new TenantContext.Tenant("acme", "alice", Set.of())));

        assertEquals("acme", out.tenantId());
    }

    @Test
    void forAlgorithm_defaultsToHs256WhenBlank() {
        InternalToken t = InternalToken.forAlgorithm(null, HS_SECRET, null, null, Duration.ofMinutes(5));
        TenantContext.Tenant out = t.verify(t.mint(new TenantContext.Tenant("acme", "alice", Set.of())));
        assertEquals("acme", out.tenantId());
    }

    @Test
    void forAlgorithm_rs256WithoutAnyKey_failsFast() {
        assertThrows(IllegalStateException.class,
                () -> InternalToken.forAlgorithm("RS256", null, null, null, Duration.ofMinutes(5)));
    }

    @Test
    void forAlgorithm_unsupportedAlgorithm_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> InternalToken.forAlgorithm("ES256", HS_SECRET, null, null, Duration.ofMinutes(5)));
    }

    @Test
    void forAlgorithm_rs256IsCaseInsensitive() {
        InternalToken t = InternalToken.forAlgorithm("rs256", null, privateKeyBase64, publicKeyBase64,
                Duration.ofMinutes(5));
        TenantContext.Tenant out = t.verify(t.mint(new TenantContext.Tenant("acme", "alice", Set.of())));
        assertEquals("acme", out.tenantId());
    }
}

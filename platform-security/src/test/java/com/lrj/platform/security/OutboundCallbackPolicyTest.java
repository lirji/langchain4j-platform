package com.lrj.platform.security;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OutboundCallbackPolicyTest {

    @Test
    void acceptsOnlyRegisteredPublicOriginWhenRequired() throws Exception {
        InternalSecurityProperties.Callback properties = properties();
        properties.setRequireAllowedOrigin(true);
        properties.setAllowHttp(false);
        properties.setAllowedOrigins(List.of("https://hooks.example.com"));
        OutboundCallbackPolicy policy = new OutboundCallbackPolicy(
                properties, host -> addresses("93.184.216.34"));

        assertDoesNotThrow(() -> policy.requireAllowed("https://hooks.example.com/task?id=1"));
        assertThrows(OutboundCallbackPolicy.UnsafeCallbackException.class,
                () -> policy.requireAllowed("https://other.example.com/task"));
        assertThrows(OutboundCallbackPolicy.UnsafeCallbackException.class,
                () -> policy.requireAllowed("http://hooks.example.com/task"));
    }

    @Test
    void failsFastWhenRequiredAllowlistIsEmpty() {
        InternalSecurityProperties.Callback properties = properties();
        properties.setRequireAllowedOrigin(true);

        assertThrows(OutboundCallbackPolicy.UnsafeCallbackException.class,
                () -> new OutboundCallbackPolicy(properties));
    }

    @Test
    void failsFastForMalformedOrHttpPublicOriginsInHttpsOnlyMode() {
        InternalSecurityProperties.Callback withPath = properties();
        withPath.setAllowedOrigins(List.of("https://hooks.example.com/secret-path"));
        assertThrows(OutboundCallbackPolicy.UnsafeCallbackException.class,
                () -> new OutboundCallbackPolicy(withPath));

        InternalSecurityProperties.Callback withQuery = properties();
        withQuery.setAllowedOrigins(List.of("https://hooks.example.com?tenant=acme"));
        assertThrows(OutboundCallbackPolicy.UnsafeCallbackException.class,
                () -> new OutboundCallbackPolicy(withQuery));

        InternalSecurityProperties.Callback http = properties();
        http.setAllowHttp(false);
        http.setAllowedOrigins(List.of("http://hooks.example.com"));
        assertThrows(OutboundCallbackPolicy.UnsafeCallbackException.class,
                () -> new OutboundCallbackPolicy(http));
    }

    @Test
    void rejectsPrivateReservedIpv4AndIpv6Targets() {
        List<String> blocked = List.of(
                "127.0.0.1",
                "10.0.0.1",
                "100.64.0.1",
                "169.254.169.254",
                "172.16.0.1",
                "192.168.0.1",
                "198.18.0.1",
                "192.0.2.1",
                "198.51.100.1",
                "203.0.113.1",
                "::1",
                "fc00::1",
                "fe80::1",
                "2001:db8::1");
        OutboundCallbackPolicy policy = new OutboundCallbackPolicy(
                properties(), host -> addresses(host));

        for (String address : blocked) {
            String url = address.contains(":")
                    ? "https://[" + address + "]/hook"
                    : "https://" + address + "/hook";
            assertThrows(OutboundCallbackPolicy.UnsafeCallbackException.class,
                    () -> policy.requireAllowed(url), address);
        }
    }

    @Test
    void rejectsDnsAnswerWhenAnyAddressIsPrivate() throws Exception {
        OutboundCallbackPolicy policy = new OutboundCallbackPolicy(
                properties(),
                host -> addresses("93.184.216.34", "10.10.10.10"));

        assertThrows(OutboundCallbackPolicy.UnsafeCallbackException.class,
                () -> policy.requireAllowed("https://rebind.example.com/hook"));
    }

    @Test
    void acceptsGloballyRoutableIpv6Literal() {
        OutboundCallbackPolicy policy = new OutboundCallbackPolicy(
                properties(), host -> addresses(host));

        assertDoesNotThrow(() -> policy.requireAllowed(
                "https://[2606:4700:4700::1111]/hook"));
    }

    @Test
    void exactTrustedInternalOriginMayUsePrivateDnsButNothingElseMay() throws Exception {
        InternalSecurityProperties.Callback properties = properties();
        properties.setRequireAllowedOrigin(true);
        properties.setAllowHttp(false);
        properties.setTrustedInternalUrls(List.of(
                "http://interop-service:8088/interop/a2a/push-callback"));
        OutboundCallbackPolicy policy = new OutboundCallbackPolicy(
                properties, host -> addresses("10.0.0.8"));

        assertDoesNotThrow(() -> policy.requireAllowed(
                "http://interop-service:8088/interop/a2a/push-callback"));
        assertThrows(OutboundCallbackPolicy.UnsafeCallbackException.class,
                () -> policy.requireAllowed("http://interop-service:8088/admin"));
    }

    @Test
    void rejectsCredentialsFragmentsUnknownDnsAndUnregisteredPorts() throws Exception {
        OutboundCallbackPolicy publicPolicy = new OutboundCallbackPolicy(
                properties(), host -> addresses("93.184.216.34"));
        OutboundCallbackPolicy unresolved = new OutboundCallbackPolicy(
                properties(),
                host -> {
                    throw new java.net.UnknownHostException(host);
                });

        assertThrows(OutboundCallbackPolicy.UnsafeCallbackException.class,
                () -> publicPolicy.requireAllowed("https://user:pass@hooks.example.com/hook"));
        assertThrows(OutboundCallbackPolicy.UnsafeCallbackException.class,
                () -> publicPolicy.requireAllowed("https://hooks.example.com/hook#secret"));
        assertThrows(OutboundCallbackPolicy.UnsafeCallbackException.class,
                () -> publicPolicy.requireAllowed("https://hooks.example.com:8443/hook"));
        assertThrows(OutboundCallbackPolicy.UnsafeCallbackException.class,
                () -> unresolved.requireAllowed("https://missing.example.com/hook"));
    }

    private static InternalSecurityProperties.Callback properties() {
        return new InternalSecurityProperties.Callback();
    }

    private static InetAddress[] addresses(String... values) throws java.net.UnknownHostException {
        InetAddress[] result = new InetAddress[values.length];
        for (int index = 0; index < values.length; index++) {
            result[index] = InetAddress.getByName(values[index]);
        }
        return result;
    }
}

package com.lrj.platform.security;

import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Validates user-controlled webhook targets before registration and again before every delivery. */
public final class OutboundCallbackPolicy {

    @FunctionalInterface
    public interface AddressResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final boolean requireAllowedOrigin;
    private final boolean allowHttp;
    private final int maxUrlLength;
    private final Set<Origin> allowedOrigins;
    private final Set<Target> trustedInternalUrls;
    private final AddressResolver resolver;

    public OutboundCallbackPolicy(InternalSecurityProperties.Callback properties) {
        this(properties, InetAddress::getAllByName);
    }

    public OutboundCallbackPolicy(
            InternalSecurityProperties.Callback properties,
            AddressResolver resolver) {
        this.requireAllowedOrigin = properties.isRequireAllowedOrigin();
        this.allowHttp = properties.isAllowHttp();
        this.maxUrlLength = Math.max(256, Math.min(8192, properties.getMaxUrlLength()));
        this.allowedOrigins = parseOrigins(
                properties.getAllowedOrigins(), "allowed origin", this.allowHttp);
        this.trustedInternalUrls = parseTargets(
                properties.getTrustedInternalUrls(), "trusted internal URL");
        this.resolver = resolver;
        if (requireAllowedOrigin && allowedOrigins.isEmpty() && trustedInternalUrls.isEmpty()) {
            throw new UnsafeCallbackException(
                    "callback allowlist must not be empty when registration is required");
        }
    }

    public URI requireAllowed(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > maxUrlLength) {
            throw new UnsafeCallbackException("callback URL is missing or too long");
        }
        final URI uri;
        try {
            uri = URI.create(raw.trim()).normalize();
        } catch (IllegalArgumentException exception) {
            throw new UnsafeCallbackException("callback URL is invalid", exception);
        }
        Origin origin = Origin.from(uri, "callback URL");
        if (uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new UnsafeCallbackException("callback URL must not contain userinfo or fragment");
        }
        boolean trustedInternal = trustedInternalUrls.contains(Target.from(uri, "callback URL"));
        boolean registeredPublic = allowedOrigins.contains(origin);
        if (requireAllowedOrigin && !trustedInternal && !registeredPublic) {
            throw new UnsafeCallbackException("callback origin is not registered");
        }
        if (!trustedInternal && !"https".equals(origin.scheme()) && !allowHttp) {
            throw new UnsafeCallbackException("external callback URL must use HTTPS");
        }
        if (!trustedInternal && !registeredPublic && origin.port() != 80 && origin.port() != 443) {
            throw new UnsafeCallbackException("unregistered callback URL uses a disallowed port");
        }
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(origin.host());
        } catch (UnknownHostException exception) {
            throw new UnsafeCallbackException("callback host cannot be resolved", exception);
        }
        if (addresses == null || addresses.length == 0) {
            throw new UnsafeCallbackException("callback host has no addresses");
        }
        if (!trustedInternal && Arrays.stream(addresses).anyMatch(address -> !isPublic(address))) {
            throw new UnsafeCallbackException("callback host resolves to a non-public address");
        }
        return uri;
    }

    private static Set<Origin> parseOrigins(
            List<String> values, String label, boolean allowHttp) {
        if (values == null) return Set.of();
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> parseOrigin(value, label, allowHttp))
                .collect(Collectors.toUnmodifiableSet());
    }

    private static Origin parseOrigin(String value, String label, boolean allowHttp) {
        URI uri = parseConfiguredUri(value, label).normalize();
        Origin origin = Origin.from(uri, label);
        String path = uri.getRawPath();
        if (uri.getUserInfo() != null
                || uri.getFragment() != null
                || uri.getRawQuery() != null
                || (path != null && !path.isEmpty() && !"/".equals(path))) {
            throw new UnsafeCallbackException(
                    label + " must contain only scheme, host and optional port");
        }
        if (!allowHttp && !"https".equals(origin.scheme())) {
            throw new UnsafeCallbackException(label + " must use HTTPS");
        }
        return origin;
    }

    private static Set<Target> parseTargets(List<String> values, String label) {
        if (values == null) return Set.of();
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> parseTarget(value, label))
                .collect(Collectors.toUnmodifiableSet());
    }

    private static Target parseTarget(String value, String label) {
        URI uri = parseConfiguredUri(value, label).normalize();
        if (uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new UnsafeCallbackException(label + " must not contain userinfo or fragment");
        }
        return Target.from(uri, label);
    }

    private static URI parseConfiguredUri(String value, String label) {
        try {
            return URI.create(value.trim());
        } catch (IllegalArgumentException exception) {
            throw new UnsafeCallbackException(label + " is invalid", exception);
        }
    }

    static boolean isPublic(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) return isPublicIpv4(bytes);
        if (bytes.length != 16) return false;
        if (isIpv4Mapped(bytes)) {
            return isPublicIpv4(Arrays.copyOfRange(bytes, 12, 16));
        }
        int first = unsigned(bytes[0]);
        int second = unsigned(bytes[1]);
        // Only global unicast 2000::/3; reject ULA/link-local/multicast/unspecified/NAT64.
        if ((first & 0xE0) != 0x20) return false;
        // Documentation, Teredo and 6to4 transition ranges are not safe callback targets.
        if (first == 0x20 && second == 0x01) {
            int third = unsigned(bytes[2]);
            int fourth = unsigned(bytes[3]);
            if ((third == 0x0d && fourth == 0xb8) // 2001:db8::/32
                    || (third == 0 && fourth == 0) // 2001:0000::/32 Teredo
                    || (third == 0 && fourth == 2)) { // 2001:0002::/48 benchmark
                return false;
            }
        }
        return !(first == 0x20 && second == 0x02); // 2002::/16 6to4
    }

    private static boolean isPublicIpv4(byte[] bytes) {
        int a = unsigned(bytes[0]);
        int b = unsigned(bytes[1]);
        int c = unsigned(bytes[2]);
        if (a == 0 || a == 10 || a == 127 || a >= 224) return false;
        if (a == 100 && b >= 64 && b <= 127) return false;
        if (a == 169 && b == 254) return false;
        if (a == 172 && b >= 16 && b <= 31) return false;
        if (a == 192 && b == 168) return false;
        if (a == 192 && b == 0 && c == 0) return false;
        if (a == 192 && b == 0 && c == 2) return false;
        if (a == 192 && b == 88 && c == 99) return false;
        if (a == 198 && (b == 18 || b == 19)) return false;
        if (a == 198 && b == 51 && c == 100) return false;
        return !(a == 203 && b == 0 && c == 113);
    }

    private static boolean isIpv4Mapped(byte[] bytes) {
        for (int index = 0; index < 10; index++) {
            if (bytes[index] != 0) return false;
        }
        return bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff;
    }

    private static int unsigned(byte value) {
        return value & 0xff;
    }

    private record Origin(String scheme, String host, int port) {
        static Origin from(URI uri, String label) {
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null || !uri.isAbsolute()) {
                throw new UnsafeCallbackException(label + " must be an absolute HTTP(S) origin");
            }
            String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
            if (!List.of("http", "https").contains(normalizedScheme)) {
                throw new UnsafeCallbackException(label + " must use HTTP(S)");
            }
            String normalizedHost;
            try {
                normalizedHost = host.contains(":")
                        ? host.toLowerCase(Locale.ROOT)
                        : IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
            } catch (IllegalArgumentException exception) {
                throw new UnsafeCallbackException(label + " contains an invalid host", exception);
            }
            int port = uri.getPort();
            if (port < 0) port = "https".equals(normalizedScheme) ? 443 : 80;
            if (port < 1 || port > 65535) {
                throw new UnsafeCallbackException(label + " contains an invalid port");
            }
            return new Origin(normalizedScheme, normalizedHost, port);
        }
    }

    private record Target(Origin origin, String path, String query) {
        static Target from(URI uri, String label) {
            Origin origin = Origin.from(uri, label);
            String path = uri.getRawPath();
            return new Target(origin, path == null || path.isEmpty() ? "/" : path, uri.getRawQuery());
        }
    }

    public static final class UnsafeCallbackException extends IllegalArgumentException {
        public UnsafeCallbackException(String message) {
            super(message);
        }

        public UnsafeCallbackException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

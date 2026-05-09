package io.github.huskyagent.infra.web;

import lombok.extern.slf4j.Slf4j;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Set;
import java.util.regex.Pattern;

@Slf4j
public class UrlSafety {

    /** Block cloud metadata endpoints even when they resolve through a public hostname. */
    private static final Set<String> BLOCKED_HOSTNAMES = Set.of(
        "metadata.google.internal",
        "metadata.goog"
    );

    private static final Pattern SECRET_PATTERN = Pattern.compile(
        "(sk-[a-zA-Z0-9]{20,}|api_key|apikey|secret|token|password|credential)=[^&\\s]+",
        Pattern.CASE_INSENSITIVE
    );

    public static boolean isSafeUrl(String url) {
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            if (host == null) return false;

            String lowerHost = host.toLowerCase();
            for (String blocked : BLOCKED_HOSTNAMES) {
                if (lowerHost.equals(blocked) || lowerHost.endsWith("." + blocked)) {
                    log.warn("Blocked metadata hostname: {}", host);
                    return false;
                }
            }

            InetAddress[] addresses = resolveHost(host);
            for (InetAddress addr : addresses) {
                if (isPrivateIp(addr)) {
                    log.warn("Blocked private/internal IP: {} -> {}", host, addr.getHostAddress());
                    return false;
                }
            }
            return true;
        } catch (URISyntaxException e) {
            log.warn("Invalid URL: {}", url);
            return false;
        } catch (UnknownHostException e) {
            log.warn("DNS resolution failed for {}, blocking request (fail-closed)", url);
            return false;
        }
    }

    static InetAddress[] resolveHost(String host) throws UnknownHostException {
        return InetAddress.getAllByName(host);
    }

    /** Flags obvious credential-like query params before the request is sent anywhere. */
    public static boolean containsSecret(String url) {
        return SECRET_PATTERN.matcher(url).find();
    }

    private static boolean isPrivateIp(InetAddress addr) {
        if (addr.isLoopbackAddress()) return true;        // 127.x.x.x
        if (addr.isSiteLocalAddress()) return true;       // 10.x, 172.16-31.x, 192.168.x
        if (addr.isLinkLocalAddress()) return true;       // 169.254.x.x
        if (addr.isAnyLocalAddress()) return true;        // 0.0.0.0
        if (isCgnatAddress(addr)) return true;            // 100.64.0.0/10

        return false;
    }

    private static boolean isCgnatAddress(InetAddress addr) {
        if (addr.getAddress().length != 4) return false;
        byte[] bytes = addr.getAddress();
        int firstOctet = bytes[0] & 0xFF;
        int secondOctet = bytes[1] & 0xFF;
        return firstOctet == 100 && (secondOctet & 0xC0) == 0x40;
    }
}
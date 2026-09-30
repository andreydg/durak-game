package com.example.durakgame.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adds browser security headers to every response. The Content-Security-Policy allows scripts only
 * from this origin; the one kind of inline script the pages may carry — an import map — is
 * allowed by the hash of its exact contents, computed from the packaged HTML at startup. Inline
 * style attributes stay allowed because the UI positions a few elements with them.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class SecurityHeadersFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(SecurityHeadersFilter.class);
    private static final Pattern IMPORT_MAP = Pattern.compile(
            "<script\\s+type=\"importmap\"\\s*>(.*?)</script>", Pattern.DOTALL);
    /* Host header values that are safe to echo into the policy (host name or IP, optional port). */
    private static final Pattern SAFE_HOST = Pattern.compile("[A-Za-z0-9.\\-]+(:\\d{1,5})?|\\[[0-9A-Fa-f:.]+](:\\d{1,5})?");

    private final String scriptSources;

    public SecurityHeadersFilter() {
        this(inlineImportMapHashes("classpath:static/*.html"));
    }

    SecurityHeadersFilter(Set<String> importMapHashes) {
        StringBuilder sources = new StringBuilder("'self'");
        importMapHashes.forEach(hash -> sources.append(" '").append(hash).append('\''));
        this.scriptSources = sources.toString();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Content-Security-Policy", contentSecurityPolicy(request.getHeader("Host")));
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=()");
        if (request.isSecure()) {
            response.setHeader("Strict-Transport-Security", "max-age=31536000");
        }
        chain.doFilter(request, response);
    }

    String contentSecurityPolicy(String host) {
        // 'self' covers same-host websockets in current browsers; name them explicitly for older ones.
        String sockets = host != null && SAFE_HOST.matcher(host).matches()
                ? " ws://" + host + " wss://" + host
                : "";
        return "default-src 'self'; "
                + "script-src " + scriptSources + "; "
                + "style-src 'self' 'unsafe-inline'; "
                + "img-src 'self' data:; "
                + "font-src 'self'; "
                + "connect-src 'self'" + sockets + "; "
                + "object-src 'none'; "
                + "base-uri 'self'; "
                + "form-action 'self'; "
                + "frame-ancestors 'none'";
    }

    static Set<String> inlineImportMapHashes(String locationPattern) {
        Set<String> hashes = new LinkedHashSet<>();
        try {
            for (Resource page : new PathMatchingResourcePatternResolver().getResources(locationPattern)) {
                try (InputStream in = page.getInputStream()) {
                    hashes.addAll(importMapHashes(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
                }
            }
        } catch (IOException ex) {
            log.warn("csp_import_map_scan_failed message={}", ex.getMessage());
        }
        return hashes;
    }

    static Set<String> importMapHashes(String html) {
        Set<String> hashes = new LinkedHashSet<>();
        Matcher matcher = IMPORT_MAP.matcher(html);
        while (matcher.find()) {
            hashes.add("sha256-" + Base64.getEncoder().encodeToString(sha256(matcher.group(1))));
        }
        return hashes;
    }

    private static byte[] sha256(String content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}

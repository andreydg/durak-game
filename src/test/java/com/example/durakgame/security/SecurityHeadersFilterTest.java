package com.example.durakgame.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityHeadersFilterTest {

    @Test
    void everyResponseCarriesTheSecurityHeaders() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
        request.addHeader("Host", "durak.example:8443");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new SecurityHeadersFilter(Set.of()).doFilter(request, response, (req, res) -> { });

        String csp = response.getHeader("Content-Security-Policy");
        assertTrue(csp.contains("script-src 'self';"), csp);
        assertTrue(csp.contains("frame-ancestors 'none'"), csp);
        assertTrue(csp.contains("object-src 'none'"), csp);
        assertTrue(csp.contains("connect-src 'self' ws://durak.example:8443 wss://durak.example:8443;"), csp);
        assertEquals("nosniff", response.getHeader("X-Content-Type-Options"));
        assertEquals("DENY", response.getHeader("X-Frame-Options"));
        assertEquals("strict-origin-when-cross-origin", response.getHeader("Referrer-Policy"));
        assertNull(response.getHeader("Strict-Transport-Security"), "HSTS only over HTTPS");
    }

    @Test
    void httpsResponsesAlsoSendHsts() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
        request.setSecure(true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        new SecurityHeadersFilter(Set.of()).doFilter(request, response, (req, res) -> { });

        assertEquals("max-age=31536000", response.getHeader("Strict-Transport-Security"));
    }

    @Test
    void aHostileHostHeaderCannotInjectPolicyDirectives() {
        String csp = new SecurityHeadersFilter(Set.of()).contentSecurityPolicy("evil.example; script-src *");

        assertFalse(csp.contains("evil"), csp);
        assertTrue(csp.contains("connect-src 'self';"), csp);
    }

    @Test
    void inlineImportMapsAreAllowedByTheHashOfTheirExactContents() {
        String html = "<head><script type=\"importmap\">{\"imports\":{\"/js/a.js\":\"/js/a.js?v=1\"}}</script>"
                + "<script type=\"application/ld+json\">{\"@type\":\"Game\"}</script></head>";

        Set<String> hashes = SecurityHeadersFilter.importMapHashes(html);

        // Reference value from: printf '%s' '<map json>' | openssl dgst -sha256 -binary | base64
        assertEquals(Set.of("sha256-s/Kvz7Eqr3ECz6Gi3OIihruoQQu+Eh6U3rLDW5SyUeE="), hashes);
        String csp = new SecurityHeadersFilter(hashes).contentSecurityPolicy("localhost:8080");
        assertTrue(csp.contains("script-src 'self' 'sha256-s/Kvz7Eqr3ECz6Gi3OIihruoQQu+Eh6U3rLDW5SyUeE=';"), csp);
    }

    @Test
    void packagedPagesScanWithoutErrors() {
        // The shipped pages currently have no inline import map; whatever they carry must hash cleanly.
        Set<String> hashes = SecurityHeadersFilter.inlineImportMapHashes("classpath:static/*.html");
        hashes.forEach(hash -> assertTrue(hash.startsWith("sha256-"), hash));
    }
}

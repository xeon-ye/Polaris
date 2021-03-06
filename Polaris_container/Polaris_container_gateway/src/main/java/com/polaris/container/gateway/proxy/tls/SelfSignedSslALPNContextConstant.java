package com.polaris.container.gateway.proxy.tls;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class SelfSignedSslALPNContextConstant {
    /**
     * The following list is derived from <a
     * href="http://docs.oracle.com/javase/8/docs/technotes/guides/security/SunProviders.html">SunJSSE Supported
     * Ciphers</a> and <a
     * href="https://wiki.mozilla.org/Security/Server_Side_TLS#Modern_compatibility">Mozilla Modern Cipher
     * Suites</a> in accordance with the <a
     * href="https://tools.ietf.org/html/rfc7540#section-9.2.2">HTTP/2 Specification</a>.
     *
     * According to the <a href="http://docs.oracle.com/javase/8/docs/technotes/guides/security/StandardNames.html">
     * JSSE documentation</a> "the names mentioned in the TLS RFCs prefixed with TLS_ are functionally equivalent
     * to the JSSE cipher suites prefixed with SSL_".
     * Both variants are used to support JVMs supporting the one or the other.
     */
    public static final List<String> CIPHERS;

    /**
     * <a href="https://wiki.mozilla.org/Security/Server_Side_TLS#Intermediate_compatibility_.28recommended.29"
     * >Mozilla Modern Cipher Suites Intermediate compatibility</a> minus the following cipher suites that are black
     * listed by the <a href="https://tools.ietf.org/html/rfc7540#appendix-A">HTTP/2 RFC</a>.
     */
    private static final List<String> CIPHERS_JAVA_MOZILLA_MODERN_SECURITY = Collections.unmodifiableList(Arrays
            .asList(
            /* openssl = ECDHE-ECDSA-AES128-GCM-SHA256 */
            "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",

            /* REQUIRED BY HTTP/2 SPEC */
            /* openssl = ECDHE-RSA-AES128-GCM-SHA256 */
            "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
            /* REQUIRED BY HTTP/2 SPEC */

            /* openssl = ECDHE-ECDSA-AES256-GCM-SHA384 */
            "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
            /* openssl = ECDHE-RSA-AES256-GCM-SHA384 */
            "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
            /* openssl = ECDHE-ECDSA-CHACHA20-POLY1305 */
            "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
            /* openssl = ECDHE-RSA-CHACHA20-POLY1305 */
            "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256",

            /* TLS 1.3 ciphers */
            "TLS_AES_128_GCM_SHA256",
            "TLS_AES_256_GCM_SHA384",
            "TLS_CHACHA20_POLY1305_SHA256"
            ));

    static {
        CIPHERS = Collections.unmodifiableList(new ArrayList<String>(CIPHERS_JAVA_MOZILLA_MODERN_SECURITY));
    }

    private SelfSignedSslALPNContextConstant() { }
}

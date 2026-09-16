package io.github.mangi.eta.agent.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowserProxyRulesTest {
    @Test
    fun `host and port are normalized to an explicit scheme`() {
        assertEquals("http://127.0.0.1:8080", BrowserProxyRules.normalize("127.0.0.1:8080"))
        assertEquals("http://127.0.0.1:8080", BrowserProxyRules.normalize("  127.0.0.1:8080  "))
        assertEquals("http://proxy.local", BrowserProxyRules.normalize("proxy.local"))
        assertEquals("https://proxy.local:443", BrowserProxyRules.normalize("HTTPS://proxy.local:443"))
        assertEquals("socks://10.0.2.2:1080", BrowserProxyRules.normalize("socks5://10.0.2.2:1080"))
        assertEquals("socks://[fe80::1]:1080", BrowserProxyRules.normalize("socks://[fe80::1]:1080"))
    }

    @Test
    fun `paths, unknown schemes and malformed ports are rejected`() {
        assertNull(BrowserProxyRules.normalize(""))
        assertNull(BrowserProxyRules.normalize("http://127.0.0.1:8080/proxy"))
        assertNull(BrowserProxyRules.normalize("ftp://127.0.0.1:21"))
        assertNull(BrowserProxyRules.normalize("user:pass@127.0.0.1:8080"))
        assertNull(BrowserProxyRules.normalize("127.0.0.1:0"))
        assertNull(BrowserProxyRules.normalize("127.0.0.1:99999"))
        assertNull(BrowserProxyRules.normalize("127.0.0.1:"))
        assertNull(BrowserProxyRules.normalize("127.0.0.1:80 80"))
        assertNull(BrowserProxyRules.normalize("fe80::1:1080"))
        assertEquals("http://[fe80::1]", BrowserProxyRules.normalize("[fe80::1]"))
    }
}

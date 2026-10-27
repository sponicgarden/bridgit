package net.finleg.bridget.api

import net.finleg.bridget.capability.SpeakerRules
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * Ways the speaker's URL rules (SPK-02) can fail: http or other schemes accepted; credentials in the URL;
 * odd ports to reach internal services; private, loopback, link-local, Tailscale (CGNAT) or IPv6 ULA
 * addresses treated as public, letting an agent make the phone probe the home network or tailnet.
 */
class SpeakerRulesTest {
    @Test fun urls() {
        assertTrue(SpeakerRules.urlAllowed("https://cdn.example.com/a.mp3"))
        for (no in listOf("http://cdn.example.com/a.mp3", "https://u:p@cdn.example.com/a.mp3", "https://cdn.example.com:8443/a.mp3",
            "file:///sdcard/a.mp3", "content://x/y", "ftp://a.com/a.mp3", "https:///a.mp3", "not a url"))
            assertFalse(no, SpeakerRules.urlAllowed(no))
    }

    @Test fun addresses() {
        fun ip(s: String) = InetAddress.getByName(s)
        assertTrue(SpeakerRules.publicAddress(ip("93.184.216.34")))
        assertTrue(SpeakerRules.publicAddress(ip("2606:2800:220:1:248:1893:25c8:1946")))
        for (no in listOf("127.0.0.1", "10.0.0.5", "192.168.1.32", "172.16.0.1", "169.254.1.1", "100.94.27.79", "100.64.0.1",
            "0.0.0.0", "::1", "fd7a:115c:a1e0::1", "fe80::1", "224.0.0.1"))
            assertFalse(no, SpeakerRules.publicAddress(ip(no)))
    }
}

package com.auramusic.wiki.classic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Cookie 输入规整：覆盖「明明输入了却提示请输入 Cookie」的常见粘贴形态。 */
public class SessionManagerCookieTest {

    @Test
    public void acceptsBareMusicUToken() {
        assertEquals("MUSIC_U=0123456789abcdef", SessionManager.sanitizeCookie("0123456789abcdef"));
        assertNull(SessionManager.validateReason("0123456789abcdef"));
    }

    @Test
    public void decodesUrlEncodedCookie() {
        assertEquals("MUSIC_U=abc12345; __csrf=xyz",
                SessionManager.sanitizeCookie("MUSIC_U%3Dabc12345%3B%20__csrf%3Dxyz"));
    }

    @Test
    public void parsesJsonCookie() {
        // JSONObject 的 key 顺序不保证，因此只校验两个键都被保留
        String cookie = SessionManager.sanitizeCookie(
                "{\"MUSIC_U\":\"abc12345\",\"__csrf\":\"xyz\"}");
        assertTrue(cookie.contains("MUSIC_U=abc12345"));
        assertTrue(cookie.contains("__csrf=xyz"));
        assertNull(SessionManager.validateReason(cookie));
    }

    @Test
    public void dropsSetCookieAttributes() {
        assertEquals("MUSIC_U=abc12345; __csrf=xyz",
                SessionManager.sanitizeCookie("MUSIC_U=abc12345; Path=/; Domain=music.163.com; "
                        + "Max-Age=315360000; Expires=Thu, 16 Oct 2036 08:13:02 GMT; "
                        + "__csrf=xyz"));
    }

    @Test
    public void canonicalizesHeaderWithPrefixAndQuotes() {
        assertEquals("MUSIC_U=abc12345; __csrf=xyz",
                SessionManager.sanitizeCookie("Cookie: \"MUSIC_U=abc12345; __csrf=xyz\""));
    }

    @Test
    public void supportsChineseSemicolonAndLineBreaks() {
        assertEquals("MUSIC_U=abc12345; __csrf=xyz",
                SessionManager.sanitizeCookie("MUSIC_U=abc12345；\n__csrf=xyz"));
    }
}

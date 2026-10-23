package com.auramusic.wiki.classic;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Base64;

/** MD5 工具：上传 NOS 需要同时提供十六进制摘要与 Content-MD5（Base64）。 */
final class Md5 {

    private Md5() {
    }

    static String hex(File file) throws IOException {
        MessageDigest digest = newDigest();
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[65536];
            int len;
            while ((len = in.read(buf)) != -1) {
                digest.update(buf, 0, len);
            }
        }
        return toHex(digest.digest());
    }

    static String base64(byte[] data) {
        return Base64.getEncoder().encodeToString(newDigest().digest(data));
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (Exception e) {
            throw new IllegalStateException("MD5 不可用", e);
        }
    }

    private static String toHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        final char[] digits = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = digits[v >>> 4];
            out[i * 2 + 1] = digits[v & 0x0F];
        }
        return new String(out);
    }
}

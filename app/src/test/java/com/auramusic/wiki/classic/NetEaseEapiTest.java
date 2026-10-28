package com.auramusic.wiki.classic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * EAPI 报文格式契约。
 *
 * <p>{@code params} 必须是 AES/ECB 密文的<b>大写十六进制</b>：一旦退回 Base64，
 * 服务端会把请求判为非法，所有 App 通道调用都会静默降级到 WEAPI——
 * 而网页通道的字段映射不完整（例如专辑介绍），表现为「提交成功但审核后简介为空」。
 */
public class NetEaseEapiTest {

    private static final String KEY = "e82ckenh8dichen8";
    private static final String SEP = "-36cd479b6b5-";

    @Test
    public void encryptsParamsAsUpperCaseHexOfExpectedValue() throws Exception {
        String path = "/api/rep/ugc/add/artist/album";
        JSONObject payload = new JSONObject()
                .put("albumVo", new JSONObject().put("albumName", "A").put("production", "介绍").toString())
                .put("artistId", 1L);

        String params = NetEaseEapi.encrypt(path, payload, "0123456789abcdef", "MUSIC_U=u");

        assertTrue("params 必须是十六进制而不是 Base64：" + params, params.matches("[0-9A-F]+"));
        assertEquals(0, params.length() % 2);

        String plain = new String(decrypt(params), StandardCharsets.UTF_8);
        assertTrue("明文：" + plain, plain.startsWith(path + SEP));
        assertTrue("明文：" + plain, plain.contains("\"albumVo\""));
        // albumVo 在 EAPI 里是「JSON 字符串」，所以简介字段会被二次转义
        assertTrue("明文：" + plain, plain.contains("production"));
        assertTrue("明文：" + plain, plain.contains("\"header\""));
        // 尾部是同一段明文与摘要，长度必然一致
        String[] parts = plain.split(SEP, -1);
        assertEquals(3, parts.length);
        assertEquals(path, parts[0]);
        assertTrue(parts[1].length() > 0);
        assertEquals(32, parts[2].length());
    }

    private static byte[] decrypt(String hex) throws Exception {
        byte[] raw = new byte[hex.length() / 2];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE,
                new SecretKeySpec(KEY.getBytes(StandardCharsets.UTF_8), "AES"));
        return cipher.doFinal(raw);
    }
}

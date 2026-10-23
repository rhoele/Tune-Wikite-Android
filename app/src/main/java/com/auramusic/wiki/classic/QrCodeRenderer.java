package com.auramusic.wiki.classic;

import android.graphics.Bitmap;
import android.graphics.Color;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.HashMap;
import java.util.Map;

/**
 * 二维码位图渲染：把登录链接编码成可扫描的 QR 图。
 *
 * 使用固定尺寸 + 内部矩阵逐像素映射，保证任何屏幕密度下边缘锐利无模糊。
 */
public final class QrCodeRenderer {

    private QrCodeRenderer() {
    }

    /**
     * @param content 待编码内容
     * @param sizePx  目标边长（像素），建议取 dp 换算后的值
     * @return 位图；内容为空或编码失败时返回 null（调用方降级为提示文案）
     */
    public static Bitmap render(String content, int sizePx) {
        if (content == null || content.isEmpty() || sizePx <= 0) {
            return null;
        }
        Map<EncodeHintType, Object> hints = new HashMap<>();
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        // 留出足够的静默区，兼容对边距较敏感的扫码器
        hints.put(EncodeHintType.MARGIN, 3);
        try {
            BitMatrix matrix = new QRCodeWriter()
                    .encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints);
            int w = matrix.getWidth();
            int h = matrix.getHeight();
            if (w <= 0 || h <= 0) {
                return null;
            }
            int[] pixels = new int[w * h];
            for (int y = 0; y < h; y++) {
                int offset = y * w;
                for (int x = 0; x < w; x++) {
                    pixels[offset + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
                }
            }
            Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bitmap.setPixels(pixels, 0, w, 0, 0, w, h);
            return bitmap;
        } catch (Exception e) {
            return null;
        }
    }
}

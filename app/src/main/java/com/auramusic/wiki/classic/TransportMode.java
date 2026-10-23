package com.auramusic.wiki.classic;

import androidx.annotation.StringRes;

/**
 * 接口通道选择（对齐原 APK {@code Lt1/d} 枚举）。
 *
 * <p>原 APK 的说明文案：自动先用 App 的 eapi，失败时改用网页版 weapi。
 */
public enum TransportMode {

    /** 自动：先用 eapi，失败时降级 weapi */
    AUTO(R.string.contrib_transport_auto),
    /** 强制 App 通道（eapi） */
    EAPI(R.string.contrib_transport_eapi),
    /** 强制网页通道（weapi） */
    WEAPI(R.string.contrib_transport_weapi);

    @StringRes
    private final int labelRes;

    TransportMode(@StringRes int labelRes) {
        this.labelRes = labelRes;
    }

    @StringRes
    public int labelRes() {
        return labelRes;
    }

    /** 由持久化的枚举名解析，缺失或非法值回落到 {@link #AUTO}（对齐原 APK 的兜底行为）。 */
    public static TransportMode fromName(String name) {
        if (name != null) {
            for (TransportMode mode : values()) {
                if (mode.name().equals(name)) {
                    return mode;
                }
            }
        }
        return AUTO;
    }
}

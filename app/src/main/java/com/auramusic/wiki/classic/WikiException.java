package com.auramusic.wiki.classic;

/**
 * 统一网络/解析异常：把「超时、连不上、HTTP 错误码、响应过大、JSON 非法、业务空数据、被取消」
 * 等场景显式区分为不同类别，供界面层渲染差异化错误提示（对应基准 F11/F18）。
 */
public class WikiException extends Exception {

    /** 错误类别 */
    public enum Kind {
        /** 连接或读取超时 */
        TIMEOUT,
        /** 无法建立连接（无网络 / DNS / 服务不可达） */
        NO_NETWORK,
        /** 服务端返回非 2xx 状态码 */
        HTTP,
        /** 响应体超过上限 */
        TOO_LARGE,
        /** 响应体不是合法 JSON 或结构不符 */
        BAD_JSON,
        /** 请求被主动取消 */
        CANCELLED,
        /** 业务层明确返回空数据 */
        EMPTY,
        /** 缺少必需的访问凭据（如 Cookie） */
        NO_CREDENTIAL,
        /** 其他未知错误 */
        UNKNOWN
    }

    private final Kind kind;
    private final int httpCode;

    public WikiException(Kind kind, String message) {
        this(kind, message, 0, null);
    }

    public WikiException(Kind kind, String message, Throwable cause) {
        this(kind, message, 0, cause);
    }

    public WikiException(Kind kind, String message, int httpCode, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.httpCode = httpCode;
    }

    public Kind getKind() {
        return kind;
    }

    public int getHttpCode() {
        return httpCode;
    }
}

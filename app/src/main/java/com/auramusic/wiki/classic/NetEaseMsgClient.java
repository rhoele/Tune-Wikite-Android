package com.auramusic.wiki.classic;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 曲库审核私信读取。
 *
 * <p>审核未通过的具体原因只出现在「曲库小学妹」发来的私信里，贡献列表接口本身
 * 只给出 {@code rejectNames}。这里按网页版私信详情页用的同一个端点
 * （{@code /api/msg/private/history}）拉取对话，供 {@link RejectArchive} 抽取并归档。
 *
 * <p>走 WEAPI：该端点本身是网页端接口，eapi 通道对私信类端点并不稳定。
 */
final class NetEaseMsgClient {

    private static final String TAG = "NetEaseMsgClient";

    /** 曲库小学妹的 uid，取自私信详情页 {@code private_detail?id=79248480} */
    static final long AUDITOR_UID = 79248480L;

    /** 单页条数 */
    private static final int PAGE_SIZE = 50;

    /** 全量回填的翻页上限（约 4000 条），防止接口异常时无限翻页 */
    private static final int MAX_PAGES = 80;

    private NetEaseMsgClient() {
    }

    /** 一条来自审核方的私信。 */
    static final class Notice {
        final String text;
        final long time;

        Notice(String text, long time) {
            this.text = text;
            this.time = time;
        }
    }

    /**
     * 拉取与审核方的私信。
     *
     * <p>{@code fullHistory} 为 {@code true} 时按「最旧一条的 time 作为下页边界」持续翻页，
     * 直到服务端不再返回更早的消息，从而把整段聊天记录都取回来；归档为空时用它做一次全量回填，
     * 之后只取最新一页做增量同步，避免每 10 分钟把历史全部重拉一遍。
     *
     * @param token       可选的取消令牌
     * @param fullHistory 是否全量翻页
     * @return 按时间倒序的通知列表；没有新消息时返回空列表
     */
    static List<Notice> latestNotices(Context context, Http.CancelToken token, boolean fullHistory)
            throws WikiException {
        if (context == null || !WikiPrefs.hasCookie(context)) {
            throw new WikiException(WikiException.Kind.NO_CREDENTIAL, "未登录，无法读取审核私信");
        }
        String cookie = WikiPrefs.cookie(context);
        // 对话对象参数名是 userId，不是 uid：旧实现传 uid 时服务端只回
        // {"code":400,"msg":"参数错误"}，私信永远拉不到，归档因此长期为空。
        String key = "userId";
        JSONObject root = postHistory(key, cookie, -1L, token);
        if (root.optInt("code", 200) != 200) {
            Log.w(TAG, "userId 形态被拒（" + describe(root) + "），回退 uid 再试一次");
            JSONObject retry = postHistory("uid", cookie, -1L, token);
            if (retry.optInt("code", 200) == 200) {
                root = retry;
                key = "uid";
            }
        }

        List<Notice> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        long boundary = -1L;
        int fetchedPages = 0;
        for (int page = 0; page < MAX_PAGES; page++) {
            fetchedPages++;
            JSONArray msgs = messages(root);
            if (msgs == null) {
                if (page == 0) {
                    // 把服务端错误暴露到调用方，页面才能提示「同步失败」而不是伪装成「没有新内容」
                    throw new WikiException(WikiException.Kind.BAD_JSON,
                            "私信接口返回异常：" + describe(root));
                }
                break;
            }
            long oldest = Long.MAX_VALUE;
            int fresh = 0;
            for (int i = 0; i < msgs.length(); i++) {
                JSONObject msg = msgs.optJSONObject(i);
                if (msg == null || !fromAuditor(msg)) {
                    continue;
                }
                String text = textOf(msg);
                if (text.isEmpty()) {
                    continue;
                }
                long time = msg.optLong("time", 0L);
                // 同一秒内的多条消息用正文再区分，跨页重叠也不会重复入库
                if (!seen.add(time + "|" + text.hashCode())) {
                    continue;
                }
                out.add(new Notice(text, time));
                fresh++;
                if (time > 0 && time < oldest) {
                    oldest = time;
                }
            }
            if (!fullHistory) {
                break;
            }
            // more=false 说明已经到头；fresh=0 / 边界没有前移说明分页失去意义，及时收手
            if (!root.optBoolean("more", true) || fresh == 0 || oldest == Long.MAX_VALUE
                    || (boundary > 0 && oldest >= boundary)) {
                break;
            }
            boundary = oldest;
            try {
                // 连续翻几十页时留出间隔，避免触发风控
                Thread.sleep(80L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            root = postHistory(key, cookie, boundary, token);
            if (root.optInt("code", 200) != 200) {
                Log.w(TAG, "第 " + (page + 2) + " 页拉取失败：" + describe(root));
                break;
            }
        }
        Log.d(TAG, "私信拉取完成：pages=" + fetchedPages + " notices=" + out.size()
                + " full=" + fullHistory + " 最早=" + boundary);
        return out;
    }

    /** 发起一次私信历史请求；{@code key} 为对话对象的参数名（userId / uid）。 */
    private static JSONObject postHistory(String key, String cookie, long before,
                                          Http.CancelToken token)
            throws WikiException {
        JSONObject payload = new JSONObject();
        try {
            // 网页端下发的同样是字符串形式；time 传上一页最旧一条的时间即为「继续往前翻」
            payload.put(key, String.valueOf(AUDITOR_UID));
            payload.put("limit", PAGE_SIZE);
            payload.put("time", before > 0 ? before : -1L);
        } catch (Exception e) {
            throw new WikiException(WikiException.Kind.UNKNOWN, "构造私信参数失败", e);
        }
        return NetEaseWeApi.post("/api/msg/private/history", payload, cookie, token);
    }

    private static String describe(JSONObject root) {
        if (root == null) {
            return "空响应";
        }
        return "code=" + root.optInt("code", -1) + " msg=" + root.optString("msg", "");
    }

    /** 兼容 {@code msgs} 直接在根节点 / 包在 {@code data} 里的两种形态。 */
    private static JSONArray messages(JSONObject root) {
        JSONArray arr = root.optJSONArray("msgs");
        if (arr != null) {
            return arr;
        }
        JSONObject data = root.optJSONObject("data");
        if (data != null) {
            arr = data.optJSONArray("msgs");
            if (arr != null) {
                return arr;
            }
            arr = data.optJSONArray("data");
            if (arr != null) {
                return arr;
            }
        }
        return root.optJSONArray("data");
    }

    /** 只保留审核方发来的消息（自己发出去的不参与归档）。 */
    private static boolean fromAuditor(JSONObject msg) {
        long from = msg.optLong("fromUserId", 0L);
        if (from == 0L) {
            JSONObject fromUser = msg.optJSONObject("fromUser");
            if (fromUser != null) {
                from = fromUser.optLong("userId", 0L);
            }
        }
        // 字段缺失时不排除：宁可多解析一条，也不要漏掉审核通知
        return from == 0L || from == AUDITOR_UID;
    }

    /**
     * 取消息正文。
     *
     * <p>私信正文在接口里可能是纯文本，也可能是 {@code {"msg":"..."}} 形式的
     * 序列化 JSON（富文本消息更复杂），这里做一次还原，失败则按原文返回。
     */
    private static String textOf(JSONObject msg) {
        String text = msg.optString("text", "");
        if (text.isEmpty()) {
            text = msg.optString("msg", "");
        }
        text = text.trim();
        if (text.startsWith("{")) {
            try {
                JSONObject inner = new JSONObject(text);
                String innerMsg = inner.optString("msg", "");
                if (!innerMsg.isEmpty()) {
                    text = innerMsg;
                }
            } catch (Exception ignored) {
                // 不是 JSON，保持原样
            }
        }
        return text.trim();
    }
}

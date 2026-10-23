package com.auramusic.wiki.classic;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 「我的贡献」数据源客户端（对齐原 APK {@code Lt1/P}）。
 *
 * <p><b>空白问题根因</b>：此前实现用明文 GET 请求
 * {@code https://music.163.com/api/rep/ugc/user/devote}，而官方 {@code /api/rep/ugc/*}
 * 端点只接受 WEAPI 加密 POST，明文请求一律返回 {@code {"code":301,"message":"系统错误"}}，
 * 被界面误判为「登录态失效」，于是刚登录也显示登录引导/空白。
 *
 * <p><b>修复后</b>：列表改走 {@code /api/rep/ugc/detail}（加密 POST），
 * 参数、筛选值、分页步长与原 APK 完全一致：
 * <pre>
 *   type / limit(=20) / offset(=页索引×20) / order=desc / sortBy=createTime
 *   [auditStatus]  // 仅在非「全部」时携带
 *   → data.wikiDetailResultVoList[] + data.total
 * </pre>
 *
 * <p>加密通道与原 APK 的可选「接口通道」一一对应：{@link TransportMode#EAPI} 走 App 通道，
 * {@link TransportMode#WEAPI} 走网页通道，{@link TransportMode#AUTO} 先 eapi、失败降级 weapi。
 */
public final class NetEaseWikiClient {

    /** 日志 TAG，便于按「贡献接口」过滤排查 */
    private static final String TAG = "WikiClient";

    /** 列表端点：data.wikiDetailResultVoList[] + data.total */
    private static final String API_DETAIL = "/api/rep/ugc/detail";

    /**
     * 并行请求池：用于「全部」页的多来源补齐。
     *
     * <p>线程数刻意压到 2：这些请求都打到同一个风控域，过高并发反而更容易被限流。
     */
    private static final ExecutorService PARALLEL =
            Executors.newFixedThreadPool(2, runnable -> daemon(runnable, "wiki-parallel"));

    /**
     * 翻页扫描池：与 {@link #PARALLEL} 分开，避免「外层翻页等内层补齐」时把线程池占满。
     */
    private static final ExecutorService SCAN_POOL =
            Executors.newFixedThreadPool(3, runnable -> daemon(runnable, "wiki-scan"));

    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }
    /** 统计端点：data.ugcCount + data.cloudShell */
    private static final String API_DEVOTE = "/api/rep/ugc/user/devote";

    /** 每页条数，与原 APK {@code mul-int/lit8 v10, p1, 0x14} 一致 */
    public static final int PAGE_SIZE = 20;

    /** 接口返回「需要登录」的业务码 */
    private static final int CODE_NEED_LOGIN = 301;
    /** 接口返回「无权限 / 未实名」等业务码，统一按凭据问题处理 */
    private static final int CODE_FORBIDDEN = 250;

    /** 类型筛选项，顺序与原 APK 一致（全部 + 6 类） */
    public static final String[] TYPE_LABELS = {
            "全部", "补充专辑", "歌曲", "专辑", "艺人", "歌词", "翻译歌词（LRC）"
    };
    public static final int[] TYPE_VALUES = {0, 101, 3, 2, 1, 5, 6};

    /** 类型取值（对齐原 APK {@code o/b} 中的标签 → 值映射），供跳转与轮询复用 */
    public static final int TYPE_ALL = 0;
    public static final int TYPE_ALBUM_SUPPLEMENT = 101;
    public static final int TYPE_SONG = 3;
    public static final int TYPE_ALBUM = 2;
    public static final int TYPE_ARTIST = 1;
    public static final int TYPE_LYRIC = 5;
    public static final int TYPE_LYRIC_TRANSLATION = 6;

    /**
     * 「全部」标签页需要客户端额外补齐的来源类型。
     *
     * <p>实测（见 {@code .diag/all-param-variants.js}、{@code all-full-scan.js}）：服务端
     * {@code /api/rep/ugc/detail} 的「全部」结果里<b>不含</b>歌词与翻译条目（穷举 {@code type=0}
     * / 省略 type / 数组 / {@code all=true} 等参数组合均无法带出），因此「歌词和翻译在全部页
     * 不显示」是服务端数据缺失，只能在客户端按类型补拉后合并。
     */
    public static final int[] ALL_EXTRA_TYPES = {TYPE_LYRIC, TYPE_LYRIC_TRANSLATION};

    /** 状态筛选项，顺序与原 APK {@code Lt1/P;->e} 一致；值为 null 表示「全部」（不传 auditStatus） */
    public static final String[] STATUS_LABELS = {
            "全部", "待审核", "审核中", "部分通过", "审核通过", "未采纳"
    };
    public static final Integer[] STATUS_VALUES = {null, 0, 1, 4, 5, -5};

    private NetEaseWikiClient() {
    }

    /**
     * 类型 → 卡片类型标签文案资源；未收录的类型返回 0（调用方隐藏标签）。
     *
     * <p>「全部」标签页里歌曲 / 专辑 / 艺人 / 补充专辑 / 歌词 / 翻译混排，且歌词与翻译
     * 条目的 {@code type} 都是 0、名称相同，必须靠类型标签才能分辨。
     */
    public static int typeLabelRes(int type) {
        switch (type) {
            case TYPE_ALBUM_SUPPLEMENT:
                return R.string.contrib_type_album_supplement;
            case TYPE_SONG:
                return R.string.contrib_type_song;
            case TYPE_ALBUM:
                return R.string.contrib_type_album;
            case TYPE_ARTIST:
                return R.string.contrib_type_artist;
            case TYPE_LYRIC:
                return R.string.contrib_type_lyric;
            case TYPE_LYRIC_TRANSLATION:
                return R.string.contrib_type_lyric_translation;
            default:
                return 0;
        }
    }

    /** 一页贡献记录 */
    public static final class Page {
        public final List<Contribution> items;
        public final int total;
        /**
         * 总页数；{@code <= 0} 表示未提供，调用方按 {@code total} 与每页条数推算。
         *
         * <p>「全部」标签页是客户端聚合多来源的结果，其总页数取各来源页数的最大值，
         * 不能由求和后的 {@code total} 推算（否则会翻出没有数据的空页）。
         */
        public final int pageCount;

        Page(List<Contribution> items, int total) {
            this(items, total, 0);
        }

        Page(List<Contribution> items, int total, int pageCount) {
            this.items = items;
            this.total = total;
            this.pageCount = pageCount;
        }

        /** 以当前 offset 为起点是否还有下一页 */
        public boolean hasMore(int offset) {
            return offset + items.size() < total;
        }
    }

    /** 贡献统计（对应原 APK 账号卡片的「贡献数 / 云贝」） */
    public static final class Stats {
        public final int ugcCount;
        public final int cloudShell;

        Stats(int ugcCount, int cloudShell) {
            this.ugcCount = ugcCount;
            this.cloudShell = cloudShell;
        }
    }

    /**
     * 拉取一页贡献记录。
     *
     * @param type        分类取值见 {@link #TYPE_VALUES}
     * @param auditStatus 审核状态过滤，{@code null} 表示全部
     * @param offset      偏移量（页索引 × {@link #PAGE_SIZE}）
     * @throws WikiException NO_CREDENTIAL / TIMEOUT / NO_NETWORK / HTTP / BAD_JSON 等
     */
    public static Page fetchPage(Context context, int type, Integer auditStatus, int offset,
                                 int limit, Http.CancelToken token) throws WikiException {
        requireCredential(context);

        JSONObject payload = new JSONObject();
        try {
            // type 始终按原 APK 语义下发（「全部」= 0）。
            // 网页通道对 type=0 的语义不同，由 {@link #webPayload} 在下发前剔除，见其说明。
            payload.put("type", type);
            payload.put("limit", Math.max(1, limit));
            payload.put("offset", Math.max(0, offset));
            payload.put("order", "desc");
            payload.put("sortBy", "createTime");
            if (auditStatus != null) {
                payload.put("auditStatus", auditStatus.intValue());
            }
        } catch (Exception e) {
            throw new WikiException(WikiException.Kind.BAD_JSON, "构造请求参数失败", e);
        }

        Log.d(TAG, "fetchPage 请求 " + API_DETAIL + " type=" + type
                + " auditStatus=" + auditStatus + " offset=" + offset + " limit=" + limit
                + " payload=" + payload);

        JSONObject root = request(context, API_DETAIL, payload, token);
        ensureBusinessOk(root);

        JSONObject data = dataObject(root);
        if (data == null) {
            Log.w(TAG, "fetchPage 响应缺少 data 节点，按空页处理；root=" + root);
            return new Page(Collections.<Contribution>emptyList(), 0);
        }
        JSONArray list = data.optJSONArray("wikiDetailResultVoList");
        List<Contribution> items = Contribution.listFrom(list);
        // 标注来源查询类型：歌词(5)/翻译(6)条目的 type 字段恒为 0，
        // 必须靠来源类型才能区分「歌词」与「翻译」，并用于跳转分发。
        for (Contribution c : items) {
            c.sourceType = type;
        }
        int total = data.optInt("total", items.size());
        Log.d(TAG, "fetchPage 成功 type=" + type + " 本页=" + items.size() + " 总数=" + total);
        return new Page(items, total);
    }

    /** 拉取贡献统计；失败时调用方降级为不展示即可。 */
    public static Stats fetchStats(Context context, Http.CancelToken token) throws WikiException {
        requireCredential(context);
        JSONObject root = request(context, API_DEVOTE, new JSONObject(), token);
        ensureBusinessOk(root);
        JSONObject data = dataObject(root);
        if (data == null) {
            Log.w(TAG, "fetchStats 响应缺少 data 节点；root=" + root);
            return new Stats(0, 0);
        }
        Stats stats = new Stats(data.optInt("ugcCount", 0), data.optInt("cloudShell", 0));
        Log.d(TAG, "fetchStats 成功 ugcCount=" + stats.ugcCount + " cloudShell=" + stats.cloudShell);
        return stats;
    }

    /**
     * 按页码取一页（页码从 1 开始），供后台轮询等只需列表的场景使用。
     */
    public static List<Contribution> fetchContributions(Context context, int type, int pageNo,
                                                        int pageSize, Http.CancelToken token)
            throws WikiException {
        int size = Math.max(1, pageSize);
        int offset = (Math.max(1, pageNo) - 1) * size;
        return fetchPage(context, type, null, offset, size, token).items;
    }

    /**
     * 「全部」标签页专用：聚合所有类型的一页数据。
     *
     * <p>服务端的「全部」结果不含歌词 / 翻译（见 {@link #ALL_EXTRA_TYPES} 说明），
     * 因此这里在「全部」结果之上按同一页码补拉 {@link #ALL_EXTRA_TYPES}，合并后按提交时间
     * 倒序排列、按 {@link Contribution#stableKey()} 去重，保证歌词与翻译和其它音乐元素
     * 在「全部」页同步呈现。
     *
     * <p>补齐来源失败（网络抖动等）时只记录日志并呈现已取到的部分，不影响主结果；主来源
     * 失败才抛出异常交给上层展示错误态。
     */
    public static Page fetchAllTypesPage(Context context, Integer auditStatus, int offset,
                                         int limit, Http.CancelToken token) throws WikiException {
        Page base = fetchPage(context, TYPE_ALL, auditStatus, offset, limit, token);
        List<Contribution> merged = new ArrayList<>(base.items);
        int total = base.total;
        int pageCount = pageCountOf(base.total, limit);
        // 补齐来源与主来源互不依赖：串行等于把首屏延迟叠加三次，这里改为并行
        List<Future<Page>> pending = new ArrayList<>(ALL_EXTRA_TYPES.length);
        for (final int type : ALL_EXTRA_TYPES) {
            pending.add(PARALLEL.submit(() ->
                    fetchPage(context, type, auditStatus, offset, limit, token)));
        }
        for (int i = 0; i < pending.size(); i++) {
            try {
                Page extra = pending.get(i).get();
                merged.addAll(extra.items);
                total += extra.total;
                pageCount = Math.max(pageCount, pageCountOf(extra.total, limit));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                cancelAll(pending);
                throw new WikiException(WikiException.Kind.CANCELLED, "请求已取消", e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof WikiException
                        && ((WikiException) cause).getKind() == WikiException.Kind.CANCELLED) {
                    throw (WikiException) cause;
                }
                Log.w(TAG, "「全部」补齐类型 " + ALL_EXTRA_TYPES[i] + " 失败，仅呈现已取到的类型："
                        + (cause == null ? e.getMessage() : cause.getMessage()), cause);
            }
        }
        List<Contribution> items = mergeAndDedupe(merged);
        Log.d(TAG, "fetchAllTypesPage 聚合 本页=" + items.size()
                + " 总数=" + total + " 总页数=" + pageCount);
        return new Page(items, total, pageCount);
    }

    private static void cancelAll(List<Future<Page>> pending) {
        for (Future<Page> future : pending) {
            future.cancel(true);
        }
    }

    /** 扫描进度（在后台线程回调，调用方自行切主线程） */
    public interface ScanProgress {
        void onScanned(int count);
    }

    /**
     * 并行扫描某个分类 / 状态下的全部条目。
     *
     * <p>先取第一页拿到总页数，其余页一次性提交到扫描池，最后<strong>按页序</strong>拼接，
     * 保证「全局下标 = 页码 × 每页条数 + 页内偏移」这一映射成立（跳转到指定页依赖它）。
     * 串行实现下 30 页要排 30 次往返；并行后接近「总页数 ÷ 3」次。
     */
    public static List<Contribution> scanAll(Context context, int type, Integer auditStatus,
                                             int pageSize, int maxPages,
                                             Http.CancelToken token,
                                             ScanProgress progress) throws WikiException {
        final int size = Math.max(1, pageSize);
        Page first = type == TYPE_ALL
                ? fetchAllTypesPage(context, auditStatus, 0, size, token)
                : fetchPage(context, type, auditStatus, 0, size, token);
        List<Contribution> all = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        appendUnique(all, seen, first.items);
        if (progress != null) {
            progress.onScanned(all.size());
        }
        int pages = first.pageCount > 0 ? first.pageCount
                : Math.max(1, (first.total + size - 1) / size);
        pages = Math.min(pages, Math.max(1, maxPages));
        if (pages <= 1) {
            return all;
        }
        List<Future<Page>> futures = new ArrayList<>(pages - 1);
        for (int p = 1; p < pages; p++) {
            final int offset = p * size;
            futures.add(SCAN_POOL.submit(() -> type == TYPE_ALL
                    ? fetchAllTypesPage(context, auditStatus, offset, size, token)
                    : fetchPage(context, type, auditStatus, offset, size, token)));
        }
        for (Future<Page> future : futures) {
            Page page;
            try {
                page = future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                cancelAll(futures);
                throw new WikiException(WikiException.Kind.CANCELLED, "扫描已取消", e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof WikiException
                        && ((WikiException) cause).getKind() == WikiException.Kind.CANCELLED) {
                    cancelAll(futures);
                    throw (WikiException) cause;
                }
                // 单页失败只跳过该页：检索结果宁可少一页，也不要整次扫描失败
                Log.w(TAG, "扫描单页失败，已跳过：" + (cause == null ? e.getMessage()
                        : cause.getMessage()), cause);
                continue;
            }
            appendUnique(all, seen, page.items);
            if (progress != null) {
                progress.onScanned(all.size());
            }
        }
        return all;
    }

    private static void appendUnique(List<Contribution> out, java.util.Set<String> seen,
                                     List<Contribution> items) {
        if (items == null) {
            return;
        }
        for (Contribution item : items) {
            if (item != null && seen.add(item.stableKey())) {
                out.add(item);
            }
        }
    }

    /** 按每页条数换算总页数，至少 1 页 */
    private static int pageCountOf(int total, int limit) {
        int size = Math.max(1, limit);
        return Math.max(1, (Math.max(0, total) + size - 1) / size);
    }

    /**
     * 合并多来源条目：按 {@link Contribution#stableKey()} 去重，再按提交时间倒序排序。
     *
     * <p>抽为包级可见的纯函数，便于单测覆盖聚合去重与排序契约。
     */
    static List<Contribution> mergeAndDedupe(List<Contribution> items) {
        if (items == null || items.isEmpty()) {
            return new ArrayList<>();
        }
        Map<String, Contribution> unique = new LinkedHashMap<>();
        for (Contribution c : items) {
            if (c == null) {
                continue;
            }
            String key = c.stableKey();
            if (!unique.containsKey(key)) {
                unique.put(key, c);
            }
        }
        List<Contribution> out = new ArrayList<>(unique.values());
        Collections.sort(out, (a, b) -> Long.compare(b.createTime, a.createTime));
        return out;
    }

    // ------------------------------------------------------------------

    /** 未登录时直接给出可识别的凭据异常，避免发出注定失败的请求。 */
    private static void requireCredential(Context context) throws WikiException {
        if (!WikiPrefs.hasCookie(context)) {
            throw new WikiException(WikiException.Kind.NO_CREDENTIAL, "未登录，无法读取「我的贡献」");
        }
    }

    /**
     * 按用户选定的接口通道发起一次加密 POST。
     *
     * <p>{@link TransportMode#WEAPI} 直接走网页通道；{@link TransportMode#AUTO} 与显式
     * {@link TransportMode#EAPI} 共用同一套容错策略：先用 App 的 eapi，出现下列任一情况即
     * 降级为网页版 weapi（网页百科本身使用的方式）重试——
     * <ul>
     *   <li>响应体不是合法 JSON / 为空（{@code BAD_JSON}）；</li>
     *   <li>业务码判定登录态失效或异常；</li>
     *   <li>响应缺少 {@code data} 节点（eapi 对部分网页百科端点在风控/限流下会返回
     *       「HTTP 200 + 无 data」的非预期结构）。</li>
     * </ul>
     * 这样「App 通道」在服务端不配合时也能拿到数据并正常渲染，而不是直接抛出「数据格式异常」。
     */
    private static JSONObject request(Context context, String apiPath, JSONObject payload,
                                      Http.CancelToken token) throws WikiException {
        String cookie = WikiPrefs.cookie(context);
        TransportMode mode = WikiPrefs.transportMode(context);
        // AUTO 模式沿用上次成功的通道：eapi 对网页百科端点稳定返回空响应，
        // 每次先试它等于给每个请求多付一次失败往返（实测 60–110ms）
        TransportMode hint = mode == TransportMode.AUTO
                ? WikiPrefs.transportHint(context) : null;
        Log.d(TAG, "request path=" + apiPath + " 通道=" + mode
                + (hint == null ? "" : " 记忆=" + hint));

        if (mode == TransportMode.WEAPI || hint == TransportMode.WEAPI) {
            try {
                JSONObject root = NetEaseWeApi.post(apiPath, webPayload(payload), cookie, token);
                WikiPrefs.setTransportHint(context, TransportMode.WEAPI);
                return root;
            } catch (WikiException e) {
                if (e.getKind() == WikiException.Kind.CANCELLED
                        || mode == TransportMode.WEAPI) {
                    throw e;
                }
                // 记忆失效：清掉后按原策略重新探测
                WikiPrefs.setTransportHint(context, null);
                Log.w(TAG, "记忆的 WEAPI 通道失败，改试 EAPI：" + e.getMessage(), e);
            }
        }

        try {
            JSONObject root = NetEaseEapi.post(apiPath, payload, WikiPrefs.deviceId(context),
                    cookie, token);
            ensureBusinessOk(root);
            if (dataObject(root) == null) {
                Log.w(TAG, "EAPI 响应缺少可解析的 data 节点，降级 WEAPI 重试；root=" + root);
                JSONObject fallback = NetEaseWeApi.post(apiPath, webPayload(payload), cookie, token);
                WikiPrefs.setTransportHint(context, TransportMode.WEAPI);
                return fallback;
            }
            WikiPrefs.setTransportHint(context, TransportMode.EAPI);
            return root;
        } catch (WikiException e) {
            if (e.getKind() == WikiException.Kind.CANCELLED) {
                throw e;
            }
            Log.w(TAG, "EAPI 通道失败（kind=" + e.getKind() + "），降级 WEAPI：" + e.getMessage(), e);
            JSONObject root = NetEaseWeApi.post(apiPath, webPayload(payload), cookie, token);
            WikiPrefs.setTransportHint(context, TransportMode.WEAPI);
            return root;
        }
    }

    /**
     * 网页通道（WEAPI）专用载荷修正：「全部」必须<b>省略</b> {@code type}。
     *
     * <p>两个通道对 {@code type=0} 的语义并不一致（实测结论）：
     * <ul>
     *   <li><b>App 通道（EAPI）</b>：{@code type=0} 即「全部」，与原 APK 一致；而且
     *       <b>省略</b> {@code type} 时服务端会回落到「补充专辑」，这正是此前
     *       「『全部』只显示补充专辑」的根因；</li>
     *   <li><b>网页通道（WEAPI）</b>：{@code type=0} 被当作「无匹配类型」，
     *       稳定返回 {@code total=0} 的空列表；只有省略 {@code type} 才是「全部」。</li>
     * </ul>
     *
     * <p>因此这里只在网页通道下发前剔除 {@code type=0}，其余类型与非列表接口原样透传。
     */
    static JSONObject webPayload(JSONObject payload) {
        if (payload == null || !payload.has("type") || payload.optInt("type", -1) != TYPE_ALL) {
            return payload;
        }
        try {
            JSONObject copy = new JSONObject(payload.toString());
            copy.remove("type");
            return copy;
        } catch (Exception e) {
            Log.w(TAG, "构造网页通道载荷失败，按原样下发", e);
            return payload;
        }
    }

    /**
     * 取出 {@code data} 节点，并兼容不同通道下的两种形态：
     * <ul>
     *   <li>Web 通道（WEAPI）与多数 App 通道响应：{@code data} 直接是 JSON 对象；</li>
     *   <li>App 通道（EAPI）在部分端点上会把 {@code data} 二次序列化成<b>字符串</b>，
     *       此时 {@code optJSONObject} 会返回 null，若不做还原就会把正常数据误判为
     *       「数据格式异常 / 空列表」。这里对其做一次 JSON 还原。</li>
     * </ul>
     *
     * @return data 对象；确实不存在或无法还原时返回 {@code null}
     */
    private static JSONObject dataObject(JSONObject root) {
        if (root == null) {
            return null;
        }
        JSONObject direct = root.optJSONObject("data");
        if (direct != null) {
            return direct;
        }
        Object raw = root.opt("data");
        if (raw instanceof String) {
            String text = ((String) raw).trim();
            if (!text.isEmpty()) {
                try {
                    return new JSONObject(text);
                } catch (Exception e) {
                    Log.w(TAG, "data 为字符串但无法还原为 JSON：" + text, e);
                }
            }
        }
        return null;
    }

    private static void ensureBusinessOk(JSONObject root) throws WikiException {
        int code = root.optInt("code", 0);
        if (code == CODE_NEED_LOGIN || code == CODE_FORBIDDEN) {
            Log.w(TAG, "业务码 " + code + " → 登录态失效；root=" + root);
            throw new WikiException(WikiException.Kind.NO_CREDENTIAL, "登录态已失效，请重新登录");
        }
        if (code != 0 && code != 200) {
            String message = root.optString("message", "");
            if (message.isEmpty() || "null".equals(message)) {
                message = root.optString("msg", "");
            }
            if (message.isEmpty() || "null".equals(message)) {
                // 服务端只给业务码时把原始响应带出来，便于定位
                String raw = root.toString();
                message = raw.length() > 300 ? raw.substring(0, 300) + "…" : raw;
            }
            Log.w(TAG, "业务码异常 " + code + "：" + message + "；root=" + root);
            throw new WikiException(WikiException.Kind.EMPTY, "接口业务码 " + code + "：" + message);
        }
    }

    // ------------------------------------------------------------------
    // 专辑上传（补充缺失专辑）
    // ------------------------------------------------------------------

    /** 搜索可补充的艺人（原 APK：keyword + limit=40，返回 data.list）。 */
    public static List<AlbumDraft.Artist> searchArtists(Context context, String keyword,
                                                        Http.CancelToken token)
            throws WikiException {
        JSONObject payload = new JSONObject();
        try {
            payload.put("keyword", keyword == null ? "" : keyword.trim());
            payload.put("limit", 40);
        } catch (Exception ignored) {
            // JSONObject.put(String, Object) 不会失败
        }
        JSONObject root = request(context, "/api/rep/ugc/artist/search", payload, token);
        JSONObject data = dataObject(root);
        JSONArray list = data == null ? null : data.optJSONArray("list");
        List<AlbumDraft.Artist> out = new ArrayList<>();
        if (list != null) {
            for (int i = 0; i < list.length(); i++) {
                JSONObject item = list.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                out.add(new AlbumDraft.Artist(item.optLong("artistId"),
                        item.optString("artistName"),
                        item.optString("artistAvatarPicUrl")));
            }
        }
        return out;
    }

    /**
     * 曲风字典（{@code categoryName=STYLE_TAG}）。
     *
     * <p>与原 APK 完全一致：{@code tagName} 传空串取全部，{@code resType=2} 表示补充专辑。
     * 返回的 {@code id} 是提交时真正使用的曲风 ID —— 之前用自造的 1000~1011 会让服务端
     * 把「电子」落成「中国传统特色-民乐」，所以这里必须以接口返回为准。
     */
    public static List<AlbumDraft.Tag> fetchStyleTags(Context context, Http.CancelToken token)
            throws WikiException {
        requireCredential(context);
        JSONObject payload = new JSONObject();
        try {
            payload.put("tagName", "");
            payload.put("resType", 2);
            payload.put("categoryName", "STYLE_TAG");
        } catch (Exception ignored) {
            // JSONObject.put(String, Object) 不会失败
        }
        JSONObject root = NetEaseWeApi.post("/api/rep/ugc/tag/list", payload,
                WikiPrefs.cookie(context), token);
        JSONArray data = root.optJSONArray("data");
        if (data == null) {
            JSONObject inner = dataObject(root);
            if (inner != null) {
                data = inner.optJSONArray("data");
            }
        }
        List<AlbumDraft.Tag> out = new ArrayList<>();
        if (data != null) {
            for (int i = 0; i < data.length(); i++) {
                JSONObject item = data.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                String name = item.optString("name", "").trim();
                if (name.isEmpty()) {
                    continue;
                }
                out.add(new AlbumDraft.Tag(item.optString("id", ""), name));
            }
        }
        if (out.isEmpty()) {
            throw new WikiException(WikiException.Kind.EMPTY, "曲风列表为空");
        }
        return out;
    }

    /**
     * 提交补充专辑。
     *
     * <p>该端点只接受 WEAPI，且失败重试可能造成<b>重复提交</b>，因此这里固定走网页通道并
     * 只发一次请求，不做 eapi→weapi 的自动降级。
     */
    public static JSONObject submitAlbum(Context context, long artistId, JSONObject albumVo,
                                         Http.CancelToken token) throws WikiException {
        requireCredential(context);
        JSONObject payload = new JSONObject();
        try {
            // 原 APK 会先把嵌套 JSON 转成字符串再加密；直接下发 JSONObject 会被服务端
            // 按 String 字段反序列化失败，返回业务码 500。
            payload.put("albumVo", albumVo == null ? "" : albumVo.toString());
            payload.put("artistId", artistId);
        } catch (Exception ignored) {
            // 同上
        }
        JSONObject root = NetEaseWeApi.post("/api/rep/ugc/add/artist/album",
                webPayload(payload), WikiPrefs.cookie(context), token);
        if (root == null) {
            throw new WikiException(WikiException.Kind.BAD_JSON, "提交失败：空响应");
        }
        ensureBusinessOk(root);
        return root;
    }
}

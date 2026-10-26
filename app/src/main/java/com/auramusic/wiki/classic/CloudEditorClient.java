package com.auramusic.wiki.classic;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 云小编（网易云曲库共建者）数据客户端。
 *
 * <p>全部走 EAPI 通道，端点与网页版 {@code music.163.com/st/wiki} 的云小编工作台一致：
 * <ul>
 *   <li>{@code /api/rep/ugc/user/get} —— 等级、头衔、总积分 / 可用积分 / 今日积分、我的排名、签到状态；</li>
 *   <li>{@code /api/rep/ugc/user/sign} —— 每日签到；</li>
 *   <li>{@code /api/rep/ugc/user/devote} —— 贡献 / 积分流水；</li>
 *   <li>{@code /api/rep/ugc/task/rank/list} —— 排行榜（type 区分日榜 / 月榜 / 总榜）；</li>
 *   <li>{@code /api/rep/ugc/user/vip}、{@code /api/rep/ugc/user/collect-vip} —— 一日黑胶会员任务；</li>
 *   <li>{@code /api/rep/ugc/activity/get}、{@code /api/rep/ugc/activity/collect} —— 活动任务与积分领取。</li>
 * </ul>
 *
 * <p>服务端字段在不同版本间存在差异（例如列表字段名 records / list / rankList 不统一），
 * 因此解析全部按「多候选键 + 容错」处理：宁可少显示一项，也不因单个字段缺失导致整页失败。
 * 解析函数保持包级可见，便于用固定 JSON 做单元测试。
 */
public final class CloudEditorClient {

    /** 排行榜维度：与网页版 honor-roll 的 type 一一对应 */
    static final int RANK_DAY = 1;
    static final int RANK_MONTH = 2;
    static final int RANK_TOTAL = 3;

    /** 积分流水每页条数：实测服务端支持 200 条/页，单页耗时约 100ms */
    static final int FLOW_PAGE_SIZE = 200;
    /** 流水最多拉取的页数，避免异常数据导致无限请求（200×100 = 2 万条上限） */
    private static final int FLOW_MAX_PAGES = 100;

    private static final String API_PROFILE = "/api/rep/ugc/user/get";
    private static final String API_SIGN = "/api/rep/ugc/user/sign";
    private static final String API_RANK = "/api/rep/ugc/honor-roll/list";
    private static final String API_VIP = "/api/rep/ugc/user/vip";
    private static final String API_VIP_CLAIM = "/api/rep/ugc/user/collect-vip";
    private static final String API_ACTIVITY = "/api/rep/ugc/activity/get";
    private static final String API_ACTIVITY_CLAIM = "/api/rep/ugc/activity/collect";
    private static final String API_INTEGRATION_RECORDS = "/api/rep/ugc/user/integration/records";
    private static final String API_LOTTERY_CHANCE = "/api/middle/play/lottery/remain/chance";
    private static final String API_LOTTERY_DRAW = "/api/middle/play/do/lottery";

    /** 会员任务与活动任务的固定活动 ID（网页端默认值） */
    private static final String VIP_ACTIVITY_ID = "5001";
    private static final String LOTTERY_ACTIVITY_ID = "6501202";

    private CloudEditorClient() {
    }

    // ------------------------------------------------------------------
    // 数据模型
    // ------------------------------------------------------------------

    public static final class Profile {
        public int level;
        public int starLevel;
        public String levelDesc = "";
        public String nextLevel = "";
        public int totalPoints;
        public int todayPoints;
        public int availablePoints;
        public int needPoints;
        /** 我的排名，0 表示服务端未给出 */
        public int rank;
        public boolean signed;
        public int signDays;
        public int signPoints;
        public String nickname = "";
        public String avatarUrl = "";
    }

    public static final class SignResult {
        public boolean success;
        public String message = "";
        public int points;
    }

    public static final class PointRecord {
        public String title = "";
        public String time = "";
        public int points;
        /** 原始时间戳（毫秒）：用于跳转到「我的贡献」按时间轴对照 */
        public long createTime;
        /**
         * 关联的贡献条目 ID / 类型。
         *
         * <p>接口若返回这类字段，就能从流水卡片跳转到「我的贡献」里对应的卡片；
         * 当前只做「有则记录」，没有时卡片保持简洁不可展开。
         */
        public long bizId;
        public String bizType = "";
    }

    public static final class RankEntry {
        public int rank;
        public int level;
        public int points;
        public long userId;
        public String nickname = "";
        public String levelDesc = "";
        public String avatarUrl = "";
    }

    public static final class RankPage {
        public final List<RankEntry> items = new ArrayList<>();
        /** 服务端返回的「我」的排名信息，未登录或未上榜时 rank 为 0 */
        public RankEntry currentUser;
        /** 榜单最近更新时间（毫秒） */
        public long updateTime;
        public int total;
        public boolean hasMore;
    }

    public static final class VipState {
        /** 10 / 20 可领取，30 今日已领取；0 表示服务端未返回状态 */
        public int status;
        /** 领取门槛（积分），由接口下发 */
        public int threshold = 50;
        public String message = "";
        /** 官方文案，例如「已领取1日黑胶会员，明天再来吧~」 */
        public String title = "";
    }

    public static final class ActivityTask {
        public String activityId = VIP_ACTIVITY_ID;
        public String title = "";
        public int points;
        public boolean claimed;
        /** 当前进度与目标值（服务端返回的是单个活动对象时才有意义） */
        public int count;
        public int goalCount;
    }

    public static final class ActivityState {
        public final List<ActivityTask> tasks = new ArrayList<>();
    }

    /** 单次抽奖结果；服务端没有冷却时间，限制只有「每日剩余次数」。 */
    public static final class LotteryResult {
        public String prizeName = "";
        /** 奖品类型原样保留，便于后续自动化按类型判别（积分 / 会员 / 实物） */
        public String prizeType = "";
        public int points;
        /** 抽完后剩余次数，-1 表示服务端未返回 */
        public int remainChance = -1;
        /** 请求耗时（毫秒）：用于确认抽奖本身是否需要等待 */
        public long elapsedMs;
    }

    // ------------------------------------------------------------------
    // 读取
    // ------------------------------------------------------------------

    static Profile fetchProfile(Context context, Http.CancelToken token) throws WikiException {
        JSONObject root = call(API_PROFILE, new JSONObject(), context, token);
        return parseProfile(root);
    }

    static SignResult sign(Context context, Http.CancelToken token) throws WikiException {
        JSONObject root = call(API_SIGN, new JSONObject(), context, token);
        SignResult result = new SignResult();
        result.success = true;
        result.message = root.optString("message", "");
        JSONObject data = root.optJSONObject("data");
        if (data != null) {
            result.points = firstInt(data, 0, "points", "signPoints", "score");
            if (result.message.isEmpty()) {
                result.message = data.optString("message", "");
            }
        }
        return result;
    }

    /**
     * 积分流水：按页拉全量。
     *
     * <p>接口单页上限有限，且用户明确要求看到全部条目，因此循环翻页直到
     * 返回不足一页或达到页数上限；任何一页失败都保留已取到的部分。
     */
    static List<PointRecord> fetchPointFlow(Context context, Http.CancelToken token)
            throws WikiException {
        List<PointRecord> all = new ArrayList<>();
        for (int page = 0; page < FLOW_MAX_PAGES; page++) {
            List<PointRecord> pageItems;
            try {
                pageItems = fetchPointFlowPage(context, page * FLOW_PAGE_SIZE, FLOW_PAGE_SIZE,
                        token);
            } catch (WikiException e) {
                if (e.getKind() == WikiException.Kind.CANCELLED) {
                    throw e;
                }
                if (all.isEmpty()) {
                    throw e;
                }
                break;
            }
            all.addAll(pageItems);
            if (pageItems.size() < FLOW_PAGE_SIZE) {
                break;
            }
        }
        return all;
    }

    /**
     * 取积分流水的一页。
     *
     * <p>页面首屏只需要第一页就够渲染，历史记录可以在界面出现之后继续补，
     * 这样进入页面不用等二十来次串行请求。
     */
    static List<PointRecord> fetchPointFlowPage(Context context, int offset, int limit,
                                                Http.CancelToken token) throws WikiException {
        JSONObject query = params("limit", limit, "offset", offset);
        return parsePointFlow(getJson(API_INTEGRATION_RECORDS, query, context, token));
    }

    /** 荣誉榜：日榜 / 月榜 / 总榜。接口一次返回整页（最多 500～1000 条）。 */
    static RankPage fetchRank(Context context, int type, Http.CancelToken token)
            throws WikiException {
        JSONObject query = params("type", type);
        JSONObject root;
        try {
            root = getJson(API_RANK, query, context, token);
        } catch (WikiException e) {
            if (e.getKind() == WikiException.Kind.CANCELLED) {
                throw e;
            }
            root = call(API_RANK, query, context, token);
        }
        return parseRankPage(root);
    }

    static VipState fetchVipState(Context context, Http.CancelToken token) throws WikiException {
        JSONObject root = call(API_VIP, new JSONObject(), context, token);
        if (BuildConfig.DEBUG) {
            android.util.Log.d("CloudEditor", "vip=" + root);
        }
        return parseVip(root);
    }

    /**
     * 领取一日黑胶会员。
     *
     * <p>官方网页端走「活动领取」接口，不同版本对 collect-vip 的参数要求不一致：
     * 先按 activityId 领取，业务失败时再退回通用活动领取接口，避免单一路径失效。
     */
    static void claimVip(Context context, Http.CancelToken token) throws WikiException {
        try {
            call(API_VIP_CLAIM, params("activityId", VIP_ACTIVITY_ID), context, token);
            if (BuildConfig.DEBUG) {
                android.util.Log.d("CloudEditor", "claimVip ok via collect-vip");
            }
        } catch (WikiException e) {
            if (e.getKind() == WikiException.Kind.CANCELLED
                    || e.getKind() == WikiException.Kind.TIMEOUT
                    || e.getKind() == WikiException.Kind.NO_NETWORK) {
                throw e;
            }
            if (BuildConfig.DEBUG) {
                android.util.Log.d("CloudEditor", "collect-vip failed: " + e.getKind()
                        + " / " + e.getMessage());
            }
            call(API_ACTIVITY_CLAIM, params("activityId", VIP_ACTIVITY_ID), context, token);
        }
    }

    static ActivityState fetchActivity(Context context, Http.CancelToken token) throws WikiException {
        JSONObject root = call(API_ACTIVITY, new JSONObject(), context, token);
        return parseActivity(root);
    }

    static void claimActivity(Context context, String activityId, Http.CancelToken token)
            throws WikiException {
        String id = activityId == null || activityId.isEmpty() ? VIP_ACTIVITY_ID : activityId;
        call(API_ACTIVITY_CLAIM, params("activityId", id), context, token);
    }

    /** 剩余抽奖次数；接口异常时返回 -1，界面隐藏该行，不影响其它模块。 */
    static int fetchLotteryChance(Context context, Http.CancelToken token) {
        try {
            JSONObject root = callEapiFirst(API_LOTTERY_CHANCE,
                    params("activityId", LOTTERY_ACTIVITY_ID), context, token);
            JSONObject data = root.optJSONObject("data");
            if (data != null) {
                return firstInt(data, -1, "remainChance", "chance", "remainCount", "count");
            }
            // 兼容 data 直接返回次数（数字或字符串）的旧版本
            return root.optInt("data", -1);
        } catch (WikiException e) {
            return -1;
        }
    }

    /**
     * 抽一次奖。
     *
     * <p>服务端没有冷却/等待时间，唯一的限制是每日剩余次数；App 内的转盘只是本地动画，
     * 因此自动化可以「抽完立刻查次数」循环到 0 为止。这里额外记录请求耗时，
     * 便于后续自动化据此设置超时。
     */
    static LotteryResult drawLottery(Context context, Http.CancelToken token) throws WikiException {
        long start = System.currentTimeMillis();
        JSONObject root = callEapiFirst(API_LOTTERY_DRAW,
                params("activityId", LOTTERY_ACTIVITY_ID, "drawCount", 1), context, token);
        if (BuildConfig.DEBUG) {
            android.util.Log.d("CloudEditor", "lottery=" + root);
        }
        LotteryResult result = parseLottery(root);
        result.elapsedMs = System.currentTimeMillis() - start;
        return result;
    }

    // ------------------------------------------------------------------
    // 请求与错误映射
    // ------------------------------------------------------------------

    /** 构造请求参数；JSONException 统一收敛为 WikiException，避免调用方到处 try/catch。 */
    private static JSONObject params(Object... pairs) throws WikiException {
        JSONObject object = new JSONObject();
        try {
            for (int i = 0; i + 1 < pairs.length; i += 2) {
                object.put(String.valueOf(pairs[i]), pairs[i + 1]);
            }
        } catch (Exception e) {
            throw new WikiException(WikiException.Kind.UNKNOWN, "参数构造失败", e);
        }
        return object;
    }

    /**
     * 请求云小编端点。
     *
     * <p>这些端点本质是网页版 {@code music.163.com/st/wiki} 的接口：实测 App 通道（EAPI）
     * 对它们会返回空响应，网页通道（WEAPI 加密 POST）才是稳定通道。因此先走 WEAPI，
     * 仅在其整体失败时用 EAPI 兜底，避免单一通道抖动导致整页空白。
     */
    private static JSONObject call(String path, JSONObject payload, Context context,
                                   Http.CancelToken token) throws WikiException {
        String cookie = SessionManager.cookie(context);
        if (cookie.isEmpty()) {
            throw new WikiException(WikiException.Kind.NO_CREDENTIAL, "请先登录网易云账号");
        }
        WikiException webError = null;
        try {
            return check(NetEaseWeApi.post(path, payload, cookie, token));
        } catch (WikiException e) {
            if (e.getKind() == WikiException.Kind.CANCELLED) {
                throw e;
            }
            webError = e;
        }
        try {
            return check(NetEaseEapi.post(path, payload, WikiPrefs.deviceId(context), cookie, token));
        } catch (WikiException e) {
            if (e.getKind() == WikiException.Kind.CANCELLED) {
                throw e;
            }
            throw webError == null ? e : webError;
        }
    }

    /**
     * 抽奖类端点与云小编其它接口相反：官方实现只走 App 通道（EAPI）。
     * 因此这里把顺序反过来，仅当 App 通道失败时才尝试网页通道。
     */
    private static JSONObject callEapiFirst(String path, JSONObject payload, Context context,
                                            Http.CancelToken token) throws WikiException {
        String cookie = SessionManager.cookie(context);
        if (cookie.isEmpty()) {
            throw new WikiException(WikiException.Kind.NO_CREDENTIAL, "请先登录网易云账号");
        }
        WikiException eapiError = null;
        try {
            return check(NetEaseEapi.post(path, payload, WikiPrefs.deviceId(context), cookie, token));
        } catch (WikiException e) {
            if (e.getKind() == WikiException.Kind.CANCELLED) {
                throw e;
            }
            eapiError = e;
        }
        try {
            return check(NetEaseWeApi.post(path, payload, cookie, token));
        } catch (WikiException e) {
            if (e.getKind() == WikiException.Kind.CANCELLED) {
                throw e;
            }
            throw eapiError == null ? e : eapiError;
        }
    }

    /** 统一把服务端业务码映射成异常；200 与 0 视为成功。 */
    private static JSONObject check(JSONObject root) throws WikiException {
        if (root == null) {
            throw new WikiException(WikiException.Kind.BAD_JSON, "响应体为空");
        }
        int code = root.optInt("code", 0);
        if (code == 200 || code == 0) {
            return root;
        }
        if (code == 301 || code == -462) {
            throw new WikiException(WikiException.Kind.NO_CREDENTIAL,
                    "登录状态已失效，请重新登录");
        }
        String message = root.optString("message", "");
        if (message.isEmpty()) {
            message = "请求失败（code=" + code + "）";
        }
        throw new WikiException(WikiException.Kind.EMPTY, message);
    }

    /** 以 GET + 查询参数请求网页接口（与 music.163.com/st/wiki 的调用形态一致）。 */
    private static JSONObject getJson(String path, JSONObject query, Context context,
                                      Http.CancelToken token) throws WikiException {
        String cookie = SessionManager.cookie(context);
        if (cookie.isEmpty()) {
            throw new WikiException(WikiException.Kind.NO_CREDENTIAL, "请先登录网易云账号");
        }
        StringBuilder sb = new StringBuilder();
        appendQuery(sb, "csrf_token", NetEaseWeApi.csrfToken(cookie));
        if (query != null) {
            java.util.Iterator<String> keys = query.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                appendQuery(sb, key, String.valueOf(query.opt(key)));
            }
        }
        String url = NetEaseWeApi.BASE_URL + path + (sb.length() > 0 ? "?" + sb : "");
        String body = Http.get(url, NetEaseWeApi.UA, NetEaseWeApi.REFERER,
                NetEaseWeApi.cookieHeader(cookie), token);
        return check(parseJson(body));
    }

    private static void appendQuery(StringBuilder sb, String key, String value) {
        if (key == null || key.isEmpty() || value == null || value.isEmpty()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append('&');
        }
        try {
            sb.append(java.net.URLEncoder.encode(key, "UTF-8")).append('=')
                    .append(java.net.URLEncoder.encode(value, "UTF-8"));
        } catch (Exception ignored) {
            // UTF-8 恒定可用，理论不会走到这里
        }
    }

    private static JSONObject parseJson(String body) throws WikiException {
        String text = body == null ? "" : body.trim();
        if (text.isEmpty()) {
            throw new WikiException(WikiException.Kind.BAD_JSON, "响应体为空");
        }
        try {
            return new JSONObject(text);
        } catch (Exception e) {
            throw new WikiException(WikiException.Kind.BAD_JSON, "响应不是合法 JSON", e);
        }
    }

    // ------------------------------------------------------------------
    // 解析（包级可见，供单元测试）
    // ------------------------------------------------------------------

    static Profile parseProfile(JSONObject root) {
        Profile profile = new Profile();
        JSONObject data = root == null ? null : root.optJSONObject("data");
        if (data == null) {
            return profile;
        }
        profile.level = firstInt(data, 0, "level");
        profile.starLevel = firstInt(data, 0, "starLevel");
        profile.levelDesc = data.optString("levelDesc", "");
        profile.nextLevel = data.optString("nextLevel", "");
        profile.totalPoints = firstInt(data, 0, "totalPoints");
        profile.todayPoints = firstInt(data, 0, "todayPoints");
        profile.availablePoints = firstInt(data, 0, "availablePoints");
        profile.needPoints = firstInt(data, 0, "needPoints");
        profile.rank = firstInt(data, 0, "rank", "ranking", "rankNo");
        profile.signDays = firstInt(data, 0, "signDays", "continueSignDays");
        profile.signPoints = firstInt(data, 0, "signPoints");
        profile.signed = data.optInt("signed", 0) == 1
                || data.optBoolean("signed", false)
                || data.optBoolean("todaySigned", false);
        profile.nickname = data.optString("name", data.optString("nickname", ""));
        profile.avatarUrl = data.optString("imgUrl", data.optString("avatarUrl", ""));
        return profile;
    }

    static List<PointRecord> parsePointFlow(JSONObject root) {
        List<PointRecord> records = new ArrayList<>();
        JSONArray array = findArray(root);
        if (array == null) {
            return records;
        }
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) {
                continue;
            }
            PointRecord record = new PointRecord();
            record.title = firstString(item, "", "title", "desc", "description", "reason", "name",
                    "taskName", "action");
            record.time = timeOf(item);
            record.createTime = firstLong(item, 0L, "createTime", "timestamp", "time");
            // 贡献条目的提交时间是毫秒，流水接口可能给秒：统一成毫秒再参与时间轴对照
            if (record.createTime > 0L && record.createTime < 100000000000L) {
                record.createTime *= 1000L;
            }
            record.points = firstInt(item, 0, "points", "score", "change", "point", "value",
                    "amount");
            // 「展开显示具体贡献 / 跳转贡献卡片」依赖这两个字段：
            // 有则记录，没有时保持卡片不可展开
            record.bizId = firstLong(item, 0L,
                    "bizId", "relatedId", "targetId", "resourceId", "objectId", "id");
            record.bizType = firstString(item, "",
                    "bizType", "targetType", "resourceType", "objectType", "type");
            if (record.title.isEmpty() && record.points == 0 && record.time.isEmpty()) {
                continue;
            }
            if (i == 0 && BuildConfig.DEBUG) {
                // 仅在调试包打印一次字段名，用来确认流水接口到底给了哪些关联信息
                StringBuilder keys = new StringBuilder();
                java.util.Iterator<String> it = item.keys();
                while (it.hasNext()) {
                    keys.append(it.next()).append(',');
                }
                android.util.Log.d("CloudEditor", "flow keys=" + keys);
            }
            records.add(record);
        }
        return records;
    }

    static RankPage parseRankPage(JSONObject root) {
        RankPage page = new RankPage();
        if (root == null) {
            return page;
        }
        JSONArray array = findArray(root);
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                RankEntry entry = parseRankEntry(array.optJSONObject(i));
                if (entry != null) {
                    if (entry.rank <= 0) {
                        entry.rank = page.items.size() + 1;
                    }
                    page.items.add(entry);
                }
            }
        }
        JSONObject data = root.optJSONObject("data");
        if (data != null) {
            page.currentUser = parseRankEntry(data.optJSONObject("currentUser"));
            page.updateTime = firstLong(data, 0L, "updateTime");
            page.total = firstInt(data, page.items.size(), "total", "totalCount", "count");
            page.hasMore = data.optBoolean("more", data.optBoolean("hasMore", false));
        }
        if (page.total <= 0) {
            page.total = page.items.size();
        }
        return page;
    }

    /** 荣誉榜单条记录：{rank, userId, userName, userImage, level, points}。 */
    private static RankEntry parseRankEntry(JSONObject item) {
        if (item == null) {
            return null;
        }
        RankEntry entry = new RankEntry();
        entry.rank = firstInt(item, 0, "rank", "ranking", "rankNo", "no", "index");
        entry.level = firstInt(item, 0, "level", "starLevel");
        entry.points = firstInt(item, 0, "points", "score", "devote", "todayPoints", "totalPoints");
        entry.userId = firstLong(item, 0L, "userId", "uid", "id");
        entry.nickname = firstString(item, "", "userName", "nickname", "name", "userNickname");
        entry.levelDesc = firstString(item, "", "levelDesc", "level", "levelName", "honor");
        entry.avatarUrl = normalizeImage(firstString(item, "", "userImage", "avatarUrl", "avatar",
                "imgUrl", "picUrl"));
        if (entry.nickname.isEmpty() && entry.userId <= 0L) {
            return null;
        }
        return entry;
    }

    /** 用户头像与封面一样需要 https：targetSdk 34 下明文 http 会被系统直接拦截。 */
    private static String normalizeImage(String url) {
        String value = url == null ? "" : url.trim();
        if (value.startsWith("http://") && value.contains(".126.net")) {
            return "https://" + value.substring("http://".length());
        }
        return value;
    }

    static VipState parseVip(JSONObject root) {
        VipState state = new VipState();
        JSONObject data = root == null ? null : root.optJSONObject("data");
        if (data == null) {
            return state;
        }
        state.status = firstInt(data, 0, "status", "vipStatus");
        state.threshold = firstInt(data, 50, "threshold");
        state.message = data.optString("message", data.optString("statusDesc", ""));
        state.title = data.optString("title", "");
        return state;
    }

    static ActivityState parseActivity(JSONObject root) {
        ActivityState state = new ActivityState();
        JSONArray array = findArray(root);
        if (array == null) {
            // 服务端当前返回的是单个活动对象：{activityId, count, goalCount, points, finished}
            JSONObject data = root == null ? null : root.optJSONObject("data");
            if (data != null && data.optInt("activityId", 0) > 0) {
                ActivityTask task = new ActivityTask();
                task.activityId = String.valueOf(data.optInt("activityId"));
                task.title = firstString(data, "", "title", "name", "activityName");
                task.points = firstInt(data, 0, "points", "score", "rewardPoints");
                task.count = firstInt(data, 0, "count", "finishedCount", "progress");
                task.goalCount = firstInt(data, 0, "goalCount", "goal", "target");
                task.claimed = data.has("finished") && !data.isNull("finished");
                state.tasks.add(task);
            }
            return state;
        }
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) {
                continue;
            }
            ActivityTask task = new ActivityTask();
            task.activityId = firstString(item, VIP_ACTIVITY_ID, "activityId", "id");
            task.title = firstString(item, "", "title", "name", "activityName", "desc");
            task.points = firstInt(item, 0, "points", "score", "rewardPoints");
            task.claimed = item.optInt("status", 0) == 2
                    || item.optBoolean("claimed", false)
                    || item.optBoolean("received", false);
            if (task.title.isEmpty()) {
                continue;
            }
            state.tasks.add(task);
        }
        return state;
    }

    /**
     * 抽奖响应解析。
     *
     * <p>服务端实际结构（2026-10 实测）：
     * <pre>
     * data: {
     *   drawPrizeInfoList: [{prizeTableId, prizeId, drawStatus, winCount}],
     *   prizeDetailInfoMap: { "8186002": {prizeName, prizeType, prizeImgList, ...} },
     *   noLotteryContent: null,
     *   restChance: 20
     * }
     * </pre>
     * 因此奖品名要从 prizeDetailInfoMap 里按 prizeTableId 回查；
     * 同时保留一些旧的直出字段作为兜底。
     */
    static LotteryResult parseLottery(JSONObject root) {
        LotteryResult result = new LotteryResult();
        JSONObject data = root == null ? null : root.optJSONObject("data");
        if (data == null) {
            JSONArray array = root == null ? null : root.optJSONArray("data");
            if (array != null && array.length() > 0) {
                data = array.optJSONObject(0);
            }
        }
        if (data == null) {
            if (root != null) {
                result.prizeName = firstString(root, "", "message", "msg");
            }
            return result;
        }

        result.remainChance = firstInt(data, -1, "restChance", "remainChance", "chance",
                "remainCount", "count");

        JSONObject detail = prizeDetailOf(data);
        if (detail != null) {
            result.prizeName = firstString(detail, "", "prizeName", "awardName", "productName",
                    "name", "title");
            result.prizeType = firstString(detail, "", "prizeType", "awardType", "productType",
                    "type");
            result.points = firstInt(detail, 0, "points", "integration", "point");
            if (result.points <= 0) {
                result.points = sentPointsOf(detail);
            }
        }
        if (result.prizeName.isEmpty()) {
            result.prizeName = firstString(data, "", "prizeName", "awardName", "noLotteryContent");
        }
        if (result.prizeName.isEmpty()) {
            result.prizeName = firstString(root, "", "message", "msg");
        }
        return result;
    }

    /** 从 prizeDetailInfoMap 中按中奖列表的第一条取详情；键缺失时退回遍历第一个有名字的奖品。 */
    private static JSONObject prizeDetailOf(JSONObject data) {
        JSONObject map = data.optJSONObject("prizeDetailInfoMap");
        if (map == null) {
            return null;
        }
        JSONArray list = data.optJSONArray("drawPrizeInfoList");
        if (list != null) {
            for (int i = 0; i < list.length(); i++) {
                JSONObject item = list.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                JSONObject detail = map.optJSONObject(item.optString("prizeTableId", ""));
                if (detail != null) {
                    return detail;
                }
            }
        }
        java.util.Iterator<String> keys = map.keys();
        while (keys.hasNext()) {
            JSONObject detail = map.optJSONObject(keys.next());
            if (detail != null && !firstString(detail, "", "prizeName", "name").isEmpty()) {
                return detail;
            }
        }
        return null;
    }

    /** 积分类奖品的实际发放数值在 interestCompose.refInterests[].sendNum。 */
    private static int sentPointsOf(JSONObject detail) {
        JSONObject compose = detail.optJSONObject("interestCompose");
        if (compose == null) {
            return 0;
        }
        JSONArray refs = compose.optJSONArray("refInterests");
        if (refs == null || refs.length() == 0) {
            return 0;
        }
        JSONObject first = refs.optJSONObject(0);
        return first == null ? 0 : first.optInt("sendNum", 0);
    }

    // ------------------------------------------------------------------
    // JSON 容错工具
    // ------------------------------------------------------------------

    /** 取响应中的列表：兼容 data 直接为数组，以及 data.records / data.list 等包装。 */
    private static JSONArray findArray(JSONObject root) {
        if (root == null) {
            return null;
        }
        JSONArray direct = root.optJSONArray("data");
        if (direct != null) {
            return direct;
        }
        JSONObject data = root.optJSONObject("data");
        if (data == null) {
            return root.optJSONArray("records");
        }
        String[] keys = {"records", "list", "rankList", "users", "items", "data", "result"};
        for (String key : keys) {
            JSONArray array = data.optJSONArray(key);
            if (array != null) {
                return array;
            }
        }
        return null;
    }

    /** 时间字段：兼容毫秒 / 秒时间戳与已格式化字符串。 */
    private static String timeOf(JSONObject item) {
        Object raw = item.opt("createTime");
        if (raw instanceof Number) {
            return formatMillis(((Number) raw).longValue());
        }
        String text = firstString(item, "", "createTime", "time", "createTimeStr", "date",
                "updateTime", "ts");
        if (text.matches("\\d{10,13}")) {
            try {
                return formatMillis(Long.parseLong(text));
            } catch (Exception ignored) {
                return text;
            }
        }
        return text;
    }

    private static String formatMillis(long value) {
        long millis = value < 100000000000L ? value * 1000L : value;
        java.text.SimpleDateFormat format =
                new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA);
        return format.format(new java.util.Date(millis));
    }

    private static int firstInt(JSONObject object, int fallback, String... keys) {
        for (String key : keys) {
            if (object.has(key)) {
                Object value = object.opt(key);
                if (value instanceof Number) {
                    return ((Number) value).intValue();
                }
                try {
                    return Integer.parseInt(String.valueOf(value).trim());
                } catch (Exception ignored) {
                    // 尝试下一个候选键
                }
            }
        }
        return fallback;
    }

    private static long firstLong(JSONObject object, long fallback, String... keys) {
        for (String key : keys) {
            if (object.has(key)) {
                Object value = object.opt(key);
                if (value instanceof Number) {
                    return ((Number) value).longValue();
                }
                try {
                    return Long.parseLong(String.valueOf(value).trim());
                } catch (Exception ignored) {
                    // 尝试下一个候选键
                }
            }
        }
        return fallback;
    }

    private static String firstString(JSONObject object, String fallback, String... keys) {
        for (String key : keys) {
            String value = object.optString(key, "");
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return fallback;
    }
}

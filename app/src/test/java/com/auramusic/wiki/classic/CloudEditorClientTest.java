package com.auramusic.wiki.classic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/** 云小编接口的容错解析：字段名按服务端实际返回的多套候选键取第一个可用值。 */
public class CloudEditorClientTest {

    @Test
    public void parsesProfileWithRankAndSignState() throws Exception {
        JSONObject root = new JSONObject("{\"code\":200,\"data\":{"
                + "\"id\":10001,\"name\":\"测试小编\",\"level\":4,\"starLevel\":2,"
                + "\"levelDesc\":\"资深云小编\",\"nextLevel\":\"金牌云小编\",\"needPoints\":120,"
                + "\"totalPoints\":880,\"todayPoints\":15,\"availablePoints\":300,"
                + "\"rank\":37,\"signed\":1,\"signDays\":6,\"signPoints\":5,"
                + "\"imgUrl\":\"https://p1.music.126.net/avatar.jpg\"}}");

        CloudEditorClient.Profile profile = CloudEditorClient.parseProfile(root);

        assertEquals(4, profile.level);
        assertEquals(2, profile.starLevel);
        assertEquals("资深云小编", profile.levelDesc);
        assertEquals("金牌云小编", profile.nextLevel);
        assertEquals(120, profile.needPoints);
        assertEquals(880, profile.totalPoints);
        assertEquals(15, profile.todayPoints);
        assertEquals(300, profile.availablePoints);
        assertEquals(37, profile.rank);
        assertTrue(profile.signed);
        assertEquals(5, profile.signPoints);
        assertEquals("测试小编", profile.nickname);
    }

    @Test
    public void profileDefaultsWhenNotLoggedIn() throws Exception {
        CloudEditorClient.Profile profile =
                CloudEditorClient.parseProfile(new JSONObject("{\"code\":200,\"data\":{}}"));

        assertEquals(0, profile.rank);
        assertEquals(0, profile.totalPoints);
        assertFalse(profile.signed);
        assertEquals("", profile.levelDesc);
    }

    @Test
    public void parsesRankPageWithRecordsWrapper() throws Exception {
        JSONObject root = new JSONObject("{\"code\":200,\"data\":{"
                + "\"total\":2,\"more\":true,\"records\":["
                + "{\"rank\":1,\"nickname\":\"甲\",\"level\":8,\"levelDesc\":\"金牌\",\"points\":960,"
                + "\"userId\":1001,\"avatarUrl\":\"https://p1.music.126.net/a.jpg\"},"
                + "{\"userId\":1002,\"name\":\"乙\",\"score\":540}]}}");

        CloudEditorClient.RankPage page = CloudEditorClient.parseRankPage(root);

        assertEquals(2, page.items.size());
        assertEquals(1, page.items.get(0).rank);
        assertEquals("甲", page.items.get(0).nickname);
        assertEquals(960, page.items.get(0).points);
        assertEquals(1001L, page.items.get(0).userId);
        // 第二条缺 rank，按位置补序号；昵称与积分取自候选键
        assertEquals(2, page.items.get(1).rank);
        assertEquals("乙", page.items.get(1).nickname);
        assertEquals(540, page.items.get(1).points);
        assertTrue(page.hasMore);
    }

    @Test
    public void parsesRankPageWhenDataIsArray() throws Exception {
        JSONObject root = new JSONObject("{\"code\":200,\"data\":["
                + "{\"rank\":3,\"nickname\":\"丙\",\"points\":210}]}");

        CloudEditorClient.RankPage page = CloudEditorClient.parseRankPage(root);

        assertEquals(1, page.items.size());
        assertEquals("丙", page.items.get(0).nickname);
        assertFalse(page.hasMore);
    }

    @Test
    public void parsesPointFlowRecords() throws Exception {
        JSONObject root = new JSONObject("{\"code\":200,\"data\":{\"records\":["
                + "{\"title\":\"歌曲审核通过\",\"points\":10,\"time\":\"2026-10-24 20:11\"},"
                + "{\"desc\":\"每日签到\",\"score\":5}]}}");

        java.util.List<CloudEditorClient.PointRecord> records =
                CloudEditorClient.parsePointFlow(root);

        assertEquals(2, records.size());
        assertEquals("歌曲审核通过", records.get(0).title);
        assertEquals(10, records.get(0).points);
        assertEquals("2026-10-24 20:11", records.get(0).time);
        assertEquals("每日签到", records.get(1).title);
        assertEquals(5, records.get(1).points);
    }

    @Test
    public void parsesVipStatus() throws Exception {
        JSONObject root = new JSONObject("{\"code\":200,\"data\":{\"status\":30}}");

        CloudEditorClient.VipState state = CloudEditorClient.parseVip(root);

        assertEquals(30, state.status);
    }

    @Test
    public void parsesActivityTasks() throws Exception {
        JSONObject root = new JSONObject("{\"code\":200,\"data\":["
                + "{\"activityId\":\"5001\",\"title\":\"审核满 20 首\",\"points\":30,\"status\":2},"
                + "{\"id\":\"5002\",\"name\":\"连续签到 7 天\",\"rewardPoints\":50}]}");

        CloudEditorClient.ActivityState state = CloudEditorClient.parseActivity(root);

        assertEquals(2, state.tasks.size());
        assertEquals("5001", state.tasks.get(0).activityId);
        assertTrue(state.tasks.get(0).claimed);
        assertEquals("5002", state.tasks.get(1).activityId);
        assertEquals(50, state.tasks.get(1).points);
        assertFalse(state.tasks.get(1).claimed);
    }

    @Test
    public void parsesIntegrationRecordsWithReasonAndAmount() throws Exception {
        JSONObject root = new JSONObject("{\"code\":200,\"data\":{\"records\":["
                + "{\"userId\":574571544,\"reason\":\"百科编辑审核通过\",\"amount\":18,"
                + "\"createTime\":1792899056582}]}}");

        java.util.List<CloudEditorClient.PointRecord> records =
                CloudEditorClient.parsePointFlow(root);

        assertEquals(1, records.size());
        assertEquals("百科编辑审核通过", records.get(0).title);
        assertEquals(18, records.get(0).points);
        assertTrue(records.get(0).time.startsWith("2026-10-2"));
    }

    @Test
    public void parsesSingleActivityObject() throws Exception {
        JSONObject root = new JSONObject("{\"code\":200,\"data\":{\"activityId\":5001,"
                + "\"type\":1,\"count\":12,\"goalCount\":50,\"points\":66,\"finished\":null}}");

        CloudEditorClient.ActivityState state = CloudEditorClient.parseActivity(root);

        assertEquals(1, state.tasks.size());
        assertEquals("5001", state.tasks.get(0).activityId);
        assertEquals(12, state.tasks.get(0).count);
        assertEquals(50, state.tasks.get(0).goalCount);
        assertEquals(66, state.tasks.get(0).points);
        assertFalse(state.tasks.get(0).claimed);
    }

    @Test
    public void parsesHonorRollWithCurrentUserAndUpdateTime() throws Exception {
        JSONObject root = new JSONObject("{\"code\":200,\"data\":{"
                + "\"updateTime\":1792899056582,"
                + "\"currentUser\":{\"rank\":88,\"userName\":\"我\",\"points\":640},"
                + "\"records\":[{\"rank\":1,\"userId\":9001,\"userName\":\"CharlotteCC_\","
                + "\"userImage\":\"http://p3.music.126.net/avatar.jpg\",\"level\":17,"
                + "\"points\":21400}]}}");

        CloudEditorClient.RankPage page = CloudEditorClient.parseRankPage(root);

        assertEquals(1, page.items.size());
        assertEquals("CharlotteCC_", page.items.get(0).nickname);
        assertEquals(21400, page.items.get(0).points);
        // 明文 http 头像在 targetSdk 34 下会被拦截，解析阶段统一升级为 https
        assertEquals("https://p3.music.126.net/avatar.jpg", page.items.get(0).avatarUrl);
        assertEquals(88, page.currentUser.rank);
        assertEquals(1792899056582L, page.updateTime);
        assertEquals(1, page.total);
    }

    @Test
    public void parsesLotteryResultWithRemainChance() throws Exception {
        JSONObject root = new JSONObject("{\"code\":200,\"data\":{"
                + "\"drawPrizeInfoList\":[{\"order\":1,\"prizeTableId\":8186002,"
                + "\"prizeId\":1155502,\"drawStatus\":1,\"winCount\":1}],"
                + "\"prizeDetailInfoMap\":{\"8186002\":{\"prizeName\":\"云小编100积分礼包\","
                + "\"prizeType\":11,\"interestCompose\":{\"refInterests\":[{\"sendNum\":100}]}}},"
                + "\"noLotteryContent\":null,\"restChance\":20}}");

        CloudEditorClient.LotteryResult result = CloudEditorClient.parseLottery(root);

        assertEquals("云小编100积分礼包", result.prizeName);
        assertEquals("11", result.prizeType);
        // 积分类奖品的发放值来自 interestCompose.refInterests[].sendNum
        assertEquals(100, result.points);
        assertEquals(20, result.remainChance);
    }
}

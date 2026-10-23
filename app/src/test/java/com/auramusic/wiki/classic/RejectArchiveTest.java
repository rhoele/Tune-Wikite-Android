package com.auramusic.wiki.classic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * 未通过原因解析的单测。
 *
 * <p>样本取自「曲库小学妹」真实私信。此前私信接口的参数名写错（{@code uid} 应为
 * {@code userId}），整条链路拿不到任何消息；这里固定住解析契约，避免后续改动
 * 让归档再次悄悄变空。
 */
public class RejectArchiveTest {

    private static RejectArchive.Entry parse(String text) {
        return RejectArchive.parse(new NetEaseMsgClient.Notice(text, 1792480000000L));
    }

    @Test
    public void parsesStandardFormat() {
        RejectArchive.Entry entry = parse("您提交的专辑《Take It Easy》未通过审核，感谢您的贡献！"
                + "如有疑问请咨询@曲库小学妹。拒绝原因：经核实线上已有此专辑，请勿重复上传。");
        assertNotNull(entry);
        assertEquals("Take It Easy", entry.album);
        assertEquals("经核实线上已有此专辑，请勿重复上传。", entry.reason);
    }

    @Test
    public void parsesHalfWidthColon() {
        RejectArchive.Entry entry = parse("您提交的专辑《VOID》未通过审核。"
                + "拒绝原因: 提供资料与添加内容不一致。");
        assertNotNull(entry);
        assertEquals("VOID", entry.album);
        assertEquals("提供资料与添加内容不一致。", entry.reason);
    }

    @Test
    public void parsesTailSentence() {
        RejectArchive.Entry entry = parse("您提交的专辑《verglas》未通过审核，"
                + "如有疑问请咨询@曲库小学妹。歌名过长");
        assertNotNull(entry);
        assertEquals("verglas", entry.album);
        assertEquals("歌名过长", entry.reason);
    }

    @Test
    public void dropsNonAuditMessage() {
        assertNull(parse("感谢您的支持，专辑《X》已经通过审核，"));
    }

    @Test
    public void dropsEmptyReason() {
        assertNull(parse("您提交的专辑《X》未通过审核。拒绝原因：null"));
        assertNull(parse("您提交的专辑《X》未通过审核。拒绝原因："));
    }

    @Test
    public void parsesAlbumTargetWithoutBookQuotes() {
        RejectArchive.Entry entry = parse("您对专辑RUST的纠错修改（专辑艺人）因有误差或需以官方信息为准"
                + "等原因未审核通过。拒绝原因：暂以官方传输信息为准。");
        assertNotNull(entry);
        assertEquals("RUST", entry.album);
        assertEquals("RUST", RejectArchive.jumpTarget(entry));
    }

    @Test
    public void keepsNestedBookQuotesInAlbumName() {
        RejectArchive.Entry entry = parse("您提交的专辑《DOUDOU《忘记了也没关系》Orchestral House Remix》"
                + "未被审核通过，依然感谢您的贡献，如有疑问请咨询@曲库小学妹。"
                + "拒绝原因：由于相关版权原因，此专辑不予传输。");
        assertNotNull(entry);
        assertEquals("DOUDOU《忘记了也没关系》Orchestral House Remix", entry.album);
        assertEquals("DOUDOU《忘记了也没关系》Orchestral House Remix",
                RejectArchive.displayName(entry));
        assertEquals("DOUDOU《忘记了也没关系》Orchestral House Remix",
                RejectArchive.jumpTarget(entry));
        assertEquals(RejectArchive.CAT_ALBUM, entry.category);
    }

    @Test
    public void displayNameRepairsLegacyTruncatedAlbum() {
        // 老归档把名称截断成了第一个书名号；展示与跳转应从原文重新推导
        RejectArchive.Entry legacy = new RejectArchive.Entry("DOUDOU《忘记了也没关系",
                "由于相关版权原因，此专辑不予传输。",
                "您提交的专辑《DOUDOU《忘记了也没关系》Orchestral House Remix》未被审核通过，"
                        + "拒绝原因：由于相关版权原因，此专辑不予传输。",
                1792480000000L);
        assertEquals("DOUDOU《忘记了也没关系》Orchestral House Remix",
                RejectArchive.displayName(legacy));
    }

    @Test
    public void artistNoticeShowsTargetInsteadOfAlbumPlaceholder() {
        RejectArchive.Entry entry = parse("您对艺人NyaniKore的纠错修改（艺人图片、艺人类型）"
                + "因有误差或需以官方信息为准等原因未审核通过。拒绝原因：暂以官方传输信息为准。");
        assertNotNull(entry);
        assertEquals("", entry.album);
        assertEquals("NyaniKore", RejectArchive.displayName(entry));
        assertEquals(RejectArchive.CAT_FIX_ARTIST, entry.category);
        // 艺人纠错同样可以定位到艺人类贡献卡片
        assertEquals("NyaniKore", RejectArchive.jumpTarget(entry));
    }

    @Test
    public void lyricAndTranslationCorrectionsHaveNoContributionTarget() {
        RejectArchive.Entry lyric = parse("您对歌词《X》的纠错修改（歌词内容）"
                + "因有误差或需以官方信息为准等原因未审核通过。拒绝原因：暂以官方传输信息为准。");
        assertNotNull(lyric);
        assertEquals(RejectArchive.CAT_FIX_LYRIC, lyric.category);
        assertEquals("", RejectArchive.jumpTarget(lyric));

        RejectArchive.Entry translation = parse("您对翻译《X》的纠错修改（翻译内容）"
                + "因有误差或需以官方信息为准等原因未审核通过。拒绝原因：暂以官方传输信息为准。");
        assertNotNull(translation);
        assertEquals(RejectArchive.CAT_FIX_TRANSLATION, translation.category);
        assertEquals("", RejectArchive.jumpTarget(translation));
    }

    @Test
    public void parsesLostCorrectionNotice() {
        RejectArchive.Entry entry = parse("您纠错的《Magic Mirror》由于：其他用户的纠错较为贴切/在你之前上传，"
                + "因此没有被选用，很遗憾不能获得积分，依然感谢您的贡献。");
        assertNotNull(entry);
        assertEquals("其他用户的纠错较为贴切/在你之前上传", entry.reason);
        assertEquals(RejectArchive.CAT_FIX, entry.category);
        assertEquals("Magic Mirror", entry.album);
    }

    @Test
    public void cardCopyKeepsNoticeStructureWithBlankAlbum() {
        String raw = "您提交的专辑《Take It Easy》未被审核通过，依然感谢您的贡献，"
                + "如有疑问请咨询@曲库小学妹。拒绝原因：经核实线上已有此专辑，请勿重复上传。";
        assertEquals("您提交的专辑《》未被审核通过，依然感谢您的贡献，"
                        + "如有疑问请咨询@曲库小学妹。拒绝原因：经核实线上已有此专辑，请勿重复上传。",
                RejectArchive.rawWithoutAlbum(raw));
        assertEquals("", RejectArchive.rawWithoutAlbum(null));
    }

    @Test
    public void cardCopyBlanksCorrectionTargetWithoutBookQuotes() {
        // 纠错通知的对象名不带书名号：复制卡片时同样要清空，不能残留第一条的具体艺人 / 专辑
        assertEquals("您对艺人的纠错修改（艺人图片、艺人类型）因有误差或需以官方信息为准"
                        + "等原因未审核通过。拒绝原因：暂以官方传输信息为准。",
                RejectArchive.rawWithoutAlbum("您对艺人NyaniKore的纠错修改（艺人图片、艺人类型）"
                        + "因有误差或需以官方信息为准等原因未审核通过。拒绝原因：暂以官方传输信息为准。"));
        assertEquals("您对专辑的纠错修改（专辑歌曲列表）因有误差或需以官方信息为准"
                        + "等原因未审核通过，感谢您贡献。添加曲目已在曲库中哦",
                RejectArchive.rawWithoutAlbum("您对专辑The Noodle Effect的纠错修改（专辑歌曲列表）"
                        + "因有误差或需以官方信息为准等原因未审核通过，感谢您贡献。添加曲目已在曲库中哦"));
    }

    @Test
    public void cardCopyBlanksNestedBookQuotedAlbum() {
        assertEquals("您提交的专辑《》未被审核通过，依然感谢您的贡献。拒绝原因：由于相关版权原因，此专辑不予传输。",
                RejectArchive.rawWithoutAlbum("您提交的专辑《DOUDOU《忘记了也没关系》Orchestral House Remix》"
                        + "未被审核通过，依然感谢您的贡献。拒绝原因：由于相关版权原因，此专辑不予传输。"));
    }

    @Test
    public void lyricAndTranslationFailures() {
        RejectArchive.Entry lyric = parse("你上传的《CHANCE!》的歌词由于其他用户的歌词在你之前上传"
                + "未通过审核，很遗憾不能获得积分。");
        assertNotNull(lyric);
        assertEquals("CHANCE!", lyric.album);
        assertEquals("其他用户的歌词在你之前上传", lyric.reason);
        assertEquals(RejectArchive.CAT_LYRIC, lyric.category);

        RejectArchive.Entry translation = parse("你上传的《GHOST》的翻译由于其他用户的歌词翻译在你之前上传，"
                + "未通过审核，很遗憾不能获得积分。");
        assertNotNull(translation);
        assertEquals("其他用户的歌词翻译在你之前上传", translation.reason);
        assertEquals(RejectArchive.CAT_TRANSLATION, translation.category);
    }

    @Test
    public void archivesCorrectionWithNewTailWording() {
        RejectArchive.Entry entry = parse("您对专辑The Noodle Effect的纠错修改（专辑歌曲列表）"
                + "因有误差或需以官方信息为准等原因未审核通过，如有疑问请私信联系@曲库小学妹，"
                + "感谢您贡献。添加曲目已在曲库中哦");
        assertNotNull(entry);
        assertEquals("The Noodle Effect", entry.album);
        assertEquals("添加曲目已在曲库中哦", entry.reason);
        assertEquals(RejectArchive.CAT_FIX_ALBUM, entry.category);
    }

    @Test
    public void fallsBackToGenericReasonForCorrection() {
        RejectArchive.Entry entry = parse("您对艺人H3RØ的纠错修改（艺人图片、艺人类型、艺人简介）"
                + "因有误差或需以官方信息为准等原因未审核通过，如有疑问请私信联系@曲库小学妹，感谢您贡献。");
        assertNotNull(entry);
        assertEquals("资料有误差或需以官方信息为准", entry.reason);
    }

    @Test
    public void splitsMixedReasonIntoOneEntryPerItem() {
        java.util.List<String> parts = RejectArchive.splitReason(
                "提供资料与添加内容（歌曲列表/歌曲名/封面）不一致，建议仔细比对重新上传。");
        assertEquals(3, parts.size());
        assertEquals("提供资料与添加内容（歌曲列表）不一致，建议仔细比对重新上传。", parts.get(0));
        assertEquals("提供资料与添加内容（封面）不一致，建议仔细比对重新上传。", parts.get(2));
        // 单项括号不拆
        assertEquals(1, RejectArchive.splitReason("提供资料与添加内容（歌手）不一致。").size());
    }
}

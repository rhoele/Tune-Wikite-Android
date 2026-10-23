package com.auramusic.wiki.classic;

import android.os.Bundle;
import android.view.View;

import androidx.annotation.Nullable;

import com.google.android.material.materialswitch.MaterialSwitch;

/**
 * 设置二级页：显示（需求 1 / 3 / 5）。
 *
 * <p>承载「我的贡献」页的可视化开关：
 * <ul>
 *   <li>贡献卡片封面（需求 4）：封面能否读到在不同歌单 / 分类下并不一致，
 *       除自动策略（全部读不到时全局隐藏图标）外再给用户一个显式开关；</li>
 *   <li>接口通道卡片 / 账号卡片（需求 3）：整块卡片可隐藏，隐藏后由
 *       {@link MainActivity} 把下方内容整体上移填充空白（需求 6）；</li>
 *   <li>贡献搜索模块（需求 5）：关键词与日期范围搜索区的显隐。</li>
 * </ul>
 * 所有开关改动即时落库并持久化，回到「我的贡献」页面后自动重新应用。
 */
public class DisplaySettingsActivity extends BaseActivity {

    private MaterialSwitch switchShowCovers;
    private MaterialSwitch switchShowTransportCard;
    private MaterialSwitch switchShowAccountCard;
    private MaterialSwitch switchShowSearch;
    /** 搜索卡片布局模式行（普通 / 精简单行） */
    private View rowSearchLayout;

    /** 回填期间抑制监听回调，避免把初始化当成用户操作 */
    private boolean binding;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings_display);
        bindToolbar(R.string.display_title);

        switchShowCovers = switchOf(R.id.switch_show_covers);
        switchShowTransportCard = switchOf(R.id.switch_show_transport_card);
        switchShowAccountCard = switchOf(R.id.switch_show_account_card);
        switchShowSearch = switchOf(R.id.switch_show_search);

        binding = true;
        setChecked(switchShowCovers, WikiPrefs.showContributionCovers(this));
        setChecked(switchShowTransportCard, WikiPrefs.showTransportCard(this));
        setChecked(switchShowAccountCard, WikiPrefs.showAccountCard(this));
        setChecked(switchShowSearch, WikiPrefs.showContributionSearch(this));
        binding = false;

        bind(switchShowCovers, WikiPrefs::setShowContributionCovers);
        bind(switchShowTransportCard, WikiPrefs::setShowTransportCard);
        bind(switchShowAccountCard, WikiPrefs::setShowAccountCard);
        bind(switchShowSearch, WikiPrefs::setShowContributionSearch);

        rowSearchLayout = SettingsRows.addOptionRow(getLayoutInflater(),
                findViewById(R.id.list_search_layout),
                R.string.settings_search_layout_title,
                R.string.settings_search_layout_desc,
                this::searchLayoutValue,
                this::pickSearchLayout,
                R.drawable.ic_tune, Md3ListItemView.TILE_SECONDARY);

        // 按「设置项样式」偏好重排：独立卡片模式下每行单独成卡，分组卡透明化
        SettingsRows.applyRowStyle(findViewById(R.id.group_display_list));
        SettingsRows.applyRowStyle(findViewById(R.id.group_display_cards));
        SettingsRows.applyRowStyle(findViewById(R.id.list_search_layout));
    }

    private CharSequence searchLayoutValue() {
        return getString(WikiPrefs.compactSearchLayout(this)
                ? R.string.settings_search_layout_compact
                : R.string.settings_search_layout_normal);
    }

    /** 唤起原生单选子菜单选择搜索卡片布局。 */
    private void pickSearchLayout() {
        boolean compact = WikiPrefs.compactSearchLayout(this);
        SettingsRows.showSingleChoice(this, R.string.settings_search_layout_title,
                new CharSequence[]{
                        getString(R.string.settings_search_layout_normal),
                        getString(R.string.settings_search_layout_compact)},
                compact ? 1 : 0,
                which -> {
                    boolean target = which == 1;
                    if (target == compact) {
                        return;
                    }
                    WikiPrefs.setCompactSearchLayout(this, target);
                    SettingsRows.refreshValue(rowSearchLayout, this::searchLayoutValue);
                });
    }

    /** 从列表行里取出尾部开关（Stage 2 起开关由 Md3ListItemView 统一承载）。 */
    private MaterialSwitch switchOf(int rowId) {
        View row = findViewById(rowId);
        return row instanceof Md3ListItemView ? ((Md3ListItemView) row).getSwitch() : null;
    }

    private static void setChecked(MaterialSwitch button, boolean checked) {
        if (button != null) {
            button.setChecked(checked);
        }
    }

    /** 统一的开关绑定：初始化期间不回写，用户操作时先出触感再持久化。 */
    private void bind(MaterialSwitch button, BoolWriter writer) {
        if (button == null) {
            return;
        }
        button.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (binding) {
                return;
            }
            HapticsManager.toggle(buttonView);
            writer.write(this, isChecked);
        });
    }

    private interface BoolWriter {
        void write(DisplaySettingsActivity activity, boolean value);
    }
}

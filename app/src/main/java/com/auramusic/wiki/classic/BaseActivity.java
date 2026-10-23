package com.auramusic.wiki.classic;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/**
 * 全应用界面基类，统一承载 MD3 外观设置的下发与沉浸式系统栏：
 *   - 字体缩放：{@link #attachBaseContext} 阶段覆写 Configuration.fontScale，
 *     所有 sp 字号（含 MD3 textAppearance 令牌）随设置统一切换；
 *   - 调色板：onCreate 中 {@code setTheme} 选择对应主题分支，改色后重建即生效；
 *   - 实时性：调色板 / 字体变更会推进外观修订号，{@link #onResume} 检测到落后即
 *     recreate 自身，使返回栈中已存在的页面也同步刷新，而不仅是当前操作页；
 *   - 沉浸式状态栏（需求 7）：窗口不再为系统栏让位，页面自身背景延伸到状态栏与
 *     导航栏之下，再按系统栏高度为其内容留出安全内边距，从而使状态栏与页面头部
 *     视觉连成一片（两者同为 {@code colorSurface}）；
 *   - 明暗图标：状态栏 / 导航栏图标颜色按当前生效的深浅色模式切换，避免深色主题下
 *     出现「黑底黑字」不可读的系统栏。
 */
public abstract class BaseActivity extends AppCompatActivity {

    /** 本实例已应用的外观修订号，用于识别外观是否在别处被改过 */
    private int appliedRevision = -1;

    /** 是否已开启沉浸式（edge-to-edge）；未开启时不做系统栏内边距补偿 */
    private boolean edgeToEdge;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(AppearanceManager.wrap(newBase));
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        setTheme(AppearanceManager.themeRes(this));
        super.onCreate(savedInstanceState);
        appliedRevision = AppearanceManager.revision(this);
        applyEdgeToEdge();
    }

    // ------------------------------------------------------------------
    // 沉浸式系统栏（需求 7）
    // ------------------------------------------------------------------

    /**
     * 开启 edge-to-edge 并把系统栏设为透明。
     *
     * <p>系统栏透明后，状态栏区域透出的是窗口背景（{@code md_background}），
     * 它与各页面根布局使用的 {@code colorSurface} 取值一致，因此顶栏与状态栏之间
     * 不再有颜色断层；内容的安全区由 {@link #installSystemBarInsets()} 逐页补偿。
     */
    private void applyEdgeToEdge() {
        Window window = getWindow();
        if (window == null) {
            return;
        }
        WindowCompat.setDecorFitsSystemWindows(window, false);
        edgeToEdge = true;

        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 关闭系统为透明栏自动添加的对比度蒙层，避免状态栏区域泛灰
            window.setStatusBarContrastEnforced(false);
            window.setNavigationBarContrastEnforced(false);
        }
        applySystemBarAppearance();
    }

    /** 按当前生效的深浅色模式设置系统栏图标明暗。 */
    private void applySystemBarAppearance() {
        Window window = getWindow();
        if (window == null) {
            return;
        }
        boolean light = isNightMode();
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(window, window.getDecorView());
        controller.setAppearanceLightStatusBars(!light);
        controller.setAppearanceLightNavigationBars(!light);
    }

    /** 当前生效的深浅色模式（已包含用户在「外观」中的主题选择）。 */
    private boolean isNightMode() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    @Override
    public void setContentView(int layoutResID) {
        super.setContentView(layoutResID);
        installSystemBarInsets();
    }

    @Override
    public void setContentView(View view) {
        super.setContentView(view);
        installSystemBarInsets();
    }

    /**
     * 是否由页面自行承接底部（导航栏 / 输入法）内边距。
     *
     * <p>默认返回 {@code false}，即由根布局统一让出导航栏与输入法的高度。
     * 存在<b>悬浮覆盖式底栏</b>的页面应覆写为 {@code true}：此时基类只补偿状态栏高度，
     * 底部交给页面按「覆盖层高度 + 输入法高度」分别下发给内容区与覆盖层，
     * 否则输入法弹出时会把覆盖层一起顶起（表现为底栏突然变高并遮挡页面）。
     */
    protected boolean handlesBottomInsets() {
        return false;
    }

    /**
     * 给页面根布局补上系统栏高度的内边距：顶部让出状态栏，底部让出导航栏与输入法。
     *
     * <p>顶部只让位而背景仍延伸到状态栏之下，故视觉上状态栏与顶栏融为一体；
     * 底部取「导航栏 / 输入法」中的较大值，保证软键盘弹出时内容不被遮挡
     * （edge-to-edge 下窗口不再随输入法自动缩小）。
     *
     * <p>输入法内边距仅在 Android 11（API 30）及以上补偿：更低版本的窗口仍会随
     * 输入法自动缩小，重复补偿会导致内容被顶出屏幕。
     */
    private void installSystemBarInsets() {
        if (!edgeToEdge) {
            return;
        }
        View decorContent = findViewById(android.R.id.content);
        if (!(decorContent instanceof ViewGroup)) {
            return;
        }
        ViewGroup group = (ViewGroup) decorContent;
        if (group.getChildCount() == 0) {
            return;
        }
        final View content = group.getChildAt(0);
        if (content == null) {
            return;
        }
        final int start = content.getPaddingStart();
        final int top = content.getPaddingTop();
        final int end = content.getPaddingEnd();
        final int bottom = content.getPaddingBottom();
        final boolean bottomByPage = handlesBottomInsets();
        final boolean imeInsetAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;
        ViewCompat.setOnApplyWindowInsetsListener(content, (view, windowInsets) -> {
            // 同时纳入刘海 / 挖孔（displayCutout）：横屏时挖孔位于侧边或顶部，
            // 只取 systemBars 会让内容被切成不可读的窄条。
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            int extraBottom = 0;
            if (!bottomByPage) {
                int ime = imeInsetAvailable
                        ? windowInsets.getInsets(WindowInsetsCompat.Type.ime()).bottom : 0;
                extraBottom = Math.max(bars.bottom, ime);
            }
            view.setPaddingRelative(start, top + bars.top, end, bottom + extraBottom);
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(content);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            // 从系统 UI（如设置里的深色模式开关）返回时重新校准图标明暗
            applySystemBarAppearance();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        int revision = AppearanceManager.revision(this);
        if (revision != appliedRevision) {
            appliedRevision = revision;
            recreate();
            return;
        }
        // 「彩色图标底托」不推进外观修订号（避免每改一个开关就整栈重建），
        // 因此回到任意页面时都要就地刷新一遍底托，否则会残留上一次的彩色底盘。
        refreshIconTiles(findViewById(android.R.id.content));
    }

    /** 递归刷新页面内的 {@link Md3ListItemView} 底托（跟随「彩色图标底托」偏好）。 */
    private static void refreshIconTiles(View view) {
        if (view instanceof Md3ListItemView) {
            ((Md3ListItemView) view).refreshLeadingTile();
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                refreshIconTiles(group.getChildAt(i));
            }
        }
    }

    /**
     * 绑定统一顶栏：返回箭头 + 标题。
     * 箭头尺寸 24dp 且热区由 Toolbar 的 navigationIcon 保证 ≥48dp；
     * 点击返回时按「单击」场景触发系统触感反馈。
     */
    protected void bindToolbar(int titleRes) {
        Toolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar == null) {
            return;
        }
        setSupportActionBar(toolbar);
        toolbar.setTitle(titleRes);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(false);
            getSupportActionBar().setTitle(titleRes);
        }
        // setSupportActionBar 会按「非 Up 导航」清空 XML 里的 navigationIcon，
        // 这里显式补回返回箭头，否则二级页只剩文字标题。
        toolbar.setNavigationIcon(R.drawable.ic_back);
        toolbar.setNavigationOnClickListener(v -> {
            HapticsManager.tap(v);
            finish();
        });
    }
}

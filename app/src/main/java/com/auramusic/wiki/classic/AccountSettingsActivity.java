package com.auramusic.wiki.classic;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.Nullable;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * 设置二级页：账号与凭据（需求 2 / 需求 4）。
 *
 *   - 展示当前登录状态（昵称 + 用户 ID），一键进入登录页或退出登录；
 *   - Cookie 的手动输入与校验只在登录页的「Cookie 登录」标签页保留一份，
 *     这里只提供跳转入口，避免两处输入框状态不一致、互相覆盖。
 *
 * <p>Stage 14：页面结构改为与其他二级页一致的分组列表 ——
 * 账号状态是一行标准的「图标底托 + 标题 + 辅助说明 + chevron」，
 * 不再是一段平铺文字加两个通栏按钮。
 */
public class AccountSettingsActivity extends BaseActivity {

    private Md3ListItemView rowAccount;
    private View rowLogout;
    /** 进入本页时的登录状态：用于识别「从登录页返回后刚登录成功」并给出 Snackbar */
    private boolean wasLoggedIn;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings_account);
        bindToolbar(R.string.settings_group_account);

        rowAccount = findViewById(R.id.row_account);
        rowLogout = findViewById(R.id.row_logout);

        rowAccount.setOnClickListener(v -> {
            HapticsManager.tap(v);
            startActivity(new Intent(this, LoginActivity.class));
        });
        rowLogout.setOnClickListener(v -> {
            HapticsManager.tap(v);
            confirmLogout();
        });

        // 按「设置项样式」偏好重排：独立卡片模式下每行单独成卡，分组卡透明化
        SettingsRows.applyRowStyle(findViewById(R.id.group_account));
        wasLoggedIn = SessionManager.isLoggedIn(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshState();
        // 从登录页返回：本页不接收 result，只能比较前后登录态来给出成功提示
        boolean loggedIn = SessionManager.isLoggedIn(this);
        if (loggedIn && !wasLoggedIn) {
            Feedback.show(this, R.string.login_success);
        }
        wasLoggedIn = loggedIn;
    }

    private void refreshState() {
        boolean loggedIn = SessionManager.isLoggedIn(this);
        if (loggedIn) {
            String name = SessionManager.displayName(this);
            if (name.isEmpty()) {
                name = getString(R.string.login_account_unknown);
            }
            rowAccount.setTitle(name);
            rowAccount.setSupporting(getString(R.string.settings_account_id,
                    SessionManager.userId(this)));
        } else {
            rowAccount.setTitle(getString(R.string.settings_account_logged_out_title));
            rowAccount.setSupporting(getString(R.string.settings_account_logged_out));
        }
        SettingsRows.setRowEnabled(rowLogout, loggedIn);
    }

    private void confirmLogout() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.login_logout_title)
                .setMessage(R.string.login_logout_msg)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.login_logout, (dialog, which) -> {
                    // 主动退出：一并清掉该账号的审核收件箱与未通过原因归档，
                    // 避免下一位登录者看到上一位账号的数据
                    SessionManager.clearAccountData(this);
                    SessionManager.clear(this);
                    AuditPollingService.stop(this);
                    refreshState();
                    Feedback.show(AccountSettingsActivity.this, R.string.login_logout_done);
                })
                .show();
    }
}

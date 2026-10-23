package com.auramusic.wiki.classic;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationManagerCompat;

import com.google.android.material.color.MaterialColors;

/**
 * 设置二级页：通知（需求 4）。
 * 实时检测系统通知权限、支持发送测试通知，并在权限关闭时一键跳转系统通知管理页。
 *
 * <p>Stage 14：权限状态与两个操作改为分组列表行，取值用语义色区分开启 / 关闭。
 */
public class NotificationSettingsActivity extends BaseActivity {

    private Md3ListItemView rowPermission;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings_notification);
        bindToolbar(R.string.settings_group_notification);

        rowPermission = findViewById(R.id.row_permission);
        findViewById(R.id.row_test_notification).setOnClickListener(v -> {
            HapticsManager.tap(v);
            sendTestNotification();
        });
        findViewById(R.id.row_system_settings).setOnClickListener(v -> {
            HapticsManager.tap(v);
            openSystemSettings();
        });

        updatePermissionDisplay();

        // 按「设置项样式」偏好重排：独立卡片模式下每行单独成卡，分组卡透明化
        SettingsRows.applyRowStyle(findViewById(R.id.group_notification_state));
        SettingsRows.applyRowStyle(findViewById(R.id.group_notification_actions));
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePermissionDisplay();
    }

    private void updatePermissionDisplay() {
        boolean enabled;
        try {
            enabled = NotificationManagerCompat.from(this).areNotificationsEnabled();
        } catch (Exception e) {
            enabled = true;
        }
        rowPermission.setSupporting(getString(enabled
                ? R.string.settings_permission_on_short
                : R.string.settings_permission_off_short));
        rowPermission.setTrailingValue(getString(enabled
                ? R.string.settings_permission_value_on
                : R.string.settings_permission_value_off));
        rowPermission.setTrailingValueColor(ColorStateList.valueOf(MaterialColors.getColor(
                rowPermission, enabled ? androidx.appcompat.R.attr.colorPrimary
                        : androidx.appcompat.R.attr.colorError)));
    }

    private void sendTestNotification() {
        try {
            NotificationHelper.sendAuditChannelTest(this);
            Feedback.show(this, R.string.settings_notification_sent);
        } catch (Exception e) {
            Feedback.showLong(this, getString(R.string.settings_notification_failed,
                    String.valueOf(e.getMessage())));
        }
    }

    /** 跳转系统通知管理页，兼容不同 ROM 的 Intent 差异 */
    private void openSystemSettings() {
        try {
            Intent intent = new Intent("android.settings.APP_NOTIFICATION_SETTINGS");
            intent.putExtra("android.provider.extra.APP_PACKAGE", getPackageName());
            startActivity(intent);
        } catch (Exception e) {
            try {
                Intent intent = new Intent("android.settings.APPLICATION_DETAILS_SETTINGS");
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (Exception e2) {
                Feedback.show(this, R.string.wiki_failed);
            }
        }
    }
}

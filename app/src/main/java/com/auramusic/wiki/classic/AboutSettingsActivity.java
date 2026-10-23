package com.auramusic.wiki.classic;

import android.content.pm.PackageInfo;
import android.os.Bundle;

import androidx.annotation.Nullable;

/**
 * 设置二级页：关于。展示能力说明、数据边界与当前版本号。
 *
 * <p>Stage 14：版本号改为标准列表行的尾部取值，与其余二级页的行结构一致。
 */
public class AboutSettingsActivity extends BaseActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings_about);
        bindToolbar(R.string.settings_group_about);

        Md3ListItemView rowVersion = findViewById(R.id.row_version);
        rowVersion.setTrailingValue(versionName());

        // 按「设置项样式」偏好重排：独立卡片模式下版本行单独成卡，分组卡透明化
        SettingsRows.applyRowStyle(findViewById(R.id.group_about));
    }

    private String versionName() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName == null ? "" : info.versionName;
        } catch (Exception e) {
            return "";
        }
    }
}

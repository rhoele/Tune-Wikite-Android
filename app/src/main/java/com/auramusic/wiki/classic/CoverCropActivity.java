package com.auramusic.wiki.classic;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** 封面裁剪页：返回裁剪后的本地文件路径。 */
public class CoverCropActivity extends BaseActivity {

    public static final String EXTRA_SOURCE = "source_uri";
    public static final String EXTRA_RESULT = "result_path";
    private static final int OUTPUT_SIZE = 1024;

    private SquareCropView cropView;
    private Uri source;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_cover_crop);
        bindToolbar(R.string.crop_title);
        cropView = findViewById(R.id.crop_view);
        MaterialButton done = findViewById(R.id.btn_crop_done);

        String raw = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_SOURCE);
        if (raw == null) {
            finish();
            return;
        }
        source = Uri.parse(raw);
        loadBitmap();
        done.setOnClickListener(v -> export());
    }

    private void loadBitmap() {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(source)) {
                BitmapFactory.decodeStream(in, null, options);
            }
            int sample = 1;
            while (options.outWidth / sample > 2048 || options.outHeight / sample > 2048) {
                sample *= 2;
            }
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample;
            Bitmap bitmap;
            try (InputStream in = getContentResolver().openInputStream(source)) {
                bitmap = BitmapFactory.decodeStream(in, null, options);
            }
            if (bitmap == null) {
                throw new IllegalStateException("无法解码图片");
            }
            cropView.setBitmap(bitmap);
        } catch (Exception e) {
            Feedback.show(this, getString(R.string.au_cover_pick_failed));
            finish();
        }
    }

    private void export() {
        if (!cropView.hasBitmap()) {
            finish();
            return;
        }
        try {
            Bitmap cropped = cropView.export(OUTPUT_SIZE);
            File out = new File(getCacheDir(), "cover-square-" + System.currentTimeMillis() + ".jpg");
            try (FileOutputStream fos = new FileOutputStream(out)) {
                cropped.compress(Bitmap.CompressFormat.JPEG, 92, fos);
            }
            cropped.recycle();
            Intent data = new Intent();
            data.putExtra(EXTRA_RESULT, out.getAbsolutePath());
            setResult(RESULT_OK, data);
            finish();
        } catch (Exception e) {
            Feedback.show(this, getString(R.string.au_cover_pick_failed));
        }
    }
}

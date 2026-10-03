package com.happyagent.mobile.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ScrollView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.happyagent.mobile.R;
import com.happyagent.mobile.data.StorageAccess;
import com.happyagent.mobile.tools.FileTools;

import java.io.File;
import java.io.InputStream;
import java.util.Locale;

// 文件预览：不进编辑器，快速查看。文本/代码 monospace 内嵌（超 50 万字符截断并标注），
// 图片内嵌（采样防 OOM），其它类型给"用系统应用打开"。来源：工作区路径 或 SAF docId。
public class FilePreviewActivity extends AppCompatActivity {

    public static final String EXTRA_PATH = "path";
    public static final String EXTRA_DOC_ID = "doc_id";
    public static final String EXTRA_NAME = "name";
    public static final String EXTRA_MIME = "mime";
    private static final int MAX_PREVIEW_CHARS = 500_000;
    private static final String PROVIDER = "com.happyagent.mobile.fileprovider";

    private String path, docId, name, mime;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeUtil.apply(this);
        setContentView(R.layout.activity_file_preview);
        path = getIntent().getStringExtra(EXTRA_PATH);
        docId = getIntent().getStringExtra(EXTRA_DOC_ID);
        name = getIntent().getStringExtra(EXTRA_NAME);
        mime = getIntent().getStringExtra(EXTRA_MIME);

        MaterialToolbar t = findViewById(R.id.prev_toolbar);
        t.setTitle("预览 " + (name == null ? "" : name));
        t.setNavigationOnClickListener(v -> finish());

        String n = name == null ? "" : name.toLowerCase(Locale.US);
        boolean image = mime != null && mime.startsWith("image/")
                || n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg")
                || n.endsWith(".gif") || n.endsWith(".webp");
        boolean isText = isTextLike(n, mime);

        ScrollView scroll = findViewById(R.id.prev_text_scroll);
        android.widget.TextView textTv = findViewById(R.id.prev_text);
        android.widget.ImageView img = findViewById(R.id.prev_image);
        View placeholder = findViewById(R.id.prev_placeholder);
        MaterialButton openBtn = findViewById(R.id.prev_open_system);

        // 文本/图片读盘与解码都放后台线程，避免主线程阻塞（低配安卓 6 防卡/ANR）
        final ScrollView sScroll = scroll;
        final android.widget.TextView sText = textTv;
        final android.widget.ImageView sImg = img;
        final View sPh = placeholder;
        if (image) {
            sScroll.setVisibility(View.GONE);
            sImg.setVisibility(View.VISIBLE);
            sPh.setVisibility(View.GONE);
            findViewById(R.id.prev_meta).setVisibility(View.GONE);
            new Thread(() -> {
                final Bitmap b = (path != null) ? decodeFile(path)
                        : decodeUri(StorageAccess.docUri(getApplicationContext(), docId));
                runOnUiThread(() -> {
                    if (b == null) {
                        sImg.setVisibility(View.GONE);
                        sPh.setVisibility(View.VISIBLE);
                        ((android.widget.TextView) findViewById(R.id.prev_placeholder_title)).setText("图片解码失败");
                    } else {
                        sImg.setImageBitmap(b);
                    }
                });
            }, "prev-img").start();
        } else if (isText) {
            sScroll.setVisibility(View.VISIBLE);
            sImg.setVisibility(View.GONE);
            sPh.setVisibility(View.GONE);
            findViewById(R.id.prev_meta).setVisibility(View.VISIBLE);
            ((android.widget.TextView) findViewById(R.id.prev_meta)).setText("加载中…");
            new Thread(() -> {
                String content;
                try {
                    content = (path != null)
                            ? new FileTools(getApplicationContext()).readContent(path)
                            : StorageAccess.readText(getApplicationContext(), docId);
                    if (content == null) content = "（读取失败）";
                } catch (Exception e) {
                    content = "读取失败：" + e.getMessage();
                }
                int lines = 0;
                int len = content.length();
                for (int i = 0; i < len; i++) if (content.charAt(i) == '\n') lines++;
                boolean truncated = len > MAX_PREVIEW_CHARS;
                if (truncated) content = content.substring(0, MAX_PREVIEW_CHARS);
                final String show = content;
                final int linesF = lines;
                final int lenF = len;
                final boolean truncF = truncated;
                runOnUiThread(() -> {
                    sText.setText(show);
                    ((android.widget.TextView) findViewById(R.id.prev_meta))
                            .setText(linesF + " 行 · " + fmtChars(lenF) + (truncF ? " · 已截断" : ""));
                });
            }, "prev-txt").start();
        } else {
            sScroll.setVisibility(View.GONE);
            sImg.setVisibility(View.GONE);
            sPh.setVisibility(View.VISIBLE);
            ((android.widget.TextView) findViewById(R.id.prev_placeholder_meta))
                    .setText(mime == null ? "类型未知" : mime);
            openBtn.setOnClickListener(v -> openInSystem());
        }
    }

    // 采样解码：先读尺寸，按目标 1280px 降采样防 OOM（API 23 安全）
    private Bitmap decodeFile(String p) {
        int sample = sampleSize(new File(p));
        return BitmapFactory.decodeFile(p, sampleOptions(sample));
    }

    private Bitmap decodeUri(Uri uri) {
        if (uri == null) return null;
        try {
            InputStream in = getContentResolver().openInputStream(uri);
            if (in == null) return null;
            try {
                android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeStream(in, null, o);
                in.close();
                int sample = sampleSize(o.outWidth, o.outHeight, 1280);
                in = getContentResolver().openInputStream(uri);
                if (in == null) return null;
                return BitmapFactory.decodeStream(in, null, sampleOptions(sample));
            } finally {
                if (in != null) in.close();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static int sampleSize(File f) {
        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = new java.io.FileInputStream(f)) {
            BitmapFactory.decodeStream(in, null, o);
        } catch (Exception e) {
            return 1;
        }
        return sampleSize(o.outWidth, o.outHeight, 1280);
    }

    private static int sampleSize(int w, int h, int target) {
        int max = Math.max(w, h);
        int s = 1;
        while (max / s > target) s *= 2;
        return s;
    }

    private static android.graphics.BitmapFactory.Options sampleOptions(int sample) {
        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
        o.inSampleSize = sample;
        return o;
    }

    private void openInSystem() {
        Uri uri;
        if (docId != null) {
            uri = StorageAccess.docUri(getApplicationContext(), docId);
        } else {
            File f = new File(path);
            uri = FileProvider.getUriForFile(this, PROVIDER, f);
        }
        if (uri == null) return;
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, mime == null ? "*/*" : mime);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(i);
        } catch (Exception e) {
            android.widget.Toast.makeText(this, "没有能打开此文件的应用", android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private boolean isTextLike(String name, String m) {
        if (m != null && m.startsWith("text/")) return true;
        String n = name.toLowerCase(Locale.US);
        return n.endsWith(".txt") || n.endsWith(".md") || n.endsWith(".log") || n.endsWith(".json")
                || n.endsWith(".xml") || n.endsWith(".csv") || n.endsWith(".java") || n.endsWith(".kt")
                || n.endsWith(".ts") || n.endsWith(".js") || n.endsWith(".py") || n.endsWith(".html")
                || n.endsWith(".css") || n.endsWith(".properties") || n.endsWith(".gradle")
                || n.endsWith(".toml") || n.endsWith(".yaml") || n.endsWith(".yml") || n.endsWith(".ini");
    }

    private static String fmtChars(long c) {
        if (c < 1024) return c + " 字符";
        return String.format(Locale.US, "%.1f KB", c / 1024.0);
    }
}

package com.happyagent.mobile.ui;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.happyagent.mobile.R;
import com.happyagent.mobile.tools.FileTools;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

// 内置文件浏览器：不走系统文件选择器，直接浏览内置沙箱与共享存储。
// 选择器模式由调用方启动：
//   EXTRA_MODE = "file"（默认，选文件）| "dir"（选目录）
//   EXTRA_FILTER = "image"（只列图片，可选）
//   EXTRA_START = 起始目录绝对路径（可选，默认共享存储根）
// 结果放回 EXTRA_PATH（绝对路径），RESULT_OK。
public class FileBrowserActivity extends AppCompatActivity {

    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_FILTER = "filter";
    public static final String EXTRA_START = "start";
    public static final String EXTRA_PATH = "path";

    private static final int REQ_STORAGE = 105;
    private static final int MAX_ROWS = 800;

    private String mode = "file";
    private boolean imageOnly;
    private String current;
    private String workspaceRoot;
    private String externalRoot;
    private boolean storageRequested;

    private TextView pathView, statusView;
    private LinearLayout list, rootsRow;
    private View confirmBar;
    private TextView confirmLabel;

    private static final String[] IMG_EXT = {
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "tif", "tiff"
    };

    @Override
    protected void onCreate(Bundle s) {
        ThemeUtil.apply(this);
        super.onCreate(s);
        setContentView(R.layout.activity_file_browser);

        mode = "file".equals(getIntent().getStringExtra(EXTRA_MODE)) ? "file" : "dir";
        imageOnly = "image".equals(getIntent().getStringExtra(EXTRA_FILTER));

        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.fb_toolbar);
        toolbar.setTitle("file".equals(mode)
                ? (imageOnly ? "选图片" : "选文件") : "选目录");
        toolbar.setNavigationIcon(R.drawable.ic_back);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.inflateMenu(R.menu.menu_file_browser);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.fb_new_dir) {
                promptNewDir();
                return true;
            }
            return false;
        });

        pathView = findViewById(R.id.fb_path);
        statusView = findViewById(R.id.fb_status);
        list = findViewById(R.id.fb_list);
        rootsRow = findViewById(R.id.fb_roots);
        confirmBar = findViewById(R.id.fb_confirm_bar);
        confirmLabel = findViewById(R.id.fb_confirm_label);

        try {
            workspaceRoot = new FileTools(this).workspacePath();
        } catch (Exception e) {
            workspaceRoot = getFilesDir().getAbsolutePath();
        }
        File ext = Environment.getExternalStorageDirectory();
        externalRoot = ext == null ? null : ext.getAbsolutePath();

        buildRoots();
        findViewById(R.id.fb_confirm).setOnClickListener(v -> {
            if (current == null) return;
            Intent i = new Intent();
            i.putExtra(EXTRA_PATH, current);
            setResult(RESULT_OK, i);
            finish();
        });
        if ("dir".equals(mode)) {
            confirmBar.setVisibility(View.VISIBLE);
        }

        String start = getIntent().getStringExtra(EXTRA_START);
        if (start == null || start.trim().isEmpty()) {
            start = externalRoot;
        }
        if (start == null || !new File(start).isDirectory()) {
            start = workspaceRoot;
        }
        navigate(start);
    }

    // 快捷根按钮：内置沙箱 / 共享存储 / 应用外部目录
    private void buildRoots() {
        rootsRow.removeAllViews();
        addRootChip("内置沙箱", workspaceRoot);
        addRootChip("共享存储", externalRoot);
        File extFiles = getExternalFilesDir(null);
        addRootChip("应用目录", extFiles == null ? null : extFiles.getAbsolutePath());
    }

    private void addRootChip(final String label, final String path) {
        if (path == null) return;
        int d = (int) getResources().getDisplayMetrics().density;
        TextView chip = new TextView(this);
        chip.setText(label);
        chip.setTextSize(12);
        chip.setPadding(12 * d, 5 * d, 12 * d, 5 * d);
        chip.setTextColor(ThemeUtil.attrColor(this,
                com.google.android.material.R.attr.colorOnPrimaryContainer));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(14 * d);
        bg.setColor(ThemeUtil.attrColor(this,
                com.google.android.material.R.attr.colorPrimaryContainer));
        chip.setBackground(bg);
        chip.setOnClickListener(v -> navigate(path));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = 8 * d;
        rootsRow.addView(chip, lp);
    }

    // ============ 浏览 ============

    private void navigate(final String path) {
        current = path;
        pathView.setText(path);
        confirmLabel.setText(path);
        statusView.setText("加载中…");
        list.removeAllViews();

        new Thread(new Runnable() {
            @Override
            public void run() {
                File dir = new File(path);
                File[] arr = null;
                try {
                    arr = dir.listFiles();
                } catch (Exception ignored) {
                }
                final boolean noAccess = arr == null;
                final List<File> dirs = new ArrayList<File>();
                final List<File> files = new ArrayList<File>();
                if (arr != null) {
                    for (File f : arr) {
                        if (f.isDirectory()) {
                            dirs.add(f);
                        } else if (!imageOnly || isImage(f.getName())) {
                            files.add(f);
                        }
                    }
                }
                Comparator<File> byName = new Comparator<File>() {
                    @Override
                    public int compare(File a, File b) {
                        return a.getName().compareToIgnoreCase(b.getName());
                    }
                };
                Collections.sort(dirs, byName);
                Collections.sort(files, byName);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || !path.equals(current)) return;
                        render(path, dirs, files, noAccess);
                    }
                });
            }
        }, "fb-list").start();
    }

    private void render(String path, List<File> dirs, List<File> files, boolean noAccess) {
        int d = (int) getResources().getDisplayMetrics().density;
        list.removeAllViews();

        // 上级目录
        File parent = new File(path).getParentFile();
        if (parent != null) {
            list.addView(simpleRow("↑", "上级目录", parent.getAbsolutePath(), true, v -> navigate(parent.getAbsolutePath())));
        }

        if (noAccess) {
            statusView.setText("此目录不可访问（无存储权限或系统限制）");
            TextView hint = new TextView(this);
            hint.setText("系统限制了对这个目录的直接访问。可返回「内置沙箱」浏览应用内文件，"
                    + "或去系统设置授予存储权限。");
            hint.setTextSize(13);
            hint.setTextColor(ContextCompat.getColor(this, R.color.on_surface_variant));
            int pad = 16 * d;
            hint.setPadding(pad, 20 * d, pad, pad);
            list.addView(hint);
            maybeRequestStorage(path);
            return;
        }

        int shown = 0;
        for (File f : dirs) {
            if (shown++ >= MAX_ROWS) break;
            String name = f.getName();
            list.addView(simpleRow("📁", name, f.getAbsolutePath(), true,
                    v -> navigate(f.getAbsolutePath())));
        }
        SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm", Locale.US);
        for (File f : files) {
            if (shown >= MAX_ROWS) break;
            shown++;
            String meta = fmtSize(f.length()) + " · " + fmt.format(new Date(f.lastModified()));
            final String fp = f.getAbsolutePath();
            View row = simpleRow(iconOf(f.getName()), f.getName(), meta, false, v -> {
                if ("file".equals(mode)) {
                    Intent i = new Intent();
                    i.putExtra(EXTRA_PATH, fp);
                    setResult(RESULT_OK, i);
                    finish();
                } else {
                    Toast.makeText(this, "这是一个文件，点目录再确认", Toast.LENGTH_SHORT).show();
                }
            });
            row.setOnLongClickListener(v -> {
                showRowMenu(f);
                return true;
            });
            list.addView(row);
        }

        StringBuilder sb = new StringBuilder();
        sb.append(dirs.size()).append(" 目录 · ").append(files.size()).append(" 文件");
        if (shown > MAX_ROWS) sb.append("（仅显示前 ").append(MAX_ROWS).append(" 项）");
        if (imageOnly) sb.append(" · 仅图片");
        statusView.setText(sb.toString());

        if (dirs.isEmpty() && files.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("（空目录）");
            empty.setTextSize(13);
            empty.setTextColor(ContextCompat.getColor(this, R.color.on_surface_variant));
            empty.setPadding(16 * d, 24 * d, 16 * d, 16 * d);
            list.addView(empty);
        }
    }

    // 单行：图标 + 名称 + 次要信息（path/meta）；整行可点
    private View simpleRow(String icon, String name, String sub, boolean bold,
                           View.OnClickListener onClick) {
        int d = (int) getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(16 * d, 11 * d, 16 * d, 11 * d);
        row.setClickable(true);
        row.setFocusable(true);
        row.setForeground(ContextCompat.getDrawable(this, android.R.drawable.list_selector_background));
        row.setOnClickListener(onClick);

        TextView iv = new TextView(this);
        iv.setText(icon);
        iv.setTextSize(16);
        row.addView(iv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        mlp.leftMargin = 12 * d;
        mid.setLayoutParams(mlp);

        TextView tv = new TextView(this);
        tv.setText(name);
        tv.setTextSize(15);
        tv.setTypeface(null, bold ? Typeface.BOLD : Typeface.NORMAL);
        tv.setTextColor(ContextCompat.getColor(this, R.color.on_surface));
        tv.setSingleLine(true);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        mid.addView(tv);

        if (sub != null && !sub.isEmpty()) {
            TextView sv = new TextView(this);
            sv.setText(sub);
            sv.setTextSize(11);
            sv.setTextColor(ContextCompat.getColor(this, R.color.on_surface_variant));
            sv.setSingleLine(true);
            sv.setEllipsize(android.text.TextUtils.TruncateAt.START);
            mid.addView(sv);
        }
        row.addView(mid);

        // 目录用箭头提示可进入
        if (bold) {
            TextView arr = new TextView(this);
            arr.setText("›");
            arr.setTextSize(18);
            arr.setTextColor(ContextCompat.getColor(this, R.color.on_surface_variant));
            row.addView(arr);
        }
        return row;
    }

    // ============ 行操作：复制路径 / 重命名 / 删除 ============

    private void showRowMenu(final File f) {
        androidx.appcompat.widget.PopupMenu menu = new androidx.appcompat.widget.PopupMenu(this, list);
        menu.getMenu().add("复制路径");
        menu.getMenu().add("重命名");
        if (!isRootPath(f.getAbsolutePath())) {
            menu.getMenu().add("删除");
        }
        menu.setOnMenuItemClickListener(item -> {
            String title = String.valueOf(item.getTitle());
            if ("复制路径".equals(title)) {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("path", f.getAbsolutePath()));
                    Toast.makeText(this, "路径已复制", Toast.LENGTH_SHORT).show();
                }
            } else if ("重命名".equals(title)) {
                promptRename(f);
            } else if ("删除".equals(title)) {
                confirmDelete(f);
            }
            return true;
        });
        menu.show();
    }

    private boolean isRootPath(String p) {
        if (p == null) return false;
        return p.equals(workspaceRoot) || p.equals(externalRoot)
                || "/".equals(p) || p.equals(getFilesDir().getAbsolutePath());
    }

    private void promptRename(final File f) {
        final TextView input = new TextView(this);
        int d = (int) getResources().getDisplayMetrics().density;
        input.setPadding(20 * d, 8 * d, 20 * d, 8 * d);
        input.setText(f.getName());
        input.setTextSize(15);
        new AlertDialog.Builder(this)
                .setTitle("重命名")
                .setView(input)
                .setPositiveButton("确定", (dlg, w) -> {
                    String nn = input.getText().toString().trim();
                    if (nn.isEmpty() || nn.contains("/") || nn.contains("\\")) {
                        Toast.makeText(this, "名称不合法", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    File dst = new File(f.getParentFile(), nn);
                    if (dst.exists()) {
                        Toast.makeText(this, "同名已存在", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (f.renameTo(dst)) {
                        navigate(current);
                    } else {
                        Toast.makeText(this, "重命名失败（无权限）", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmDelete(final File f) {
        boolean isDir = f.isDirectory();
        new AlertDialog.Builder(this)
                .setTitle("删除" + (isDir ? "文件夹" : "文件"))
                .setMessage("删除「" + f.getName() + "」" + (isDir ? "（含里面所有内容）" : "") + "？不可恢复。")
                .setPositiveButton("删除", (dlg, w) -> {
                    boolean ok = deleteRec(f);
                    Toast.makeText(this, ok ? "已删除" : "删除失败（无权限）",
                            Toast.LENGTH_SHORT).show();
                    navigate(current);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private boolean deleteRec(File f) {
        try {
            if (f.isDirectory()) {
                File[] kids = f.listFiles();
                if (kids != null) {
                    for (File k : kids) {
                        if (!deleteRec(k)) return false;
                    }
                }
            }
            return f.delete() || !f.exists();
        } catch (Exception e) {
            return false;
        }
    }

    private void promptNewDir() {
        if (current == null) return;
        final TextView input = new TextView(this);
        int d = (int) getResources().getDisplayMetrics().density;
        input.setPadding(20 * d, 8 * d, 20 * d, 8 * d);
        input.setHint("文件夹名称");
        input.setTextSize(15);
        new AlertDialog.Builder(this)
                .setTitle("新建文件夹")
                .setView(input)
                .setPositiveButton("创建", (dlg, w) -> {
                    String nn = input.getText().toString().trim();
                    if (nn.isEmpty() || nn.contains("/") || nn.contains("\\")) {
                        Toast.makeText(this, "名称不合法", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    File nd = new File(current, nn);
                    if (nd.exists()) {
                        Toast.makeText(this, "同名已存在", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (nd.mkdir()) {
                        navigate(current);
                    } else {
                        Toast.makeText(this, "创建失败（无权限）", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ============ 权限 ============

    // 目录不可访问时补申请存储权限：安卓 12 及以下 READ_EXTERNAL_STORAGE；
    // 13+ 申请媒体三项（图片/视频/音频可按路径读，其余目录系统仍会拦）
    private void maybeRequestStorage(String path) {
        if (storageRequested) return;
        if (path == null || externalRoot == null || !path.startsWith(externalRoot)) return;
        storageRequested = true;
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
                    == PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO)
                    == PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {
                return;
            }
            requestPermissions(new String[]{
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_AUDIO}, REQ_STORAGE);
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED) return;
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_STORAGE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req == REQ_STORAGE && current != null) {
            navigate(current);
        }
    }

    // ============ 小工具 ============

    private static boolean isImage(String name) {
        String n = name.toLowerCase(Locale.US);
        int dot = n.lastIndexOf('.');
        if (dot < 0) return false;
        String ext = n.substring(dot + 1);
        for (String e : IMG_EXT) {
            if (e.equals(ext)) return true;
        }
        return false;
    }

    private static String iconOf(String name) {
        String n = name.toLowerCase(Locale.US);
        if (n.endsWith(".zip")) return "🗜";
        if (n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg")
                || n.endsWith(".webp") || n.endsWith(".gif")) return "🖼";
        if (n.endsWith(".mp3") || n.endsWith(".wav") || n.endsWith(".m4a")) return "🎵";
        if (n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".3gp")) return "🎬";
        return "📄";
    }

    private static String fmtSize(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format(Locale.US, "%.1f KB", b / 1024f);
        if (b < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", b / 1024f / 1024f);
        return String.format(Locale.US, "%.2f GB", b / 1024f / 1024f / 1024f);
    }
}

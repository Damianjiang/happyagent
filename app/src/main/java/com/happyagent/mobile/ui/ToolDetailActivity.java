package com.happyagent.mobile.ui;

import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.happyagent.mobile.R;
import com.happyagent.mobile.data.AgentBackend;
import com.happyagent.mobile.model.Models.Tool;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.ArrayList;
import java.util.List;

// 工具详情：完整说明 + 子工具清单（引擎实际实现的一批）+ 启停。
// 子工具清单把"功能有但 UI 没写"的那块补出来。
public class ToolDetailActivity extends AppCompatActivity {

    public static final String EXTRA_TOOL_ID = "tool_id";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeUtil.apply(this);
        setContentView(R.layout.activity_tool_detail);

        String id = getIntent().getStringExtra(EXTRA_TOOL_ID);
        Tool t = AgentBackend.get().getTool(id);
        if (t == null) {
            finish();
            return;
        }

        TextView name = findViewById(R.id.toold_name);
        TextView desc = findViewById(R.id.toold_desc);
        TextView category = findViewById(R.id.toold_category);
        SwitchMaterial sw = findViewById(R.id.toold_switch);

        name.setText(t.name);
        desc.setText(t.desc);
        category.setText(t.category);

        // 子工具清单：引擎里每组实际是一批，逐一露出
        fillSubTools(findViewById(R.id.toold_subs_container), t.id);

        sw.setOnCheckedChangeListener(null);
        sw.setChecked(t.enabled);
        sw.setOnCheckedChangeListener((buttonView, isChecked) -> {
            AgentBackend.get().setToolEnabled(t.id, isChecked);
            Toast.makeText(ToolDetailActivity.this,
                    t.name + (isChecked ? " 已启用" : " 已停用"), Toast.LENGTH_SHORT).show();
        });

        com.google.android.material.appbar.MaterialToolbar toolbar =
                findViewById(R.id.toold_toolbar);
        if (toolbar != null) {
            toolbar.setTitle(t.name);
            toolbar.setNavigationOnClickListener(v -> finish());
        }
        findViewById(R.id.toold_close).setOnClickListener(v -> finish());
    }

    // 子工具清单：每个一行「名字 + 一句用途」，数据来自引擎实际实现
    private void fillSubTools(LinearLayout container, String groupId) {
        List<String[]> subs = subToolsFor(groupId);
        container.removeAllViews();
        for (String[] s : subs) {
            container.addView(makeSubRow(s[0], s[1]));
        }
        if (subs.isEmpty()) {
            TextView none = new TextView(this);
            none.setText("（无子工具）");
            none.setTextSize(13);
            none.setTextColor(secondaryColor());
            none.setPadding(0, 0, 0, (int) (8 * getResources().getDisplayMetrics().density));
            container.addView(none);
        }
    }

    private LinearLayout makeSubRow(String toolName, String purpose) {
        int d = (int) getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, 4 * d, 0, 4 * d);

        TextView n = new TextView(this);
        n.setText(toolName);
        n.setTextSize(13);
        n.setTypeface(n.getTypeface(), android.graphics.Typeface.BOLD);
        n.setTextColor(primaryTextColor());
        row.addView(n);

        TextView p = new TextView(this);
        p.setText("　·　" + purpose);
        p.setTextSize(12);
        p.setTextColor(secondaryColor());
        p.setSingleLine(true);
        p.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        plp.leftMargin = d;
        p.setLayoutParams(plp);
        row.addView(p);
        return row;
    }

    private int primaryTextColor() {
        return androidx.core.content.ContextCompat.getColor(this, R.color.on_surface);
    }

    private int secondaryColor() {
        return androidx.core.content.ContextCompat.getColor(this, R.color.on_surface_variant);
    }

    // 按组列子工具（引擎实际实现，参数示例一并给出，让"功能有 UI 没写"露出来）
    private List<String[]> subToolsFor(String groupId) {
        List<String[]> l = new ArrayList<String[]>();
        switch (groupId) {
            case "tool.file":
                add(l, "file_read", "读文本文件，path 相对工作区或绝对");
                add(l, "file_write", "整文件写入，{path, content}");
                add(l, "file_edit", "局部替换 {path, old_text, new_text, replace_count}");
                add(l, "file_append", "末尾追加 {path, content}");
                add(l, "file_delete", "删除文件/目录（不可恢复，用户明确要求才用） {path}");
                add(l, "file_mkdir", "新建目录（可多级） {path}");
                add(l, "file_list", "列目录 {path?}");
                add(l, "file_info", "文件/目录信息 {path}");
                add(l, "file_exists", "存在性 {path}");
                add(l, "file_move", "移动/重命名 {path, to}");
                add(l, "file_copy", "复制 {path, to}");
                add(l, "file_zip", "压缩 {path, to}");
                add(l, "file_unzip", "解压 {path, to}");
                break;
            case "tool.search":
                add(l, "file_find", "按名找 {path?, name}");
                add(l, "file_grep", "搜关键词 {path, keyword}");
                break;
            case "tool.shell":
                add(l, "shell_exec", "白名单命令 ls/cat/echo/grep/find… {command, timeout_ms?}");
                break;
            case "tool.http":
                add(l, "http_get", "抓网页 {url}");
                add(l, "http_post", "发 POST 可带 JSON 体 {url, body?}");
                break;
            case "tool.text":
                add(l, "text_base64_encode", "文本转 base64 {text}");
                add(l, "text_base64_decode", "base64 转文本 {text}");
                add(l, "text_url_encode", "URL 编码 {text}");
                add(l, "text_url_decode", "URL 解码 {text}");
                add(l, "text_json_get", "按点路径取 JSON 字段 {json, path?}");
                add(l, "text_upper", "转大写 {text}");
                add(l, "text_lower", "转小写 {text}");
                add(l, "text_stats", "字符/行/词统计 {text}");
                add(l, "text_calc", "四则运算 3+4*2 {text}");
                break;
            case "tool.system":
                add(l, "system_device_info", "设备概要 RAM/存储/网络/时间（无参）");
                add(l, "system_battery", "电池电量与充电状态（无参）");
                add(l, "system_storage", "存储剩余/总量（无参）");
                add(l, "system_network", "网络类型与连接状态（无参）");
                add(l, "system_clipboard_get", "读剪贴板文本（无参）");
                add(l, "system_clipboard_set", "写剪贴板 {text}");
                break;
            case "tool.gui":
                add(l, "gui_dump", "读当前屏幕元素（需先开无障碍服务，无参）");
                add(l, "gui_click", "按文本点按钮/条目 {query}");
                add(l, "gui_type", "向可输入框输入 {text}");
                break;
            case "tool.proot":
                add(l, "shell_proot", "在免 root Linux 容器(proot+Alpine)里跑命令 {command, timeout_ms?}（需先一键部署）");
                add(l, "proot_status", "查容器环境状态：是否部署/架构/能否执行（无参）");
                add(l, "proot_setup", "容器未部署时返回一键部署指引（无参）");
                break;
            default:
                break;
        }
        return l;
    }

    private void add(List<String[]> l, String name, String desc) {
        l.add(new String[]{name, desc});
    }
}

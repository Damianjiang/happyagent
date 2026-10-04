package com.happyagent.mobile.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.happyagent.mobile.R;
import com.happyagent.mobile.data.AgentBackend;
import com.happyagent.mobile.model.Models.Tool;

import java.util.ArrayList;
import java.util.List;

// 工具 / 插件目录：每行一个工具，行内开关直接生效，点进去看说明
public class ToolsFragment extends Fragment {

    private RecyclerView recycler;
    private ProgressBar progress;
    private TextView empty;
    private ToolAdapter adapter;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle s) {
        View v = inflater.inflate(R.layout.fragment_tools, container, false);
        recycler = v.findViewById(R.id.tools_recycler);
        progress = v.findViewById(R.id.tools_progress);
        empty = v.findViewById(R.id.tools_empty);
        recycler.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new ToolAdapter(new ArrayList<Tool>());
        recycler.setAdapter(adapter);
        load();
        return v;
    }

    void load() {
        progress.setVisibility(View.VISIBLE);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<Tool> data = AgentBackend.get().getTools();
                if (!isAdded()) return;
                requireActivity().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!isAdded()) return;
                        progress.setVisibility(View.GONE);
                        adapter.update(data);
                        empty.setVisibility(data.isEmpty() ? View.VISIBLE : View.GONE);
                        recycler.setVisibility(data.isEmpty() ? View.GONE : View.VISIBLE);
                    }
                });
            }
        }).start();
    }

    static class ToolAdapter extends RecyclerView.Adapter<ToolAdapter.VH> {
        private final List<Tool> items;

        ToolAdapter(List<Tool> items) {
            this.items = items;
        }

        void update(List<Tool> data) {
            items.clear();
            items.addAll(data);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_tool, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            final Tool t = items.get(pos);
            h.name.setText(t.name);
            h.desc.setText(t.desc);
            h.category.setText(t.category);
            // 分类徽章按类上色：net=青 / sys=绿 / dev=强调色容器，哪类工具一眼可辨
            String cat = t.category == null ? "dev" : t.category;
            android.content.Context catCtx = h.itemView.getContext();
            int fg, bg;
            if ("net".equals(cat)) {
                fg = androidx.core.content.ContextCompat.getColor(catCtx, R.color.acc_teal_c_on);
                bg = androidx.core.content.ContextCompat.getColor(catCtx, R.color.acc_teal_c);
            } else if ("sys".equals(cat)) {
                fg = androidx.core.content.ContextCompat.getColor(catCtx, R.color.status_done);
                bg = androidx.core.content.ContextCompat.getColor(catCtx, R.color.status_done_bg);
            } else {
                // dev=跟随当前强调色（主题属性解析，换色即时生效）
                fg = ThemeUtil.attrColor(catCtx, com.google.android.material.R.attr.colorOnPrimaryContainer);
                bg = ThemeUtil.attrColor(catCtx, com.google.android.material.R.attr.colorPrimaryContainer);
            }
            h.category.setTextColor(fg);
            // 圆角徽章：用 GradientDrawable 保持 bg_chip 的圆角（setBackgroundColor 会丢圆角）
            android.graphics.drawable.GradientDrawable pill = new android.graphics.drawable.GradientDrawable();
            float radius = 8 * catCtx.getResources().getDisplayMetrics().density;
            pill.setCornerRadii(new float[]{radius, radius, radius, radius, radius, radius, radius, radius});
            pill.setColor(bg);
            h.category.setBackground(pill);
            h.sw.setOnCheckedChangeListener(null);
            h.sw.setChecked(t.enabled);
            h.itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    Intent i = new Intent(view.getContext(), ToolDetailActivity.class);
                    i.putExtra(ToolDetailActivity.EXTRA_TOOL_ID, t.id);
                    view.getContext().startActivity(i);
                }
            });
            // 开关改了立刻持久化，下一次跑任务就用新的启用集合
            h.sw.setOnCheckedChangeListener(
                    (android.widget.CompoundButton.OnCheckedChangeListener) (buttonView, isChecked) ->
                            AgentBackend.get().setToolEnabled(t.id, isChecked));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static class VH extends RecyclerView.ViewHolder {
            final TextView name, desc, category;
            final com.google.android.material.switchmaterial.SwitchMaterial sw;

            VH(View v) {
                super(v);
                name = v.findViewById(R.id.tool_name);
                desc = v.findViewById(R.id.tool_desc);
                category = v.findViewById(R.id.tool_category);
                sw = v.findViewById(R.id.tool_switch);
            }
        }
    }
}

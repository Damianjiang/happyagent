package com.happyagent.mobile.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.happyagent.mobile.R;
import com.happyagent.mobile.data.AgentBackend;
import com.happyagent.mobile.model.Models.Session;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

// 会话列表：每行一个会话，带状态徽章，点进去看详情
public class SessionsFragment extends Fragment {

    private RecyclerView recycler;
    private ProgressBar progress;
    private TextView empty;
    private com.google.android.material.button.MaterialButton createBtn;
    private SessionAdapter adapter;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle s) {
        View v = inflater.inflate(R.layout.fragment_sessions, container, false);
        recycler = v.findViewById(R.id.sessions_recycler);
        progress = v.findViewById(R.id.sessions_progress);
        empty = v.findViewById(R.id.sessions_empty);
        createBtn = v.findViewById(R.id.sessions_create);
        createBtn.setOnClickListener(view -> newSessionAndOpen());
        recycler.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new SessionAdapter(new ArrayList<Session>());
        recycler.setAdapter(adapter);
        load();
        return v;
    }

    // 空态"创建第一个会话"：建一个会话并直接进聊天页
    private void newSessionAndOpen() {
        String id = AgentBackend.get().createSession("新对话", null);
        Intent i = new Intent(requireContext(), SessionDetailActivity.class);
        i.putExtra(SessionDetailActivity.EXTRA_SESSION_ID, id);
        startActivity(i);
    }

    // 会话 ⋮ 菜单：重命名 / 删除（数据层早能，界面补上入口）
    private void showSessionMenu(Session s) {
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(s.title)
                .setItems(new String[]{"重命名", "删除"},
                        (d, which) -> {
                            if (which == 0) promptRename(s);
                            else confirmDelete(s);
                        })
                .show();
    }

    private void promptRename(Session s) {
        final EditText et = new EditText(requireContext());
        et.setText(s.title);
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("重命名会话")
                .setView(et)
                .setPositiveButton("保存", (d, w) -> {
                    if (AgentBackend.get().renameSession(s.id, et.getText().toString())) load();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmDelete(Session s) {
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("删除「" + s.title + "」？")
                .setMessage("该会话及全部消息将被删除，不可恢复。")
                .setPositiveButton("删除", (d, w) -> {
                    AgentBackend.get().deleteSession(s.id);
                    load();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // 后台读一遍再回界面，列表数据量小的话其实也够快，但不想卡主线程
    void load() {
        progress.setVisibility(View.VISIBLE);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<Session> data = AgentBackend.get().getSessions();
                if (!isAdded()) return;
                requireActivity().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!isAdded()) return;
                        progress.setVisibility(View.GONE);
                        adapter.update(data);
                        boolean isEmpty = data.isEmpty();
                        SessionsFragment.this.empty.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
                        createBtn.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
                    }
                });
            }
        }).start();
    }

    class SessionAdapter extends RecyclerView.Adapter<SessionAdapter.VH> {
        private final List<Session> items;
        private final SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());

        SessionAdapter(List<Session> items) {
            this.items = items;
        }

        void update(List<Session> data) {
            items.clear();
            items.addAll(data);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_session, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            Session s = items.get(pos);
            h.title.setText(s.title);
            h.date.setText(fmt.format(new Date(s.updatedAt)));
            h.status.setText(s.statusLabel());
            // 状态用小圆点作左侧 drawable，颜色跟状态走，卡片保持干净
            int dot = s.status == 0 ? R.drawable.dot_running
                    : (s.status == 3 ? R.drawable.dot_failed : R.drawable.dot_done);
            int pad = (int) (h.itemView.getContext().getResources().getDisplayMetrics().density * 4);
            h.status.setCompoundDrawablesWithIntrinsicBounds(dot, 0, 0, 0);
            h.status.setCompoundDrawablePadding(pad / 2);
            h.itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    Intent i = new Intent(view.getContext(), SessionDetailActivity.class);
                    i.putExtra(SessionDetailActivity.EXTRA_SESSION_ID, s.id);
                    view.getContext().startActivity(i);
                }
            });
            h.more.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    showSessionMenu(s);
                }
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static class VH extends RecyclerView.ViewHolder {
            final TextView title, status, date;
            final com.google.android.material.button.MaterialButton more;

            VH(View v) {
                super(v);
                title = v.findViewById(R.id.session_title);
                status = v.findViewById(R.id.session_status);
                date = v.findViewById(R.id.session_date);
                more = v.findViewById(R.id.session_more);
            }
        }
    }
}

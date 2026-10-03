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

import android.text.Editable;
import android.text.TextWatcher;

// 会话列表：每行一个会话，带状态徽章，点进去看详情
public class SessionsFragment extends Fragment {

    private RecyclerView recycler;
    private ProgressBar progress;
    private TextView empty;
    private com.google.android.material.button.MaterialButton createBtn;
    private EditText searchBox;
    private SessionAdapter adapter;
    // 全量缓存（含搜索过滤后再喂 adapter；搜索只是"过滤"，不真删）
    private List<Session> allSessions = new ArrayList<Session>();

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
        searchBox = v.findViewById(R.id.sessions_search);
        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence c, int a, int b, int cn) {}
            @Override public void onTextChanged(CharSequence c, int a, int b, int cn) { applyFilter(); }
            @Override public void afterTextChanged(Editable e) {}
        });
        load();
        return v;
    }

    // 按搜索词过滤全量会话（数据层早能按标题匹配，界面补上搜索框）
    private void applyFilter() {
        String q = searchBox == null ? "" : searchBox.getText().toString().trim().toLowerCase(Locale.ROOT);
        List<Session> shown;
        if (q.isEmpty()) {
            shown = allSessions;
        } else {
            shown = new ArrayList<Session>();
            for (Session s : allSessions) {
                String t = s.title == null ? "" : s.title.toLowerCase(Locale.ROOT);
                if (t.contains(q)) shown.add(s);
            }
        }
        adapter.update(shown);
        boolean isEmpty = shown.isEmpty();
        empty.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        createBtn.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onResume() {
        super.onResume();
        // 从详情/其他页回来刷新（标题/状态可能变了）
        load();
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
                        allSessions = data;
                        applyFilter();
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
            // 消息数（数据层 Session.messages 早能查，界面补露出；0 条时不显示）
            int msgCount = s.messages.size();
            if (msgCount > 0) {
                h.msgs.setVisibility(View.VISIBLE);
                h.msgs.setText(msgCount + " 条");
            } else {
                h.msgs.setVisibility(View.GONE);
            }
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
            final TextView title, status, date, msgs;
            final com.google.android.material.button.MaterialButton more;

            VH(View v) {
                super(v);
                title = v.findViewById(R.id.session_title);
                status = v.findViewById(R.id.session_status);
                date = v.findViewById(R.id.session_date);
                msgs = v.findViewById(R.id.session_msgs);
                more = v.findViewById(R.id.session_more);
            }
        }
    }
}

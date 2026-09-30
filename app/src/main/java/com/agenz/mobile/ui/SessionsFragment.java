package com.agenz.mobile.ui;

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

import com.agenz.mobile.R;
import com.agenz.mobile.data.AgentBackend;
import com.agenz.mobile.model.Models.Session;

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
    private SessionAdapter adapter;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle s) {
        View v = inflater.inflate(R.layout.fragment_sessions, container, false);
        recycler = v.findViewById(R.id.sessions_recycler);
        progress = v.findViewById(R.id.sessions_progress);
        empty = v.findViewById(R.id.sessions_empty);
        recycler.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new SessionAdapter(new ArrayList<Session>());
        recycler.setAdapter(adapter);
        load();
        return v;
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
                        empty.setVisibility(data.isEmpty() ? View.VISIBLE : View.GONE);
                    }
                });
            }
        }).start();
    }

    static class SessionAdapter extends RecyclerView.Adapter<SessionAdapter.VH> {
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
            h.agent.setText(s.agent);
            h.status.setText(s.statusLabel());
            h.date.setText(fmt.format(new Date(s.updatedAt)));
            int bg = s.status == 0 ? R.drawable.bg_status_running
                    : (s.status == 3 ? R.drawable.bg_status_failed : R.drawable.bg_status_done);
            h.status.setBackgroundResource(bg);
            h.itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    Intent i = new Intent(view.getContext(), SessionDetailActivity.class);
                    i.putExtra(SessionDetailActivity.EXTRA_SESSION_ID, s.id);
                    view.getContext().startActivity(i);
                }
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static class VH extends RecyclerView.ViewHolder {
            final TextView title, agent, status, date;

            VH(View v) {
                super(v);
                title = v.findViewById(R.id.session_title);
                agent = v.findViewById(R.id.session_agent);
                status = v.findViewById(R.id.session_status);
                date = v.findViewById(R.id.session_date);
            }
        }
    }
}

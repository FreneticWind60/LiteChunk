package com.frenetic.litechunk;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/**
 * Rows of the conversion list.
 * <p>
 * The list is backed by {@link Store#queue} directly instead of a copy: every mutation already goes through the store
 * (which persists it), so a second copy would only create a way for the two to disagree.
 */
final class QueueAdapter extends RecyclerView.Adapter<QueueAdapter.Holder> {

    interface OnRemoveListener {
        void onRemove(int position);
    }

    private final List<QueueItem> items;
    private final OnRemoveListener listener;

    QueueAdapter(List<QueueItem> items, OnRemoveListener listener) {
        this.items = items;
        this.listener = listener;
        setHasStableIds(false);
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.row_queue, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        QueueItem item = items.get(position);
        holder.name.setText(item.name);
        holder.meta.setText(describe(item));
        holder.remove.setOnClickListener(view -> {
            int index = holder.getBindingAdapterPosition();
            if (index != RecyclerView.NO_POSITION) {
                listener.onRemove(index);
            }
        });
        // A row that is currently converting should not be removable from under the service.
        boolean locked = Controller.get(holder.itemView.getContext()).isRunning();
        holder.remove.setEnabled(!locked);
        holder.remove.setAlpha(locked ? 0.35F : 1F);
    }

    private String describe(QueueItem item) {
        StringBuilder text = new StringBuilder();
        if (item.size > 0) {
            text.append(WorldFiles.humanSize(item.size)).append(" · ");
        }
        text.append(item.detectDescription());
        return text.toString();
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView meta;
        final ImageButton remove;

        Holder(@NonNull View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.tvName);
            meta = itemView.findViewById(R.id.tvMeta);
            remove = itemView.findViewById(R.id.btnRemove);
        }
    }
}

package com.frenetic.litechunk;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.List;

/**
 * "本轮输出" rows on the conversion page. Each row opens the same action sheet as the file manager, so a finished
 * archive can be exported, shared or handed to the game without switching pages.
 */
final class OutputAdapter extends RecyclerView.Adapter<OutputAdapter.Holder> {

    interface OnMoreListener {
        void onMore(Store.OutputFile output);
    }

    private final List<Store.OutputFile> items = new java.util.ArrayList<>();
    private final OnMoreListener listener;

    OutputAdapter(List<Store.OutputFile> items, OnMoreListener listener) {
        this.items.addAll(items);
        this.listener = listener;
    }

    /** Replace the rows; the conversion page calls this whenever a round produces more archives. */
    void submit(List<Store.OutputFile> files) {
        items.clear();
        items.addAll(files);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.row_output, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        Store.OutputFile output = items.get(position);
        holder.name.setText(output.name);
        holder.meta.setText(output.editionLabel() + " · " + WorldFiles.humanSize(output.size));
        holder.icon.setImageResource(output.bedrock ? R.drawable.ic_bedrock : R.drawable.ic_java);
        holder.more.setOnClickListener(view -> listener.onMore(output));
        holder.itemView.setOnClickListener(view -> listener.onMore(output));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    /** Convenience for the conversion page, which receives bare {@link File}s from the service. */
    static java.util.List<Store.OutputFile> wrap(List<File> files) {
        java.util.List<Store.OutputFile> wrapped = new java.util.ArrayList<>(files.size());
        for (File file : files) {
            wrapped.add(OutputActions.wrap(file));
        }
        return wrapped;
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView meta;
        final ImageView icon;
        final ImageButton more;

        Holder(@NonNull View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.tvName);
            meta = itemView.findViewById(R.id.tvMeta);
            icon = itemView.findViewById(R.id.ivIcon);
            more = itemView.findViewById(R.id.btnMore);
        }
    }
}

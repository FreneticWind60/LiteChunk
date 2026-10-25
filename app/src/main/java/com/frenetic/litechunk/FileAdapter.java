package com.frenetic.litechunk;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.checkbox.MaterialCheckBox;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Rows of the file manager.
 * <p>
 * The row owns both gestures (see {@link SwipeRevealLayout}): swipe left to slide the row aside and reveal its six
 * actions, swipe right to switch the whole list into multi select with that row ticked.
 */
final class FileAdapter extends RecyclerView.Adapter<FileAdapter.Holder> {

    interface Listener {
        void onEnqueue(Store.OutputFile file);

        /** @param mcworld true for a {@code .mcworld} copy, false for a {@code .zip} copy. */
        void onExport(Store.OutputFile file, boolean mcworld);

        void onShare(Store.OutputFile file);

        void onImport(Store.OutputFile file);

        /** @param file null when the request came from the selection bar. */
        void onDelete(Store.OutputFile file);

        /** Selection count changed, or selection mode was entered / left. */
        void onSelectionChanged();
    }

    private final List<Store.OutputFile> items = new ArrayList<>();
    private final Set<Store.OutputFile> selected = new LinkedHashSet<>();
    private final Listener listener;

    private boolean selectionMode;
    private Holder openRow;

    FileAdapter(Listener listener) {
        this.listener = listener;
        setHasStableIds(true);
    }

    // ---------------------------------------------------------------------------------------------
    // Data
    // ---------------------------------------------------------------------------------------------

    void submit(List<Store.OutputFile> files) {
        items.clear();
        items.addAll(files);
        // Drop selections that no longer exist, otherwise the count lies after a delete.
        selected.retainAll(items);
        if (selectionMode && selected.isEmpty()) {
            selectionMode = false;
        }
        notifyDataSetChanged();
        listener.onSelectionChanged();
    }

    boolean isSelectionMode() {
        return selectionMode;
    }

    void setSelectionMode(boolean enabled) {
        if (selectionMode == enabled) {
            return;
        }
        selectionMode = enabled;
        if (!enabled) {
            selected.clear();
            closeOpenRow();
        } else if (items.size() > 0) {
            closeOpenRow();
        }
        notifyDataSetChanged();
        listener.onSelectionChanged();
    }

    void toggleSelection(Store.OutputFile file) {
        if (!selected.remove(file)) {
            selected.add(file);
        }
        notifyDataSetChanged();
        listener.onSelectionChanged();
    }

    int selectedCount() {
        return selected.size();
    }

    List<Store.OutputFile> selectedFiles() {
        return new ArrayList<>(selected);
    }

    void clearSelection() {
        selected.clear();
        selectionMode = false;
        notifyDataSetChanged();
        listener.onSelectionChanged();
    }

    private void closeOpenRow() {
        if (openRow != null) {
            openRow.row.closeNow();
            openRow = null;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // RecyclerView
    // ---------------------------------------------------------------------------------------------

    @Override
    public long getItemId(int position) {
        return items.get(position).name.hashCode();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        Holder holder = new Holder(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.row_file, parent, false));
        holder.row.setOnRevealListener(new SwipeRevealLayout.OnRevealListener() {
            @Override
            public void onRowOpened(SwipeRevealLayout row) {
                if (openRow != null && openRow.row != row) {
                    openRow.row.close();
                }
                openRow = holder;
            }

            @Override
            public void onSelectSwipe(SwipeRevealLayout row) {
                int position = holder.getBindingAdapterPosition();
                if (position == RecyclerView.NO_POSITION) {
                    return;
                }
                Store.OutputFile file = items.get(position);
                if (!selectionMode) {
                    setSelectionMode(true);
                }
                // Select, never toggle: a swipe is a "pick this one", and un-picking by swiping again would make
                // the direction ambiguous. Tapping the row toggles.
                if (selected.add(file)) {
                    notifyDataSetChanged();
                    listener.onSelectionChanged();
                }
            }
        });
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        Store.OutputFile file = items.get(position);
        holder.name.setText(file.name);
        holder.meta.setText(file.editionLabel() + " · " + WorldFiles.humanSize(file.size));
        holder.icon.setImageResource(file.bedrock ? R.drawable.ic_bedrock : R.drawable.ic_java);

        // Swiping must be impossible while selecting: the strip and the checkbox fight for the same gesture.
        holder.row.closeNow();
        holder.actions.setVisibility(selectionMode ? View.GONE : View.VISIBLE);
        holder.check.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        holder.check.setChecked(selected.contains(file));

        View.OnClickListener tap = view -> {
            if (selectionMode) {
                toggleSelection(file);
            } else if (holder.row.isOpen()) {
                holder.row.close();
            } else {
                OutputActions.showSheet((androidx.fragment.app.FragmentActivity)
                        view.getContext(), file);
            }
        };
        holder.content.setOnClickListener(tap);
        holder.content.setOnLongClickListener(view -> {
            if (!selectionMode) {
                setSelectionMode(true);
                toggleSelection(file);
                return true;
            }
            return false;
        });

        holder.actionEnqueue.setOnClickListener(view -> {
            holder.row.close();
            listener.onEnqueue(file);
        });
        holder.actionZip.setOnClickListener(view -> {
            holder.row.close();
            listener.onExport(file, false);
        });
        holder.actionMcworld.setOnClickListener(view -> {
            holder.row.close();
            listener.onExport(file, true);
        });
        holder.actionShare.setOnClickListener(view -> {
            holder.row.close();
            listener.onShare(file);
        });
        holder.actionGame.setOnClickListener(view -> {
            holder.row.close();
            listener.onImport(file);
        });
        holder.actionDelete.setOnClickListener(view -> {
            holder.row.close();
            listener.onDelete(file);
        });
        // The mcworld copy only makes sense for a Bedrock archive.
        holder.actionMcworld.setVisibility(file.bedrock ? View.VISIBLE : View.GONE);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final SwipeRevealLayout row;
        final View content;
        final View actions;
        final MaterialCheckBox check;
        final ImageView icon;
        final TextView name;
        final TextView meta;
        final View actionEnqueue;
        final View actionZip;
        final View actionMcworld;
        final View actionShare;
        final View actionGame;
        final View actionDelete;

        Holder(@NonNull View itemView) {
            super(itemView);
            row = (SwipeRevealLayout) itemView;
            content = itemView.findViewById(R.id.content);
            actions = itemView.findViewById(R.id.actions);
            check = itemView.findViewById(R.id.cbSelect);
            icon = itemView.findViewById(R.id.ivIcon);
            name = itemView.findViewById(R.id.tvName);
            meta = itemView.findViewById(R.id.tvMeta);
            actionEnqueue = itemView.findViewById(R.id.actionEnqueue);
            actionZip = itemView.findViewById(R.id.actionZip);
            actionMcworld = itemView.findViewById(R.id.actionMcworld);
            actionShare = itemView.findViewById(R.id.actionShare);
            actionGame = itemView.findViewById(R.id.actionGame);
            actionDelete = itemView.findViewById(R.id.actionDelete);
        }
    }
}

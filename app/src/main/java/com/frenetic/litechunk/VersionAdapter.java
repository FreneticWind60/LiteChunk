package com.frenetic.litechunk;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.radiobutton.MaterialRadioButton;

import java.util.ArrayList;
import java.util.List;

/**
 * The target version list: one column, a heading per major version and a single radio per row.
 * <p>
 * It replaced a wall of chip groups, which had two problems that could not be fixed by tuning them. Chips were
 * independent per group, so two rows could look selected at once; and applying the choice happened on a click
 * listener that only existed after the dialog was shown, so closing it by any other means kept the old version while
 * the screen still showed the new one as picked. Here the adapter owns the one selected index, and a tap calls
 * {@link Listener#onPick(int)} immediately - there is no state to get out of step.
 */
final class VersionAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    /** Told which entry was tapped, by index into the labels array the adapter was built from. */
    interface Listener {
        void onPick(int index);
    }

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_VERSION = 1;

    private final String[] labels;
    private final int selected;
    private final Listener listener;
    /** Either a String (a heading) or an Integer (the index of a version in {@link #labels}). */
    private final List<Object> items = new ArrayList<>();

    VersionAdapter(String[] labels, int selected, Listener listener) {
        this.labels = labels;
        this.selected = selected;
        this.listener = listener;

        String major = null;
        for (int index = 0; index < labels.length; index++) {
            String key = majorOf(labels[index]);
            if (!key.equals(major)) {
                major = key;
                items.add(key);
            }
            items.add(index);
        }
    }

    /**
     * Which heading a version belongs under.
     * <p>
     * Two eras, two schemes. Up to 1.21 the interesting number is the second one - "1.21.8" and "1.21.4" are far
     * apart in time, "1.20" and "1.21" even more so, and grouping by the leading 1 would put eighteen years of the
     * game under one heading. From 22 onwards Mojang dropped the leading 1, so the first number is the generation and
     * the second is a point release inside it: "26.3.0" belongs with "26.1.2", not on its own.
     *
     * @param version a label from the format list, e.g. {@code 1.21.8} or {@code 26.3.0}.
     * @return the heading text.
     */
    static String majorOf(String version) {
        String[] parts = version.split("\\.");
        if (parts.length < 2) {
            return version;
        }
        try {
            return Integer.parseInt(parts[0]) >= 22 ? parts[0] : parts[0] + "." + parts[1];
        } catch (NumberFormatException notAVersion) {
            return version;
        }
    }

    /**
     * Where the list should open.
     * <p>
     * The heading above the version in use, not the version itself: scrolling straight to the row leaves the first
     * screenful of rows with no heading at all, which reads like the groups are missing.
     *
     * @return the adapter position of the heading for the selected version, or 0 when there is none.
     */
    int positionOfSelected() {
        int header = 0;
        for (int index = 0; index < items.size(); index++) {
            Object item = items.get(index);
            if (!(item instanceof Integer)) {
                header = index;
                continue;
            }
            if ((Integer) item == selected) {
                return header;
            }
        }
        return 0;
    }

    @Override
    public int getItemViewType(int position) {
        return items.get(position) instanceof Integer ? TYPE_VERSION : TYPE_HEADER;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_HEADER) {
            return new HeaderHolder(inflater.inflate(R.layout.item_version_header, parent, false));
        }
        return new VersionHolder(inflater.inflate(R.layout.item_version_row, parent, false), listener);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof HeaderHolder header) {
            header.title.setText((String) items.get(position));
            return;
        }
        int index = (Integer) items.get(position);
        VersionHolder row = (VersionHolder) holder;
        row.label.setText(labels[index]);
        row.current.setVisibility(index == selected ? View.VISIBLE : View.GONE);
        row.radio.setChecked(index == selected);
        row.index = index;
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class HeaderHolder extends RecyclerView.ViewHolder {
        final TextView title;

        HeaderHolder(@NonNull View view) {
            super(view);
            title = view.findViewById(R.id.tvSection);
        }
    }

    final class VersionHolder extends RecyclerView.ViewHolder {
        final TextView label;
        final TextView current;
        final MaterialRadioButton radio;
        int index;

        VersionHolder(@NonNull View view, Listener listener) {
            super(view);
            label = view.findViewById(R.id.tvVersion);
            current = view.findViewById(R.id.tvCurrent);
            radio = view.findViewById(R.id.radioVersion);
            view.setOnClickListener(clicked -> {
                if (index >= 0 && index < labels.length) {
                    listener.onPick(index);
                }
            });
        }
    }
}

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
import java.util.Locale;

/**
 * The target version list: one column, a heading per major version and a single radio per row.
 * <p>
 * It replaced a wall of chip groups, which had two problems that could not be fixed by tuning them. Chips were
 * independent per group, so two rows could look selected at once; and applying the choice happened on a click
 * listener that only existed after the dialog was shown, so closing it by any other means kept the old version while
 * the screen still showed the new one as picked. Here the adapter owns the one selected index, and a tap calls
 * {@link Listener#onPick(int)} immediately - there is no state to get out of step.
 * <p>
 * The list is long (eighty-odd Java versions) and is built from a plain array, so the search field narrows it by
 * rebuilding the same {@code items} list instead of hiding rows: headings are recomputed for whatever survives the
 * filter, which keeps a narrowed list reading as groups rather than as a loose column of numbers.
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
    private final boolean bedrock;
    private final Listener listener;
    /** Either a String (a heading) or an Integer (the index of a version in {@link #labels}). */
    private final List<Object> items = new ArrayList<>();
    /** Already lower-cased and trimmed; empty means "show everything". */
    private String query = "";

    VersionAdapter(String[] labels, int selected, boolean bedrock, Listener listener) {
        this.labels = labels;
        this.selected = selected;
        this.bedrock = bedrock;
        this.listener = listener;
        rebuild();
    }

    /**
     * Narrows the list to the versions matching {@code text}.
     * <p>
     * Matching is deliberately loose: the raw label is tried first ({@code 1.20.4}), then both sides are compared
     * with punctuation removed ({@code 1204}, {@code 1 20 4}), because people type a version the way they would say
     * it and the separators in between are not what they are remembering.
     *
     * @param text what is in the search box; null or blank shows every version.
     */
    void setQuery(String text) {
        String next = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        if (next.equals(query)) {
            return;
        }
        query = next;
        rebuild();
        notifyDataSetChanged();
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

    /** How many version rows survive the filter (headings not counted). */
    int countVersions() {
        int count = 0;
        for (Object item : items) {
            if (item instanceof Integer) {
                count++;
            }
        }
        return count;
    }

    private void rebuild() {
        items.clear();
        String major = null;
        for (int index = 0; index < labels.length; index++) {
            if (!matches(index)) {
                continue;
            }
            String key = majorOf(labels[index], bedrock);
            if (!key.equals(major)) {
                major = key;
                items.add(key);
            }
            items.add(index);
        }
    }

    private boolean matches(int index) {
        if (query.isEmpty()) {
            return true;
        }
        String label = labels[index].toLowerCase(Locale.ROOT);
        if (label.contains(query)) {
            return true;
        }
        String squashedQuery = squash(query);
        // A query made only of punctuation has nothing left to compare after squashing; the raw pass above owns it.
        return !squashedQuery.isEmpty() && squash(label).contains(squashedQuery);
    }

    /** Lower-cased letters and digits only: {@code "1.20.4"} and {@code "1204"} both become {@code "1204"}. */
    private static String squash(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (Character.isLetterOrDigit(character)) {
                out.append(character);
            }
        }
        return out.toString();
    }

    /**
     * Which heading a version belongs under.
     * <p>
     * The rule differs by edition because the version schemes did. Bedrock has always been {@code 1.major.patch}, so
     * the first two numbers are the heading ({@code 1.26}, {@code 1.21}, {@code 1.20}). Java ran {@code 1.major.patch}
     * for eighteen years worth of releases - grouping those by the leading 1 would put all of it under one heading -
     * and then dropped the leading 1 when it moved to {@code 26.major.patch}, where the first number is the generation
     * again and {@code 26} covers 26.1 through 26.3.
     * <p>
     * So: two numbers while the version starts with 1, otherwise just the first one.
     *
     * @param version a label from the format list, e.g. {@code 1.21.8} or {@code 26.3.0}.
     * @param bedrock whether the list is the Bedrock one, which always groups on two numbers.
     * @return the heading text.
     */
    static String majorOf(String version, boolean bedrock) {
        String[] parts = version.split("\\.");
        if (parts.length < 2) {
            return version;
        }
        if (bedrock || "1".equals(parts[0])) {
            return parts[0] + "." + parts[1];
        }
        return parts[0];
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

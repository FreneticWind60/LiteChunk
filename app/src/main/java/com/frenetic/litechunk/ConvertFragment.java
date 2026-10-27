package com.frenetic.litechunk;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.frenetic.litechunk.databinding.FragmentConvertBinding;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The conversion page: a batch list, the target format and the result of this round.
 */
public class ConvertFragment extends Fragment implements Store.Listener, Controller.Listener {

    private FragmentConvertBinding binding;
    private QueueAdapter queueAdapter;
    private OutputAdapter outputAdapter;

    private ActivityResultLauncher<String[]> pickArchive;
    private ActivityResultLauncher<Uri> pickFolder;

    private Formats formats;
    private String edition = "BEDROCK";
    private String formatId;
    /** Set when the user dismissed the error card for a specific message, so it does not reappear instantly. */
    private String dismissedError;
    /** Guards against the toggle group listener re-entering render(). */
    private boolean rendering;
    private int renderedQueueSize = -1;
    private boolean renderedRunning;
    private String renderedSignature = "";

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Batch add: several archives queued in one pick is the common case (a folder full of exports).
        pickArchive = registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(), this::onArchivesPicked);
        pickFolder = registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), this::onFolderPicked);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentConvertBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        Store store = Store.get(requireContext());
        Controller controller = Controller.get(requireContext());

        queueAdapter = new QueueAdapter(store.queue, this::removeQueueItem);
        binding.rvQueue.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.rvQueue.setAdapter(queueAdapter);
        // Swipe either way to drop an entry: the row is 58dp tall and the remove button is small on purpose.
        new ItemTouchHelper(new SwipeToRemove()).attachToRecyclerView(binding.rvQueue);
        // ViewPager2 sits above this list with its own RecyclerView, which claims every horizontal drag before
        // ItemTouchHelper ever sees it, so swiping a row switched pages instead of removing it. Take the gesture
        // on touch-down and hand it back as soon as it turns out to be vertical, so the page still scrolls.
        binding.rvQueue.addOnItemTouchListener(new HorizontalGestureOwner());

        outputAdapter = new OutputAdapter(Controller.get(requireContext()).roundOutputs().isEmpty()
                ? new ArrayList<>()
                : OutputAdapter.wrap(controller.roundOutputs()),
                output -> OutputActions.showSheet(requireActivity(), output));
        binding.rvOutputs.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.rvOutputs.setAdapter(outputAdapter);

        binding.btnAdd.setOnClickListener(this::showSourceSheet);
        binding.btnConvert.setOnClickListener(this::startConversion);
        binding.btnCancel.setOnClickListener(v -> controller.cancel());
        binding.tvGoFiles.setOnClickListener(v -> {
            if (requireActivity() instanceof MainActivity activity) {
                activity.showFilesPage();
            }
        });
        binding.btnDismissError.setOnClickListener(v -> {
            dismissedError = controller.lastError();
            render();
        });

        binding.toggleEdition.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) {
                return;
            }
            String wanted = checkedId == R.id.btnJava ? "JAVA" : "BEDROCK";
            if (wanted.equals(edition)) {
                return; // also the path taken by render(), which re-checks the current edition
            }
            edition = wanted;
            formatId = null; // reset to the newest version of the chosen edition
            render();
        });
        binding.rowVersion.setOnClickListener(v -> showVersionPicker());
        binding.rowNaming.setOnClickListener(v -> showNamingSheet());

        store.addListener(this);
        controller.addListener(this);
        controller.loadFormats(loaded -> {
            formats = loaded;
            render();
        });
        render();
    }

    @Override
    public void onDestroyView() {
        Store.get(requireContext()).removeListener(this);
        Controller.get(requireContext()).removeListener(this);
        binding = null;
        super.onDestroyView();
    }

    // ---------------------------------------------------------------------------------------------
    // Adding sources
    // ---------------------------------------------------------------------------------------------

    private void showSourceSheet(View anchor) {
        BottomSheetDialog sheet = new BottomSheetDialog(requireContext());
        View content = LayoutInflater.from(requireContext()).inflate(R.layout.sheet_pick_source, null);
        content.findViewById(R.id.rowArchive).setOnClickListener(v -> {
            sheet.dismiss();
            pickArchive.launch(new String[]{"*/*"});
        });
        content.findViewById(R.id.rowFolder).setOnClickListener(v -> {
            sheet.dismiss();
            pickFolder.launch(null);
        });
        content.findViewById(R.id.btnSheetCancel).setOnClickListener(v -> sheet.dismiss());
        sheet.setContentView(content);
        sheet.show();
    }

    private void onArchivesPicked(List<Uri> uris) {
        if (uris == null || uris.isEmpty()) {
            return;
        }
        Store store = Store.get(requireContext());
        List<QueueItem> added = new ArrayList<>();
        for (Uri uri : uris) {
            QueueItem item = QueueItem.fromDocument(requireContext(), uri);
            if (store.add(item)) {
                added.add(item);
            }
        }
        if (added.isEmpty()) {
            Toast.makeText(requireContext(), "这些存档已经在列表里了", Toast.LENGTH_SHORT).show();
        }
        for (QueueItem item : added) {
            Controller.get(requireContext()).detect(requireContext(), item);
        }
        pickDefaultTarget();
    }

    private void onFolderPicked(Uri uri) {
        if (uri == null) {
            return;
        }
        // Keep the grant across a restart: the entry only stores the URI, the bytes are read at conversion time.
        try {
            requireContext().getContentResolver().takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (RuntimeException ignored) {
            // Not every provider offers persistable grants.
        }
        QueueItem item = QueueItem.fromTree(requireContext(), uri);
        if (!Store.get(requireContext()).add(item)) {
            Toast.makeText(requireContext(), "该存档已经在列表里了", Toast.LENGTH_SHORT).show();
            return;
        }
        Controller.get(requireContext()).detect(requireContext(), item);
        pickDefaultTarget();
    }

    private void removeQueueItem(int position) {
        if (Controller.get(requireContext()).isRunning()) {
            Toast.makeText(requireContext(), "转换进行中，先取消再修改列表", Toast.LENGTH_SHORT).show();
            return;
        }
        Store.get(requireContext()).removeAt(position);
    }

    /**
     * Pick the edition the list most likely wants: a list of Bedrock worlds usually means "give me Java", and the
     * other way round. Only applied while the user has not chosen a version explicitly.
     */
    private void pickDefaultTarget() {
        if (binding == null || formatId != null) {
            return;
        }
        int java = 0;
        int bedrock = 0;
        for (QueueItem item : Store.get(requireContext()).queue) {
            if (item.formatId == null) {
                continue;
            }
            if (item.formatId.toUpperCase(Locale.ROOT).startsWith("JAVA")) {
                java++;
            } else {
                bedrock++;
            }
        }
        // Nothing recognised yet (detection still running, or the list just emptied after a finished batch): keep
        // the target the user is looking at instead of snapping back to a default.
        if (java + bedrock == 0) {
            return;
        }
        String wanted = bedrock > java ? "JAVA" : "BEDROCK";
        if (!wanted.equals(edition)) {
            // Do not set `edition` here: the toggle listener owns that field and early-returns when it already
            // matches, which used to leave the target version label showing the previous edition (Java selected,
            // Bedrock's 1.26.60 still on screen). Letting the listener run makes it reset formatId and re-render.
            binding.toggleEdition.check("JAVA".equals(wanted) ? R.id.btnJava : R.id.btnBedrock);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Target format
    // ---------------------------------------------------------------------------------------------

    private void showVersionPicker() {
        if (Controller.get(requireContext()).isRunning()) {
            Toast.makeText(requireContext(), "转换进行中，先取消再修改目标版本", Toast.LENGTH_SHORT).show();
            return;
        }
        Formats.Group group = formats == null ? null : formats.group(edition);
        if (group == null) {
            Toast.makeText(requireContext(), "引擎还在加载，请稍候", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = group.versions;
        String current = currentFormatId();
        int checked = current == null ? 0 : group.indexOf(current);
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(formats.editionLabel(edition) + " 目标版本")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    formatId = group.formatIds[which];
                    dialog.dismiss();
                    render();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private String currentFormatId() {
        if (formats == null) {
            return null;
        }
        Formats.Group group = formats.group(edition);
        if (group == null) {
            return null;
        }
        return formatId != null && formatId.startsWith(edition + "_") ? formatId : group.latestFormatId();
    }

    private String currentVersionLabel() {
        if (formats == null) {
            return "—";
        }
        Formats.Group group = formats.group(edition);
        if (group == null) {
            return "—";
        }
        String id = currentFormatId();
        int index = id == null ? 0 : group.indexOf(id);
        return index >= 0 && index < group.versions.length ? group.versions[index] : "—";
    }

    // ---------------------------------------------------------------------------------------------
    // Output naming
    // ---------------------------------------------------------------------------------------------

    /** A real name to preview the template with, so the row shows something meaningful before any conversion. */
    private String namingSampleName() {
        List<QueueItem> queue = Store.get(requireContext()).queue;
        return queue.isEmpty() ? "存档名" : queue.get(0).name;
    }

    private String namingPreview(boolean bedrock) {
        String name = Naming.preview(requireContext(), namingSampleName(), bedrock);
        // The extension is part of what the user is choosing: turning off "打包为 .mcworld" changes it.
        String extension = bedrock && binding.cbPackMcworld.isChecked() ? ".mcworld" : ".zip";
        return name + extension;
    }

    /**
     * The naming sheet: order, inclusion and separator, with a live preview.
     * <p>
     * Rows are built in code rather than declared three times in XML because the row order itself is what the user is
     * editing — a fixed row could not be moved.
     */
    private void showNamingSheet() {
        Controller controller = Controller.get(requireContext());
        if (controller.isRunning()) {
            Toast.makeText(requireContext(), "转换进行中，先取消再修改命名", Toast.LENGTH_SHORT).show();
            return;
        }
        Context context = requireContext();
        BottomSheetDialog sheet = new BottomSheetDialog(context);
        View content = LayoutInflater.from(context).inflate(R.layout.sheet_naming, null);
        LinearLayout rows = content.findViewById(R.id.namingRows);
        TextView preview = content.findViewById(R.id.tvSheetPreview);
        TextView note = content.findViewById(R.id.tvSheetNote);
        MaterialButtonToggleGroup separators = content.findViewById(R.id.toggleSeparator);

        List<String> order = Naming.split(Naming.order(context));
        List<String> enabled = Naming.split(Naming.enabled(context));
        String[] separator = {Naming.separator(context)};
        boolean bedrock = edition.equals("BEDROCK");
        String sample = namingSampleName();

        Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            String rendered = Naming.render(Naming.join(order), Naming.join(enabled), separator[0],
                    sample, bedrock, System.currentTimeMillis());
            preview.setText(rendered + (bedrock ? ".mcworld" : ".zip"));
            note.setText(enabled.isEmpty()
                    ? "一个都没勾选时会退回 world，同名文件自动加序号。"
                    : "分隔符 " + Naming.describeSeparator(separator[0]) + "，同名文件自动加序号，不会覆盖。");
            rows.removeAllViews();
            for (int index = 0; index < order.size(); index++) {
                rows.addView(namingRow(context, order, enabled, index, refresh[0]));
            }
        };

        separators.check("-".equals(separator[0]) ? R.id.sepDash
                : "_".equals(separator[0]) ? R.id.sepUnder : R.id.sepNone);
        separators.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) {
                return;
            }
            separator[0] = checkedId == R.id.sepDash ? "-" : checkedId == R.id.sepUnder ? "_" : "";
            refresh[0].run();
        });

        content.findViewById(R.id.btnNamingReset).setOnClickListener(v -> {
            Naming.reset(context);
            order.clear();
            order.addAll(Naming.split(Naming.DEFAULT_ORDER));
            enabled.clear();
            enabled.addAll(Naming.split(Naming.DEFAULT_ENABLED));
            separator[0] = Naming.DEFAULT_SEPARATOR;
            separators.check(R.id.sepDash);
            refresh[0].run();
        });
        content.findViewById(R.id.btnNamingDone).setOnClickListener(v -> {
            Naming.save(context, Naming.join(order), Naming.join(enabled), separator[0]);
            sheet.dismiss();
            render();
        });

        refresh[0].run();
        sheet.setContentView(content);
        sheet.show();
    }

    /** One row of the naming sheet: the checkbox that includes the token, and the arrows that move it. */
    private View namingRow(Context context, List<String> order, List<String> enabled, int index, Runnable refresh) {
        String token = order.get(index);
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));

        MaterialCheckBox check = new MaterialCheckBox(context);
        check.setText(Naming.label(token));
        check.setTextSize(14F);
        check.setTextColor(ContextCompat.getColor(context, R.color.text));
        check.setChecked(enabled.contains(token));
        check.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1F));
        // Attached after the initial value so restoring the saved state does not fire a change.
        check.setOnCheckedChangeListener((button, checked) -> {
            if (checked && !enabled.contains(token)) {
                enabled.add(token);
            } else if (!checked) {
                enabled.remove(token);
            }
            refresh.run();
        });
        row.addView(check);

        row.addView(namingMoveButton(context, true, index > 0, () -> {
            Collections.swap(order, index, index - 1);
            refresh.run();
        }));
        row.addView(namingMoveButton(context, false, index < order.size() - 1, () -> {
            Collections.swap(order, index, index + 1);
            refresh.run();
        }));
        return row;
    }

    private ImageButton namingMoveButton(Context context, boolean up, boolean enabled, Runnable action) {
        ImageButton button = new ImageButton(context);
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(36), dp(36)));
        android.util.TypedValue background = new android.util.TypedValue();
        context.getTheme().resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless, background, true);
        button.setBackgroundResource(background.resourceId);
        button.setImageResource(R.drawable.ic_expand_more);
        // One icon, two directions: the drawable is a chevron pointing down.
        button.setRotation(up ? 180F : 0F);
        button.setColorFilter(ContextCompat.getColor(context,
                enabled ? R.color.text_dim : R.color.divider));
        button.setEnabled(enabled);
        button.setContentDescription(up ? "上移" : "下移");
        if (enabled) {
            button.setOnClickListener(v -> action.run());
        }
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // ---------------------------------------------------------------------------------------------
    // Conversion
    // ---------------------------------------------------------------------------------------------

    private void startConversion(View anchor) {
        Controller controller = Controller.get(requireContext());
        Store store = Store.get(requireContext());
        if (controller.isRunning()) {
            return;
        }
        String id = currentFormatId();
        if (id == null) {
            Toast.makeText(requireContext(), "目标版本还没准备好", Toast.LENGTH_SHORT).show();
            return;
        }
        if (store.queue.isEmpty()) {
            Toast.makeText(requireContext(), "转化列表是空的", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean bedrock = edition.equals("BEDROCK");
        boolean packMcworld = bedrock && binding.cbPackMcworld.isChecked();
        boolean deleteSource = binding.cbDeleteSource.isChecked();
        controller.start(new ArrayList<>(store.queue), id, packMcworld, deleteSource);
        render();
    }

    // ---------------------------------------------------------------------------------------------
    // Rendering
    // ---------------------------------------------------------------------------------------------

    @Override
    public void onQueueChanged() {
        render();
        // An entry can arrive from the file page too ("装填"), and a Bedrock-only list should still default to Java.
        // pickDefaultTarget() itself backs off once the user has picked a target explicitly.
        pickDefaultTarget();
    }

    @Override
    public void onOutputsChanged() {
        // The outputs of this round live in the controller, not in the scanned folder, so nothing to do here.
    }

    @Override
    public void onControllerChanged() {
        render();
    }

    private void render() {
        if (binding == null || rendering) {
            return;
        }
        rendering = true;
        try {
            renderInternal();
        } finally {
            rendering = false;
        }
    }

    private void renderInternal() {
        Controller controller = Controller.get(requireContext());
        Store store = Store.get(requireContext());
        boolean running = controller.isRunning();
        boolean bedrock = edition.equals("BEDROCK");

        binding.toggleEdition.check(bedrock ? R.id.btnBedrock : R.id.btnJava);
        binding.tvVersion.setText(currentVersionLabel());
        binding.cbPackMcworld.setVisibility(bedrock ? View.VISIBLE : View.GONE);
        binding.tvNamingPreview.setText(namingPreview(bedrock));

        int count = store.queue.size();
        binding.tvQueueCount.setText(count == 0 ? "" : count + " 个");
        binding.tvQueueEmpty.setVisibility(count == 0 ? View.VISIBLE : View.GONE);
        binding.tvQueueHint.setVisibility(count == 0 ? View.GONE : View.VISIBLE);
        // Re-binding the whole list on every progress tick would fight the swipe animation for nothing, so the
        // rows are only rebuilt when something a row actually shows has changed.
        String signature = queueSignature(store, running);
        if (!signature.equals(renderedSignature)) {
            renderedSignature = signature;
            renderedQueueSize = count;
            renderedRunning = running;
            queueAdapter.notifyDataSetChanged();
        }

        boolean formatsReady = formats != null && currentFormatId() != null;
        boolean canConvert = formatsReady && count > 0 && !running;
        binding.btnConvert.setEnabled(canConvert);
        // A Material button with a custom backgroundTint does not visibly dim when disabled, and "开始转换" looking
        // active on an empty list is the first thing a new user taps.
        binding.btnConvert.setAlpha(canConvert ? 1F : 0.4F);
        binding.btnConvert.setText(running ? "转换中…" : "开始转换");
        binding.btnAdd.setEnabled(!running);
        binding.cbDeleteSource.setEnabled(!running);
        binding.cbPackMcworld.setEnabled(!running);
        binding.rowVersion.setEnabled(!running);
        binding.rowNaming.setEnabled(!running);

        if (formats == null) {
            binding.tvEngine.setText("正在加载转换引擎…");
        } else {
            binding.tvEngine.setText("引擎 " + formats.engineVersion + " · 支持 "
                    + formats.groups.length + " 个平台");
        }

        // Progress
        if (running) {
            binding.cardProgress.setVisibility(View.VISIBLE);
            String title = controller.total() <= 1
                    ? controller.currentName()
                    : (controller.index() + 1) + "/" + controller.total() + " " + controller.currentName();
            binding.tvProgressTitle.setText(title);
            int percent = (int) Math.round(Math.max(0D, Math.min(1D, controller.progress())) * 100D);
            binding.tvProgressPercent.setText(percent + "%");
            binding.progress.setProgressCompat(percent, true);
            String stage = controller.stage();
            binding.tvStage.setText(stage == null || stage.isEmpty()
                    ? "已用时 " + controller.elapsedSeconds() + " 秒"
                    : stage + " · " + controller.elapsedSeconds() + " 秒");
        } else {
            binding.cardProgress.setVisibility(View.GONE);
        }

        // Outputs of this round
        List<File> round = controller.roundOutputs();
        if (round.isEmpty()) {
            binding.cardOutputs.setVisibility(View.GONE);
        } else {
            binding.cardOutputs.setVisibility(View.VISIBLE);
            outputAdapter.submit(OutputAdapter.wrap(round));
        }

        // Error
        String error = controller.lastError();
        if (error == null || error.equals(dismissedError)) {
            binding.cardError.setVisibility(View.GONE);
        } else {
            binding.cardError.setVisibility(View.VISIBLE);
            binding.tvError.setText(error);
        }
    }

    private String queueSignature(Store store, boolean running) {
        StringBuilder builder = new StringBuilder();
        builder.append(running).append('|');
        for (QueueItem item : store.queue) {
            builder.append(item.id).append(':').append(item.detectDescription()).append(';');
        }
        return builder.toString();
    }

    // ---------------------------------------------------------------------------------------------
    // Swipe to remove
    // ---------------------------------------------------------------------------------------------

    /**
     * Keeps horizontal drags inside the queue list.
     * <p>
     * The page lives in a ViewPager2 (itself a RecyclerView), which intercepts any sideways drag as a page
     * change long before {@link ItemTouchHelper} can start a swipe, so "swipe a row to remove it" silently did
     * nothing. Grabbing the gesture on touch-down and only releasing it when the movement turns out to be
     * vertical keeps both behaviours: sideways removes, up and down still scrolls the page.
     */
    private final class HorizontalGestureOwner implements RecyclerView.OnItemTouchListener {
        private final int touchSlop = ViewConfiguration.get(requireContext()).getScaledTouchSlop();
        private float downX;
        private float downY;

        @Override
        public boolean onInterceptTouchEvent(@NonNull RecyclerView recyclerView, @NonNull MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getX();
                    downY = event.getY();
                    recyclerView.getParent().requestDisallowInterceptTouchEvent(true);
                    break;
                case MotionEvent.ACTION_MOVE:
                    float dx = Math.abs(event.getX() - downX);
                    float dy = Math.abs(event.getY() - downY);
                    if (dy > touchSlop && dy > dx) {
                        recyclerView.getParent().requestDisallowInterceptTouchEvent(false);
                    }
                    break;
                default:
                    recyclerView.getParent().requestDisallowInterceptTouchEvent(false);
                    break;
            }
            return false;
        }

        @Override
        public void onTouchEvent(@NonNull RecyclerView recyclerView, @NonNull MotionEvent event) {
        }

        @Override
        public void onRequestDisallowInterceptTouchEvent(boolean disallowIntercept) {
        }
    }

    /**
     * Left or right swipe on a queue row removes it, which is what makes cleaning up a mis-picked list quick.
     * The row is only removed from the model: the store persists the change and the listener re-renders.
     */
    private final class SwipeToRemove extends ItemTouchHelper.SimpleCallback {
        SwipeToRemove() {
            super(0, ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT);
        }

        @Override
        public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder,
                              @NonNull RecyclerView.ViewHolder target) {
            return false;
        }

        @Override
        public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
            int position = viewHolder.getBindingAdapterPosition();
            if (position == RecyclerView.NO_POSITION) {
                return;
            }
            if (Controller.get(requireContext()).isRunning()) {
                queueAdapter.notifyItemChanged(position);
                Toast.makeText(requireContext(), "转换进行中，先取消再修改列表", Toast.LENGTH_SHORT).show();
                return;
            }
            Store.get(requireContext()).removeAt(position);
        }
    }
}

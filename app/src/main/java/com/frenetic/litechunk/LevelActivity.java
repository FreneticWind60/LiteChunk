package com.frenetic.litechunk;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.frenetic.litechunk.api.LevelField;
import com.frenetic.litechunk.api.LevelInfo;
import com.frenetic.litechunk.api.LevelNote;
import com.frenetic.litechunk.databinding.ActivityLevelBinding;
import com.frenetic.litechunk.databinding.DialogHeaderBinding;
import com.frenetic.litechunk.databinding.DialogLevelTextBinding;
import com.frenetic.litechunk.databinding.RowLevelFieldBinding;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Read and edit the parts of {@code level.dat} that can be changed safely.
 * <p>
 * This exists because a lot of "the conversion did nothing useful" cases are not conversions: the world only needs a
 * different name, game mode or seed. Running those through the engine re-encodes every chunk (minutes on a large
 * world) and replaces identifiers it cannot represent, while editing the header takes milliseconds and leaves the
 * chunks untouched.
 * <p>
 * Everything here happens on a {@code level.dat} that was copied out of the archive into cache. Saving writes the
 * edited copy back into the archive through a temporary file, so a failure leaves the original untouched.
 */
public class LevelActivity extends AppCompatActivity {

    static final String EXTRA_PATH = "path";

    private static final int MENU_SAVE = 1;
    private static final int MENU_SAVE_AS = 2;
    private static final int MENU_DEFAULTS = 3;

    private ActivityLevelBinding binding;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "litechunk-level");
        thread.setPriority(Thread.NORM_PRIORITY);
        return thread;
    });

    /** The archive the world came from. */
    private File archive;
    /** Cache directory that holds the extracted {@code level.dat}. */
    private File workDirectory;
    /** Name of the level.dat entry inside the archive, needed to put it back. */
    private String entryName;

    private LevelInfo info;
    /** Edits made since the screen opened, in the engine's own value format. */
    private final Map<String, String> pending = new LinkedHashMap<>();

    /** True while a read or write is in flight; the header buttons are disabled so a save cannot be doubled. */
    private boolean busy;
    /** True when the save in flight is meant to land in a new file rather than over the original. */
    private boolean saveAsCopy;
    /** Set when leaving on purpose, so the unsaved-changes guard does not fire on the way out. */
    private boolean leaving;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityLevelBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        String path = getIntent().getStringExtra(EXTRA_PATH);
        if (path == null) {
            finish();
            return;
        }
        archive = new File(path);

        binding.btnBack.setOnClickListener(view -> attemptExit());
        binding.btnSave.setOnClickListener(view -> requestSave());
        binding.btnMenu.setOnClickListener(this::showMenu);
        binding.tvName.setText(archive.getName());
        binding.tvPath.setText(archive.getAbsolutePath());

        // Back has to ask the same question as the toolbar's arrow. Registering here rather than overriding
        // onBackPressed() keeps predictive back on Android 13+ working.
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                attemptExit();
            }
        });

        load();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdown();
    }

    // ---------------------------------------------------------------------------------------------
    // Loading
    // ---------------------------------------------------------------------------------------------

    private void load() {
        setBusy(true);
        io.execute(() -> {
            try {
                workDirectory = new File(getCacheDir(), "detail/" + key(archive));
                WorldFiles.delete(workDirectory);
                File levelDat = new File(workDirectory, "level.dat");
                entryName = Archive.extractLevelDat(archive, levelDat);
                Controller.get(this).inspectLevel(workDirectory, receiver);
            } catch (Throwable failure) {
                post(() -> showFailure(failure));
            }
        });
    }

    private final ResultReceiver receiver = new ResultReceiver(main) {
        @Override
        protected void onReceiveResult(int code, Bundle data) {
            if (binding == null) {
                return;
            }
            if (code == Job.CODE_LEVEL_INFO) {
                info = Job.unpackLevel(data);
                setBusy(false);
                render();
            } else if (code == Job.CODE_LEVEL_SAVED) {
                writeBack();
            } else if (code == Job.CODE_FATAL) {
                setBusy(false);
                showFailure(new IllegalStateException(
                        data == null ? "未知错误" : data.getString(Job.KEY_ERROR, "未知错误")));
            }
        }
    };

    private void render() {
        if (info == null) {
            showFailure(new IllegalStateException("无法读取 level.dat"));
            return;
        }

        StringBuilder summary = new StringBuilder();
        summary.append(info.formatLabel);
        if (info.version != null) {
            summary.append(" ").append(info.version);
        }
        summary.append(" · ").append(WorldFiles.humanSize(archive.length()));
        if (!pending.isEmpty()) {
            summary.append(" · 已改 ").append(pending.size()).append(" 项");
        }
        binding.tvSummary.setText(summary.toString());

        renderNotes();
        renderFields();
        updateActions();
    }

    /** The save button reflects "there is something to write"; the menu stays reachable either way. */
    private void updateActions() {
        binding.btnSave.setEnabled(!busy && !pending.isEmpty());
        binding.btnMenu.setEnabled(!busy);
    }

    private void renderNotes() {
        binding.noteContainer.removeAllViews();
        if (info.notes.isEmpty()) {
            binding.cardNotes.setVisibility(View.GONE);
            return;
        }
        binding.cardNotes.setVisibility(View.VISIBLE);
        for (LevelNote note : info.notes) {
            TextView row = new TextView(this);
            row.setText(note.textZh + "\n" + note.textEn);
            row.setTextSize(13);
            row.setLineSpacing(4f, 1f);
            row.setTextColor(severityColor(note.severity));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.bottomMargin = dp(10);
            row.setLayoutParams(params);
            binding.noteContainer.addView(row);
        }
    }

    private void renderFields() {
        binding.fieldContainer.removeAllViews();
        LayoutInflater inflater = getLayoutInflater();
        boolean first = true;
        for (LevelField field : info.fields) {
            RowLevelFieldBinding row = RowLevelFieldBinding.inflate(inflater, binding.fieldContainer, false);
            row.tvLabel.setText(field.labelZh);
            row.tvKey.setText(field.labelEn);

            String value = display(field);
            row.tvValue.setText(value);
            if (field.isEditable()) {
                row.tvValue.setTextColor(getColor(R.color.accent));
                row.ivChevron.setVisibility(View.VISIBLE);
                row.getRoot().setOnClickListener(view -> edit(field));
            } else {
                row.tvValue.setTextColor(getColor(R.color.text_dim));
                row.ivChevron.setVisibility(View.GONE);
                row.getRoot().setOnClickListener(null);
                row.getRoot().setClickable(false);
            }
            // Hints are the difference between "StorageVersion" and "do not touch this". They get their own line
            // rather than being appended to the key, which truncated them to the point of being useless.
            String hint = hint(field);
            if (hint != null) {
                row.tvHint.setText(hint);
                row.tvHint.setVisibility(View.VISIBLE);
            } else {
                row.tvHint.setVisibility(View.GONE);
            }

            if (!first) {
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                params.topMargin = dp(4);
                row.getRoot().setLayoutParams(params);
            }
            first = false;
            binding.fieldContainer.addView(row.getRoot());
        }
    }

    private String display(LevelField field) {
        String current = pending.containsKey(field.id) ? pending.get(field.id) : field.value;
        if (field.kind == LevelField.KIND_BOOL) {
            return "true".equalsIgnoreCase(current) ? "开 / On" : "关 / Off";
        }
        if (field.kind == LevelField.KIND_ENUM) {
            int index = field.optionIndex(current);
            return index < 0 ? current : field.optionLabel(index);
        }
        return current == null || current.isEmpty() ? "（未设置 / unset）" : current;
    }

    private String hint(LevelField field) {
        return field.hintZh != null ? field.hintZh : field.hintEn;
    }

    private int severityColor(int severity) {
        if (severity == LevelNote.SEVERITY_ERROR) {
            return getColor(R.color.danger);
        }
        if (severity == LevelNote.SEVERITY_WARN) {
            return getColor(R.color.accent_dim);
        }
        return getColor(R.color.text_dim);
    }

    // ---------------------------------------------------------------------------------------------
    // Editing
    // ---------------------------------------------------------------------------------------------

    private void edit(LevelField field) {
        String current = pending.containsKey(field.id) ? pending.get(field.id) : field.value;
        if (field.kind == LevelField.KIND_ENUM) {
            editChoice(field, current);
        } else if (field.kind == LevelField.KIND_BOOL) {
            editFlag(field, current);
        } else {
            editText(field, current);
        }
    }

    private void editChoice(LevelField field, String current) {
        String[] labels = new String[field.optionIds.length];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = field.optionLabel(i);
        }
        int checked = field.optionIndex(current);
        titled(field.labelZh, field.labelEn)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    pending.put(field.id, field.optionIds[which]);
                    dialog.dismiss();
                    render();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void editFlag(LevelField field, String current) {
        boolean on = "true".equalsIgnoreCase(current);
        titled(field.labelZh, field.labelEn)
                .setSingleChoiceItems(new String[]{"开 / On", "关 / Off"}, on ? 0 : 1, (dialog, which) -> {
                    pending.put(field.id, which == 0 ? "true" : "false");
                    dialog.dismiss();
                    render();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * Text / number editor.
     * <p>
     * The first version dropped a bare {@link EditText} into the dialog, which read as a broken form: the only
     * visual affordance was the framework's underline running edge to edge, the text sat flush against the same
     * edge, and the title used {@code "\n"} to fake a subtitle so the English key looked like a second heading.
     * The layout now owns the title, the subtitle and an outlined box, and the value is pre-selected because
     * replacing it is what people come here to do.
     */
    private void editText(LevelField field, String current) {
        DialogLevelTextBinding editor = DialogLevelTextBinding.inflate(getLayoutInflater());
        editor.tvTitle.setText(field.labelZh);
        editor.tvSubtitle.setText(field.labelEn);

        boolean numeric = field.kind == LevelField.KIND_INT || field.kind == LevelField.KIND_LONG;
        editor.inputLayout.setHint(getString(numeric
                ? R.string.level_field_number_hint : R.string.level_field_text_hint));
        TextInputEditText input = editor.etValue;
        if (numeric) {
            input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        }
        input.setText(current == null ? "" : current);
        if (input.length() > 0) {
            input.setSelection(0, input.length());
        }

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(editor.getRoot())
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", null)
                .create();
        dialog.show();
        // Wired after show() so the button can reject an empty value without closing the dialog.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String value = input.getText() == null ? "" : input.getText().toString().trim();
            if (value.isEmpty()) {
                editor.inputLayout.setError(getString(R.string.level_field_empty_error));
                return;
            }
            if (value.equals(field.value)) {
                pending.remove(field.id);
            } else {
                pending.put(field.id, value);
            }
            dialog.dismiss();
            render();
        });
        input.requestFocus();
    }

    // ---------------------------------------------------------------------------------------------
    // Saving
    // ---------------------------------------------------------------------------------------------

    /**
     * Ask before overwriting.
     * <p>
     * There is deliberately no third option to "keep a copy alongside": backups used to be written next to
     * {@code level.dat} automatically, and they were a liability. They piled up inside the world folder where
     * nobody could find them, a second save produced a second file that looked identical to the first, and the
     * only way to explain which one was current was to open the archive. Making the copy an explicit choice --
     * "save as" writes a new archive and leaves this one alone -- puts the decision where it can be understood.
     * What the dialog has to be unambiguous about is that overwriting cannot be undone.
     */
    private void requestSave() {
        if (busy || pending.isEmpty()) {
            return;
        }
        titled("覆盖原存档", "Overwrite")
                .setMessage("将修改 " + pending.size() + " 项并直接写入「" + archive.getName() + "」，"
                        + "原文件不会保留副本，覆盖后不可找回。\n\n"
                        + "若想保留原档，请用「另存为」。")
                .setNeutralButton("取消", null)
                .setNegativeButton("另存为", (dialog, which) -> startSave(true))
                .setPositiveButton("覆盖", (dialog, which) -> startSave(false))
                .show();
    }

    private void startSave(boolean asCopy) {
        if (busy || pending.isEmpty()) {
            return;
        }
        // Both paths write a whole second copy of the archive before anything is replaced, so the room needed is the
        // archive's own size. Catching it here costs a dialog; catching it at write time costs a failure the user has
        // to interpret while the screen still shows their edits.
        long needed = archive.length();
        long free = Space.free(this);
        if (free > 0L && free < needed) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("空间可能不足")
                    .setMessage((asCopy ? "另存为要写出" : "保存要先写出")
                            + "一份约 " + WorldFiles.humanSize(needed) + " 的新文件再替换，当前只剩 "
                            + WorldFiles.humanSize(free) + "。\n\n写入失败时原档不会损坏，但这次修改也不会生效。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("仍然继续", (dialog, which) -> performSave(asCopy))
                    .show();
            return;
        }
        performSave(asCopy);
    }

    private void performSave(boolean asCopy) {
        saveAsCopy = asCopy;
        setBusy(true);
        Controller.get(this).applyLevel(workDirectory, new LinkedHashMap<>(pending), receiver);
    }

    /**
     * Write the edited {@code level.dat} into an archive once the engine has verified it.
     * <p>
     * "Save as" produces the copy and the rewrite in one pass ({@link Archive#rewrite}), so a 1 GB world is read
     * once instead of being copied and then rewritten.
     */
    private void writeBack() {
        File levelDat = new File(workDirectory, "level.dat");
        boolean asCopy = saveAsCopy;
        io.execute(() -> {
            try {
                File target = asCopy ? copyTarget() : archive;
                Archive.rewrite(archive, target, entryName, levelDat);
                WorldFiles.delete(workDirectory);
                post(() -> {
                    setBusy(false);
                    pending.clear();
                    Store.get(this).refreshOutputs();
                    String message = asCopy
                            ? "已另存为 " + target.getName()
                            : "已覆盖保存到 " + archive.getName() + "（不可找回）";
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                    leave();
                });
            } catch (Throwable failure) {
                post(() -> {
                    setBusy(false);
                    showFailure(failure);
                });
            }
        });
    }

    /**
     * Name for a "save as" copy: the original name plus a fresh timestamp, in the same folder so the file list
     * picks it up immediately.
     */
    private File copyTarget() {
        String base = WorldFiles.stripArchiveExtension(archive.getName());
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
        String extension = archive.getName().toLowerCase(Locale.ROOT).endsWith(".mcworld")
                ? ".mcworld" : ".zip";
        return WorldFiles.unique(archive.getParentFile(), base + "-" + stamp + extension);
    }

    // ---------------------------------------------------------------------------------------------
    // Leaving
    // ---------------------------------------------------------------------------------------------

    private void showMenu(View anchor) {
        if (busy) {
            return;
        }
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, MENU_SAVE, 0, R.string.level_menu_save);
        menu.getMenu().add(0, MENU_SAVE_AS, 1, R.string.level_menu_save_as);
        menu.getMenu().add(0, MENU_DEFAULTS, 2, R.string.level_menu_defaults);
        boolean dirty = !pending.isEmpty();
        for (int id : new int[]{MENU_SAVE, MENU_SAVE_AS, MENU_DEFAULTS}) {
            menu.getMenu().findItem(id).setEnabled(dirty);
        }
        menu.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == MENU_SAVE) {
                requestSave();
                return true;
            }
            if (id == MENU_SAVE_AS) {
                startSave(true);
                return true;
            }
            if (id == MENU_DEFAULTS) {
                restoreDefaults();
                return true;
            }
            return false;
        });
        menu.show();
    }

    /** Drop every edit and show the values the file had when it was opened. Nothing is written. */
    private void restoreDefaults() {
        if (pending.isEmpty()) {
            return;
        }
        pending.clear();
        render();
        Toast.makeText(this, "已恢复为打开时的值", Toast.LENGTH_SHORT).show();
    }

    /**
     * The single exit point for the toolbar arrow and the system back gesture.
     * <p>
     * Nothing changed means nothing to ask: leaving is immediate. Anything changed gets the three-way question,
     * because "did I save that?" is exactly the doubt this screen must not create.
     */
    private void attemptExit() {
        if (leaving) {
            finish();
            return;
        }
        if (busy) {
            return;
        }
        if (pending.isEmpty()) {
            leave();
            return;
        }
        titled("有未保存的修改", "Unsaved changes")
                .setMessage("你改了 " + pending.size() + " 项，还没有写入存档。")
                .setNegativeButton("直接退出", (dialog, which) -> leave())
                .setNeutralButton("另存为", (dialog, which) -> startSave(true))
                .setPositiveButton("保存", (dialog, which) -> startSave(false))
                .show();
    }

    private void leave() {
        leaving = true;
        finish();
    }

    // ---------------------------------------------------------------------------------------------
    // Plumbing
    // ---------------------------------------------------------------------------------------------

    private void setBusy(boolean value) {
        busy = value;
        binding.progress.setVisibility(value ? View.VISIBLE : View.GONE);
        updateActions();
        // Leaving mid-write would strand the work directory and lose the result silently.
        binding.btnBack.setEnabled(!value);
    }

    /**
     * Title block shared by every dialog on this screen.
     * <p>
     * Chinese at 19sp bold, the underlying NBT key at 11sp beneath it. Passing a two-line string to
     * {@code setTitle()} was the earlier approach and it made the key look like a second heading.
     */
    private DialogHeaderBinding header(String titleZh, String titleEn) {
        DialogHeaderBinding header = DialogHeaderBinding.inflate(getLayoutInflater());
        header.tvTitle.setText(titleZh);
        if (titleEn == null || titleEn.isEmpty()) {
            header.tvSubtitle.setVisibility(View.GONE);
        } else {
            header.tvSubtitle.setText(titleEn);
        }
        return header;
    }

    private MaterialAlertDialogBuilder titled(String titleZh, String titleEn) {
        return new MaterialAlertDialogBuilder(this).setCustomTitle(header(titleZh, titleEn).getRoot());
    }

    private void showFailure(Throwable failure) {
        setBusy(false);
        // The only way out of a failure is the button below; an unsaved-changes prompt on top of it would trap
        // the user in a loop they cannot answer.
        leaving = true;
        titled("操作失败", "Failed")
                .setMessage(Job.describe(failure))
                .setPositiveButton("返回", (dialog, which) -> finish())
                .setCancelable(false)
                .show();
    }

    /** Distinct cache directory per archive, so two details screens cannot read each other's copy. */
    private static String key(File archive) {
        String path = archive.getAbsolutePath();
        return Integer.toHexString(path.hashCode()) + "-" + archive.length();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** {@link #render()} is called from the receiver, which already runs on the main thread. */
    private void post(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
        } else {
            main.post(action);
        }
    }
}

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

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.frenetic.litechunk.api.LevelField;
import com.frenetic.litechunk.api.LevelInfo;
import com.frenetic.litechunk.api.LevelNote;
import com.frenetic.litechunk.databinding.ActivityLevelBinding;
import com.frenetic.litechunk.databinding.DialogLevelTextBinding;
import com.frenetic.litechunk.databinding.RowLevelFieldBinding;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;

import java.io.File;
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

        binding.btnBack.setOnClickListener(view -> finish());
        binding.btnSave.setOnClickListener(view -> confirmSave());
        binding.tvName.setText(archive.getName());
        binding.tvPath.setText(archive.getAbsolutePath());

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
                writeBack(data == null ? null : data.getString(Job.KEY_LEVEL_BACKUP));
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
        binding.btnSave.setEnabled(!pending.isEmpty());
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
        new MaterialAlertDialogBuilder(this)
                .setTitle(field.labelZh + "\n" + field.labelEn)
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
        new MaterialAlertDialogBuilder(this)
                .setTitle(field.labelZh + "\n" + field.labelEn)
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

    private void confirmSave() {
        if (pending.isEmpty()) {
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("保存修改")
                .setMessage("将修改 " + pending.size() + " 项。\n\n"
                        + "只会重写 level.dat，不会重新编码区块，存档里的方块、实体和地图都保持不变。\n\n"
                        + "原 level.dat 会备份到 output/backup/；存档本身也是写进临时文件再替换，"
                        + "中途失败不会损坏原文件。")
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (dialog, which) -> save())
                .show();
    }

    private void save() {
        setBusy(true);
        Controller.get(this).applyLevel(workDirectory, new LinkedHashMap<>(pending), receiver);
    }

    /** Put the edited file back into the archive once the engine has verified it. */
    private void writeBack(String backup) {
        File levelDat = new File(workDirectory, "level.dat");
        io.execute(() -> {
            try {
                Archive.writeBack(archive, entryName, levelDat);
                // The engine's backup lives in the scratch directory, which is about to be deleted. Move it
                // somewhere the user can reach before that happens: the dialog promised them a backup, and a
                // promise kept in a directory the system clears is not kept.
                File kept = keepBackup(backup);
                WorldFiles.delete(workDirectory);
                post(() -> {
                    setBusy(false);
                    pending.clear();
                    String message = "已保存到 " + archive.getName();
                    if (kept != null) {
                        message += "\n原 level.dat 备份：output/backup/" + kept.getName();
                    }
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                    Store.get(this).refreshOutputs();
                    finish();
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
     * Copy the engine's {@code level.dat.bak-<stamp>} out of the scratch directory.
     * <p>
     * A rename would be cheaper but the scratch directory is internal storage and the output folder is external,
     * so the two may sit on different volumes.
     */
    private File keepBackup(String stamp) {
        if (stamp == null) {
            return null;
        }
        File source = new File(workDirectory, "level.dat.bak-" + stamp);
        if (!source.isFile()) {
            return null;
        }
        File directory = new File(Paths.output(this), "backup");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            return null;
        }
        File target = new File(directory,
                WorldFiles.stripArchiveExtension(archive.getName()) + "-level.dat.bak-" + stamp);
        try (java.io.InputStream in = new java.io.FileInputStream(source);
             java.io.OutputStream out = new java.io.FileOutputStream(target)) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return target;
        } catch (java.io.IOException failure) {
            return null;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Plumbing
    // ---------------------------------------------------------------------------------------------

    private void setBusy(boolean busy) {
        binding.progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        binding.btnSave.setEnabled(!busy && !pending.isEmpty());
    }

    private void showFailure(Throwable failure) {
        setBusy(false);
        new MaterialAlertDialogBuilder(this)
                .setTitle("操作失败")
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

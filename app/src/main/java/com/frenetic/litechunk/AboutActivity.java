package com.frenetic.litechunk;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.frenetic.litechunk.databinding.ActivityAboutBinding;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The about page: what the app is, how to reach the developer, and the licence.
 * <p>
 * "Check for updates" talks to the GitHub releases API and compares the tag with {@code BuildConfig.VERSION_NAME}.
 * The app is not distributed through a store, so GitHub is the only place a newer build can come from -- and the only
 * honest source to ask. Anything that is not a clean 200 (no network, repo not published yet) is reported as "cannot
 * check right now" with a link, never as "up to date".
 */
public class AboutActivity extends AppCompatActivity {

    private static final String GITHUB_PROFILE = "https://github.com/FreneticWind60";
    private static final String BILIBILI_PROFILE = "https://space.bilibili.com/1688072577";



    private ActivityAboutBinding binding;
    private final ExecutorService io = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "litechunk-about");
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityAboutBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        binding.tvVersion.setText(Text.get(R.string.AboutActivity_01) + BuildConfig.VERSION_NAME);
        binding.btnBack.setOnClickListener(view -> finish());
        binding.btnCheckUpdate.setOnClickListener(view -> checkForUpdates());
        binding.rowGithub.setOnClickListener(view -> openLink(GITHUB_PROFILE));
        binding.rowBilibili.setOnClickListener(view -> openLink(BILIBILI_PROFILE));
        binding.rowLanguage.setOnClickListener(view -> chooseLanguage());
        updateLanguageLabel();
        binding.rowLimits.setOnClickListener(view -> chooseLimits());
        updateLimitsLabel();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdown();
    }

    // ---------------------------------------------------------------------------------------------
    // Language
    // ---------------------------------------------------------------------------------------------

    /** The current choice sits on the row itself, so it does not take a dialog to find out. */
    private void updateLanguageLabel() {
        binding.tvLanguage.setText(Language.label(Language.index(this)) + " \u203A");
    }

    /**
     * "Follow the system" plus the two translations. Changing it recreates the activity stack through
     * {@link androidx.appcompat.app.AppCompatDelegate#setApplicationLocales}, so nothing has to be refreshed by hand.
     */
    private void chooseLanguage() {
        View header = getLayoutInflater().inflate(R.layout.dialog_header, null);
        ((TextView) header.findViewById(R.id.tvTitle)).setText(R.string.language_title);
        ((TextView) header.findViewById(R.id.tvSubtitle)).setText(R.string.language_subtitle);

        String[] labels = new String[Language.TAGS.length];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = Language.label(i);
        }

        new MaterialAlertDialogBuilder(this)
                .setCustomTitle(header)
                .setSingleChoiceItems(labels, Language.index(this), (dialog, which) -> {
                    dialog.dismiss();
                    Language.save(this, which);
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    // ---------------------------------------------------------------------------------------------
    // Size limits
    // ---------------------------------------------------------------------------------------------

    /** The active set sits on the row, so the numbers are visible without opening the sheet. */
    private void updateLimitsLabel() {
        binding.tvLimits.setText(Limits.load(this).summary() + " \u203A");
    }

    /**
     * Four thresholds behind the "this is big" prompts, plus the switch that derives them from the free space.
     * <p>
     * The sheet is a plain form rather than a preference screen: there are four numbers, they only make sense
     * together, and the recommended set has to be shown next to them to be understandable.
     */
    private void chooseLimits() {
        com.google.android.material.bottomsheet.BottomSheetDialog sheet =
                new com.google.android.material.bottomsheet.BottomSheetDialog(this);
        View content = getLayoutInflater().inflate(R.layout.sheet_limits, null);

        TextView free = content.findViewById(R.id.tvFree);
        TextView recommended = content.findViewById(R.id.tvRecommended);
        com.google.android.material.materialswitch.MaterialSwitch auto = content.findViewById(R.id.switchAuto);
        com.google.android.material.slider.Slider seekArchive = content.findViewById(R.id.seekArchive);
        com.google.android.material.slider.Slider seekFolder = content.findViewById(R.id.seekFolder);
        com.google.android.material.slider.Slider seekIn = content.findViewById(R.id.seekExtractIn);
        com.google.android.material.slider.Slider seekOut = content.findViewById(R.id.seekExtractOut);
        TextView inputArchive = content.findViewById(R.id.inputArchive);
        TextView inputFolder = content.findViewById(R.id.inputFolder);
        TextView inputIn = content.findViewById(R.id.inputExtractIn);
        TextView inputOut = content.findViewById(R.id.inputExtractOut);

        Limits current = Limits.load(this);
        long[] suggested = Limits.recommended(this);
        long space = Space.free(this);

        free.setText(Text.get(R.string.Limits_09) + (space > 0 ? WorldFiles.humanSize(space) : "?"));
        recommended.setText(Text.get(R.string.Limits_10)
                + Limits.mb(suggested[0]) + " / " + Limits.mb(suggested[1]) + " / "
                + Limits.mb(suggested[2]) + " / " + Limits.mb(suggested[3]));

        // Ranges are deliberately wide: the limits are advice, so a user with a 2 TB drive can say "8 GB, go away".
        bind(seekArchive, inputArchive, 64, 8192, 16, Limits.mb(current.archive));
        bind(seekFolder, inputFolder, 128, 16384, 64, Limits.mb(current.folder));
        bind(seekIn, inputIn, 32, 4096, 16, Limits.mb(current.extractIn));
        bind(seekOut, inputOut, 128, 16384, 64, Limits.mb(current.extractOut));

        auto.setChecked(current.auto);
        // Turned on, the sliders show what the switch decided - and cannot be dragged.
        Runnable sync = () -> {
            boolean on = auto.isChecked();
            seekArchive.setEnabled(!on);
            seekFolder.setEnabled(!on);
            seekIn.setEnabled(!on);
            seekOut.setEnabled(!on);
            inputArchive.setEnabled(!on);
            inputFolder.setEnabled(!on);
            inputIn.setEnabled(!on);
            inputOut.setEnabled(!on);
            if (on) {
                seekArchive.setValue(snap(Limits.mb(suggested[0]), 64, 8192, 16));
                seekFolder.setValue(snap(Limits.mb(suggested[1]), 128, 16384, 64));
                seekIn.setValue(snap(Limits.mb(suggested[2]), 32, 4096, 16));
                seekOut.setValue(snap(Limits.mb(suggested[3]), 128, 16384, 64));
            }
            recommended.setVisibility(on ? View.VISIBLE : View.GONE);
        };
        auto.setOnCheckedChangeListener((button, checked) -> sync.run());
        sync.run();

        content.findViewById(R.id.btnSaveLimits).setOnClickListener(view -> {
            Limits saved = Limits.load(this);
            saved.auto = auto.isChecked();
            if (!saved.auto) {
                saved.archive = (long) seekArchive.getValue() * Limits.MB;
                saved.folder = (long) seekFolder.getValue() * Limits.MB;
                saved.extractIn = (long) seekIn.getValue() * Limits.MB;
                saved.extractOut = (long) seekOut.getValue() * Limits.MB;
            }
            saved.save(this);
            updateLimitsLabel();
            sheet.dismiss();
        });

        sheet.setContentView(content);
        sheet.show();
    }

    /**
     * Tie one slider to one text field.
     * <p>
     * Both directions, one flag: the slider writes its value into the field, the field (digits only) snaps to the
     * slider's step and moves it. Without the flag the two would ping-pong and the caret would jump on every keystroke.
     */
    private static void bind(com.google.android.material.slider.Slider slider, TextView field,
                             int min, int max, int step, long value) {
        slider.setValueFrom(min);
        slider.setValueTo(max);
        slider.setStepSize(step);

        boolean[] guard = {false};
        slider.setValue(snap(value, min, max, step));
        field.setText(String.valueOf((int) slider.getValue()));

        slider.addOnChangeListener((changed, current, fromUser) -> {
            if (guard[0]) {
                return;
            }
            guard[0] = true;
            field.setText(String.valueOf((int) current));
            guard[0] = false;
        });

        field.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(android.text.Editable text) {
                if (guard[0]) {
                    return;
                }
                long typed;
                try {
                    typed = Long.parseLong(text.toString().trim());
                } catch (NumberFormatException empty) {
                    return; // Mid-edit (the field is empty), nothing to apply yet.
                }
                guard[0] = true;
                slider.setValue(snap(typed, min, max, step));
                guard[0] = false;
            }
        });
    }

    /** @return the value rounded to the slider's step and kept inside its range. */
    private static float snap(long value, int min, int max, int step) {
        long clamped = Math.max(min, Math.min(max, value));
        long snapped = min + Math.round((double) (clamped - min) / step) * step;
        return Math.max(min, Math.min(max, snapped));
    }

    // ---------------------------------------------------------------------------------------------
    // Links
    // ---------------------------------------------------------------------------------------------

    private void openLink(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (RuntimeException ignored) {
            // No browser installed: nothing sensible to do, and a crash here would hide the whole page.
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Update check
    // ---------------------------------------------------------------------------------------------

    private void checkForUpdates() {
        binding.btnCheckUpdate.setEnabled(false);
        binding.tvUpdateStatus.setText(Text.get(R.string.AboutActivity_02));
        io.execute(() -> {
            try {
                Updates.Result result = Updates.check();
                runOnUiThread(() -> showUpdateDialog(result));
            } catch (Throwable failure) {
                runOnUiThread(this::showUnavailable);
            }
        });
    }

    /**
     * Show what GitHub has, from the point of view of the build that is running.
     * <p>
     * A stable user sees the stable release first and a preview below it, but only when that preview is genuinely
     * ahead of the stable one. A preview user sees the preview first and the stable release below, but only when that
     * stable is ahead of the preview they are on - otherwise there is nothing to tell them.
     */
    private void showUpdateDialog(Updates.Result result) {
        if (binding == null) {
            return;
        }
        binding.btnCheckUpdate.setEnabled(true);

        Updates.Version mine = Updates.Version.parse(BuildConfig.VERSION_NAME);
        if (mine == null || (result.stable == null && result.preview == null)) {
            showUnavailable();
            return;
        }

        boolean onStable = mine.isStable();
        Updates.Release primary = onStable ? result.stable : result.preview;
        Updates.Release secondary = onStable ? result.preview : result.stable;

        // Nothing of the kind the user is on: fall back to whatever is published, as the main block.
        if (primary == null) {
            primary = onStable ? result.preview : result.stable;
            secondary = null;
        }

        boolean primaryNewer = primary.version.compareTo(mine) > 0;

        if (secondary != null) {
            // A preview is only worth showing when it is ahead of the stable release; a stable is only worth showing
            // to a preview user when it is ahead of the build they are running.
            boolean wanted = onStable
                    ? secondary.version.compareTo(primary.version) > 0
                    : secondary.version.compareTo(mine) > 0;
            if (!wanted) {
                secondary = null;
            }
        }

        binding.tvUpdateStatus.setText(primaryNewer
                ? Text.get(R.string.AboutActivity_04) + primary.label()
                : Text.get(R.string.AboutActivity_03));

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int side = dp(20);
        column.setPadding(side, dp(4), side, 0);

        // The label describes the build, not the channel the user is on: a stable-only repo still has to say
        // "正式版" to someone running a preview.
        addSection(column, primary, primary.version.isStable() ? R.string.Updates_02 : R.string.Updates_03,
                result, primaryNewer, true);

        if (secondary != null) {
            addSection(column, secondary, secondary.version.isStable() ? R.string.Updates_02 : R.string.Updates_03,
                    result, secondary.version.compareTo(mine) > 0, false);
        }

        ScrollView scroll = new ScrollView(this);
        scroll.addView(column);

        new MaterialAlertDialogBuilder(this)
                .setTitle(Text.get(R.string.Updates_01))
                .setView(scroll)
                .setNegativeButton(Text.get(R.string.Updates_11), null)
                .show();
    }

    /**
     * One block of the dialog: which kind of build it is, what it changed, and a button when it is worth taking.
     *
     * @param column  the vertical container being filled.
     * @param release the build to describe.
     * @param label   "正式版" or "测试版".
     * @param result  the check result, for its notes.
     * @param newer   whether this build is ahead of the running one.
     * @param main    whether this is the block about the channel the user is on.
     */
    private void addSection(LinearLayout column, Updates.Release release, int label,
                            Updates.Result result, boolean newer, boolean main) {
        TextView kind = new TextView(this);
        kind.setText(Text.get(label));
        kind.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge);
        kind.setPadding(0, dp(14), 0, 0);
        column.addView(kind);

        TextView version = new TextView(this);
        version.setText(release.label());
        version.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
        version.setPadding(0, dp(4), 0, 0);
        column.addView(version);

        String notes = result.notes.get(Updates.key(release.tag));
        if (notes == null) {
            notes = result.notes.get(Updates.key(release.label()));
        }
        if (notes != null && !notes.isEmpty()) {
            TextView body = new TextView(this);
            body.setText(notes);
            body.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
            body.setPadding(0, dp(6), 0, 0);
            column.addView(body);
        }

        if (newer) {
            MaterialButton button = new MaterialButton(this);
            button.setText(Text.get(release.version.isStable() ? R.string.Updates_07 : R.string.Updates_09));
            button.setOnClickListener(view -> openLink(release.url));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.topMargin = dp(10);
            column.addView(button, params);
            return;
        }

        TextView status = new TextView(this);
        if (main) {
            status.setText(Text.get(R.string.Updates_05));
        } else if (release.version.isStable()) {
            status.setText(Text.get(R.string.Updates_10).replace("$1", release.label()));
        } else {
            status.setText(Text.get(R.string.Updates_08).replace("$1", release.label()));
        }
        status.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        status.setPadding(0, dp(8), 0, 0);
        column.addView(status);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
    private void showUnavailable() {
        if (binding == null) {
            return;
        }
        binding.btnCheckUpdate.setEnabled(true);
        binding.tvUpdateStatus.setText(Text.get(R.string.AboutActivity_14));
        new MaterialAlertDialogBuilder(this)
                .setTitle(Text.get(R.string.AboutActivity_15))
                .setMessage(Text.get(R.string.AboutActivity_16))
                .setNegativeButton(Text.get(R.string.AboutActivity_17), null)
                .setPositiveButton(Text.get(R.string.AboutActivity_18), (dialog, which) -> openLink(GITHUB_PROFILE))
                .show();
    }



}

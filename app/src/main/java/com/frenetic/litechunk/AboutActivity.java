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

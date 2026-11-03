package com.frenetic.litechunk;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.frenetic.litechunk.databinding.ActivityAboutBinding;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /** Where a released build would live. Kept in one place so a rename only touches this line. */
    private static final String REPO = "FreneticWind60/LiteChunk";
    private static final String RELEASE_API = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final String RELEASE_PAGE = "https://github.com/" + REPO + "/releases/latest";

    private static final Pattern TAG_NAME = Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern HTML_URL = Pattern.compile("\"html_url\"\\s*:\\s*\"([^\"]+)\"");

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
                String json = fetch(RELEASE_API);
                String tag = group(TAG_NAME, json);
                String page = group(HTML_URL, json);
                if (tag == null) {
                    throw new IOException("unexpected payload");
                }
                String published = tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag;
                // A git tag cannot contain a space, so a release is tagged "1.21.0-build1" while the app calls
                // itself "1.21.0 build1". Comparing the raw strings would make every release look newer than
                // itself and offer the user a download of what they already have.
                boolean same = squashed(published).equals(squashed(BuildConfig.VERSION_NAME));
                String url = page == null ? RELEASE_PAGE : page;
                runOnUiThread(() -> showResult(same, published, url));
            } catch (Throwable failure) {
                runOnUiThread(this::showUnavailable);
            }
        });
    }

    private void showResult(boolean upToDate, String published, String url) {
        if (binding == null) {
            return;
        }
        binding.btnCheckUpdate.setEnabled(true);
        binding.tvUpdateStatus.setText(upToDate ? Text.get(R.string.AboutActivity_03) : Text.get(R.string.AboutActivity_04) + published);
        if (upToDate) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(Text.get(R.string.AboutActivity_05))
                    .setMessage(Text.get(R.string.AboutActivity_06) + BuildConfig.VERSION_NAME + Text.get(R.string.AboutActivity_07))
                    .setPositiveButton(Text.get(R.string.AboutActivity_08), null)
                    .show();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(Text.get(R.string.AboutActivity_09))
                .setMessage(Text.get(R.string.AboutActivity_10) + published + Text.get(R.string.AboutActivity_11) + BuildConfig.VERSION_NAME)
                .setNegativeButton(Text.get(R.string.AboutActivity_12), null)
                .setPositiveButton(Text.get(R.string.AboutActivity_13), (dialog, which) -> openLink(url))
                .show();
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

    private static String fetch(String endpoint) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(8000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "LiteChunk/" + BuildConfig.VERSION_NAME);
        try {
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + code);
            }
            try (InputStream in = connection.getInputStream()) {
                byte[] buffer = new byte[8 * 1024];
                StringBuilder builder = new StringBuilder();
                int read;
                while ((read = in.read(buffer)) > 0) {
                    builder.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                }
                return builder.toString();
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String group(Pattern pattern, String json) {
        Matcher matcher = pattern.matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** Letters and digits only, so "1.21.0-build1", "v1.21.0 build1" and "1.21.0 build1" all compare equal. */
    private static String squashed(String value) {
        return value.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}

package com.frenetic.litechunk;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

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

        binding.tvVersion.setText("版本 " + BuildConfig.VERSION_NAME);
        binding.btnBack.setOnClickListener(view -> finish());
        binding.btnCheckUpdate.setOnClickListener(view -> checkForUpdates());
        binding.rowGithub.setOnClickListener(view -> openLink(GITHUB_PROFILE));
        binding.rowBilibili.setOnClickListener(view -> openLink(BILIBILI_PROFILE));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdown();
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
        binding.tvUpdateStatus.setText("正在检查…");
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
        binding.tvUpdateStatus.setText(upToDate ? "已是最新版本" : "发现新版本 " + published);
        if (upToDate) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("已是最新版本")
                    .setMessage("当前版本 " + BuildConfig.VERSION_NAME + " 就是 GitHub 上最新的发布。")
                    .setPositiveButton("好的", null)
                    .show();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("发现新版本")
                .setMessage("最新发布：" + published + "\n当前版本：" + BuildConfig.VERSION_NAME)
                .setNegativeButton("稍后", null)
                .setPositiveButton("前往下载", (dialog, which) -> openLink(url))
                .show();
    }

    private void showUnavailable() {
        if (binding == null) {
            return;
        }
        binding.btnCheckUpdate.setEnabled(true);
        binding.tvUpdateStatus.setText("无法获取更新信息");
        new MaterialAlertDialogBuilder(this)
                .setTitle("暂时无法检查更新")
                .setMessage("可能是网络不通，或者 GitHub 上还没有发布版本。可以手动到主页查看。")
                .setNegativeButton("取消", null)
                .setPositiveButton("打开 GitHub", (dialog, which) -> openLink(GITHUB_PROFILE))
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

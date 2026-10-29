package com.frenetic.litechunk;

import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.tabs.TabLayoutMediator;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.frenetic.litechunk.databinding.ActivityMainBinding;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private final ExecutorService io = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "litechunk-io");
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });

    private ActivityResultLauncher<String> createDocument;
    private File pendingExport;
    private int currentNightMode;

    /**
     * Keeps the display awake for exactly as long as a conversion runs.
     * <p>
     * The whole point of the screen flag is that a conversion takes minutes and the phone is in the user's hand
     * while it does. Letting it sleep used to stall the worker until the user touched the screen again, which read
     * as "the app froze". The flag is on the window, so it only applies while this activity is visible; the wake
     * lock in {@link ConversionService} covers the background case.
     */
    private final Controller.Listener conversionListener = this::applyKeepScreenOn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Inset the whole column, not the scrolling content: that is what previously let cards slide underneath
        // the status bar and clip against the navigation bar.
        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        binding.tvVersion.setText(BuildConfig.VERSION_NAME);

        createDocument = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("application/octet-stream"), this::copyToPickedLocation);

        binding.pager.setAdapter(new PagerAdapter(this));
        binding.pager.setOffscreenPageLimit(1);
        new TabLayoutMediator(binding.tabs, binding.pager, (tab, position) -> tab.setText(
                position == 0 ? R.string.tab_convert : R.string.tab_files)).attach();

        currentNightMode = LiteChunkApp.readNightMode(this);
        binding.btnTheme.setOnClickListener(view -> cycleTheme());
        binding.btnAbout.setOnClickListener(view ->
                startActivity(new Intent(MainActivity.this, AboutActivity.class)));

        binding.pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                if (position == 1) {
                    Store.get(MainActivity.this).refreshOutputs();
                }
            }
        });

        handleIntent(getIntent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The state can change while this activity is stopped (a batch finishing in the background), and the window
        // flag is not restored automatically when the activity comes back.
        Controller.get(this).addListener(conversionListener);
        applyKeepScreenOn();
    }

    @Override
    protected void onPause() {
        Controller.get(this).removeListener(conversionListener);
        super.onPause();
    }

    private void applyKeepScreenOn() {
        boolean running = Controller.get(this).isRunning();
        if (running) {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdown();
    }

    void showFilesPage() {
        binding.pager.setCurrentItem(1, true);
    }

    void showConvertPage() {
        binding.pager.setCurrentItem(0, true);
    }

    // ---------------------------------------------------------------------------------------------
    // Incoming archives (share / open with)
    // ---------------------------------------------------------------------------------------------

    /**
     * Take a world handed over by another app and put it in the conversion list.
     * <p>
     * Two shapes arrive here. "Share" is {@code SEND} / {@code SEND_MULTIPLE}, which is what a file manager or a
     * cloud app offers. "Open with" is {@code VIEW}, which is what WeChat, QQ and browsers use when an
     * {@code .mcworld} is tapped. Neither grants a permission that outlives the activity, and the queue only stores
     * a reference to be read at conversion time, so the file is copied into {@code inbox/} before anything else —
     * see {@link Inbox} for why an archive that is not recognised there is rejected rather than listed.
     */
    private void handleIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        List<Uri> uris = incomingUris(intent);
        if (uris.isEmpty()) {
            return;
        }
        // Consume the action so a configuration change (or the activity being recreated) cannot import twice.
        intent.setAction(null);
        if (io.isShutdown()) {
            return;
        }
        android.util.Log.i("MainActivity", "importing " + uris.size() + " uri(s)");
        Toast.makeText(this, "正在导入存档…", Toast.LENGTH_SHORT).show();
        io.execute(() -> {
            List<File> accepted = new ArrayList<>();
            List<String> rejected = new ArrayList<>();
            for (Uri uri : uris) {
                String name = WorldFiles.displayName(this, uri);
                if (!Inbox.isArchive(name)) {
                    rejected.add(name);
                    continue;
                }
                try {
                    accepted.add(Inbox.accept(this, uri));
                } catch (Throwable failure) {
                    rejected.add(name + "：" + Job.describe(failure));
                }
            }
            runOnUiThread(() -> onIncoming(accepted, rejected));
        });
    }

    private void onIncoming(List<File> files, List<String> rejected) {
        if (isFinishing() || isDestroyed()) {
            android.util.Log.w("MainActivity", "import finished after the activity went away");
            return;
        }
        android.util.Log.i("MainActivity", "import finished: " + files.size() + " accepted, "
                + rejected.size() + " rejected " + rejected);
        Store store = Store.get(this);
        int added = 0;
        for (File file : files) {
            QueueItem item = QueueItem.fromLocal(file);
            if (store.add(item)) {
                added++;
                // Detection needs real files, so it runs on its own thread and reports the version back into the row.
                Controller.get(this).detect(this, item);
            }
        }
        if (added > 0) {
            // The list is the whole point of accepting a share, so show it instead of leaving the user on a page
            // where nothing appears to have happened.
            showConvertPage();
            Toast.makeText(this, added == 1 ? "已加入转化列表" : "已加入 " + added + " 个存档",
                    Toast.LENGTH_SHORT).show();
        }
        if (!rejected.isEmpty()) {
            String detail = rejected.size() == 1
                    ? rejected.get(0)
                    : rejected.get(0) + " 等 " + rejected.size() + " 个";
            Toast.makeText(this, "已忽略 " + detail, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Every URI an incoming intent carries, in the order the sender listed them.
     * <p>
     * Senders are inconsistent: {@code SEND} puts one URI in {@code EXTRA_STREAM}, {@code SEND_MULTIPLE} an
     * {@code ArrayList}, {@code VIEW} in the data field, and some only fill in the {@code ClipData}.
     */
    @SuppressWarnings("deprecation")
    private static List<Uri> incomingUris(Intent intent) {
        List<Uri> uris = new ArrayList<>();
        String action = intent.getAction();
        if (Intent.ACTION_VIEW.equals(action)) {
            if (intent.getData() != null) {
                uris.add(intent.getData());
            }
        } else if (Intent.ACTION_SEND.equals(action) || Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            Parcelable single = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (single instanceof Uri) {
                uris.add((Uri) single);
            }
            ArrayList<Parcelable> many = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (many != null) {
                for (Parcelable parcelable : many) {
                    if (parcelable instanceof Uri && !uris.contains(parcelable)) {
                        uris.add((Uri) parcelable);
                    }
                }
            }
        } else {
            return uris;
        }
        if (uris.isEmpty()) {
            ClipData clip = intent.getClipData();
            if (clip != null) {
                for (int index = 0; index < clip.getItemCount(); index++) {
                    Uri uri = clip.getItemAt(index).getUri();
                    if (uri != null && !uris.contains(uri)) {
                        uris.add(uri);
                    }
                }
            }
        }
        return uris;
    }

    // ---------------------------------------------------------------------------------------------
    // Theme
    // ---------------------------------------------------------------------------------------------

    private void cycleTheme() {
        currentNightMode = LiteChunkApp.nextNightMode(currentNightMode);
        LiteChunkApp.writeNightMode(this, currentNightMode);
        AppCompatDelegateHolder.set(currentNightMode);
        Toast.makeText(this, LiteChunkApp.describeNightMode(currentNightMode), Toast.LENGTH_SHORT).show();
    }

    /** Kept in one place so the delegate import does not drag a deprecation warning into the activity body. */
    private static final class AppCompatDelegateHolder {
        static void set(int mode) {
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(mode);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Export (copy an archive to a location the user picks)
    // ---------------------------------------------------------------------------------------------

    /**
     * Ask where to save a copy of {@code source}. The archive in {@code output/} is never moved: exporting is a
     * second copy at a path the user controls, optionally with the other extension.
     */
    void startExport(File source, String suggestedName) {
        if (source == null || !source.isFile()) {
            Toast.makeText(this, "文件已不存在", Toast.LENGTH_SHORT).show();
            return;
        }
        pendingExport = source;
        createDocument.launch(suggestedName);
    }

    private void copyToPickedLocation(Uri target) {
        File source = pendingExport;
        pendingExport = null;
        if (target == null || source == null) {
            return;
        }
        Toast.makeText(this, "正在导出…", Toast.LENGTH_SHORT).show();
        io.execute(() -> {
            try (InputStream in = new FileInputStream(source);
                 OutputStream out = getContentResolver().openOutputStream(target, "wt")) {
                if (out == null) {
                    throw new IOException("无法写入所选位置");
                }
                byte[] buffer = new byte[128 * 1024];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
                runOnUiThread(() -> Toast.makeText(this, "已导出", Toast.LENGTH_SHORT).show());
            } catch (Throwable failure) {
                runOnUiThread(() -> Toast.makeText(this, "导出失败：" + Job.describe(failure), Toast.LENGTH_LONG).show());
            }
        });
    }

    @Override
    public void onBackPressed() {
        if (binding.pager.getCurrentItem() == 1) {
            binding.pager.setCurrentItem(0, true);
            return;
        }
        super.onBackPressed();
    }

    @SuppressWarnings("unused")
    private static void unusedKeepImport(Intent ignored) {
    }

    // ---------------------------------------------------------------------------------------------
    // Pages
    // ---------------------------------------------------------------------------------------------

    private static final class PagerAdapter extends FragmentStateAdapter {

        PagerAdapter(@NonNull AppCompatActivity activity) {
            super(activity);
        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            return position == 0 ? new ConvertFragment() : new FilesFragment();
        }

        @Override
        public int getItemCount() {
            return 2;
        }
    }
}

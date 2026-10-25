package com.frenetic.litechunk;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
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

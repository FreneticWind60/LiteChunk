package com.frenetic.litechunk;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.frenetic.litechunk.databinding.FragmentFilesBinding;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.io.File;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;

/**
 * The file manager page: everything inside {@code output/}.
 * <p>
 * The five per-file actions live behind a swipe so the list stays readable, and the multi select mode mirrors MT
 * Manager (swipe across the top of a row, or tap the button in the header).
 */
public class FilesFragment extends Fragment implements Store.Listener, FileAdapter.Listener {

    private FragmentFilesBinding binding;
    private FileAdapter adapter;

    /** An archive the user picked to drop straight into {@code output/}. */
    private ActivityResultLauncher<String[]> pickArchive;
    /** A save folder, which has to be packed into a zip before it can live next to the converted ones. */
    private ActivityResultLauncher<Uri> pickFolder;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pickArchive = registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::onArchivePicked);
        pickFolder = registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), this::onFolderPicked);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentFilesBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        adapter = new FileAdapter(this);
        binding.rvFiles.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.rvFiles.setAdapter(adapter);

        binding.btnMultiselect.setOnClickListener(v -> adapter.setSelectionMode(!adapter.isSelectionMode()));
        binding.btnAddOutput.setOnClickListener(v -> showAddSheet());

        binding.btnSelectDelete.setOnClickListener(v -> {
            List<Store.OutputFile> selected = adapter.selectedFiles();
            OutputActions.confirmDelete(requireActivity(), selected, () -> {
                adapter.clearSelection();
                Store.get(requireContext()).refreshOutputs();
            });
        });
        binding.btnSelectExport.setOnClickListener(v -> {
            List<Store.OutputFile> selected = adapter.selectedFiles();
            if (selected.size() != 1) {
                Toast.makeText(requireContext(), Text.get(R.string.FilesFragment_01), Toast.LENGTH_SHORT).show();
                return;
            }
            export(selected.get(0), !selected.get(0).bedrock);
        });
        binding.btnSelectShare.setOnClickListener(v -> {
            List<Store.OutputFile> selected = adapter.selectedFiles();
            if (selected.size() != 1) {
                Toast.makeText(requireContext(), Text.get(R.string.FilesFragment_02), Toast.LENGTH_SHORT).show();
                return;
            }
            Importers.share(requireActivity(), selected.get(0).file, selected.get(0).bedrock);
        });
        binding.btnSelectCancel.setOnClickListener(v -> adapter.clearSelection());

        Store store = Store.get(requireContext());
        store.addListener(this);
        store.refreshOutputs();
        onOutputsChanged();
    }

    @Override
    public void onDestroyView() {
        Store.get(requireContext()).removeListener(this);
        binding = null;
        super.onDestroyView();
    }

    @Override
    public void onResume() {
        super.onResume();
        Store.get(requireContext()).refreshOutputs();
    }

    // ---------------------------------------------------------------------------------------------
    // Store
    // ---------------------------------------------------------------------------------------------
    // Importing a save into output/
    // ---------------------------------------------------------------------------------------------

    /**
     * Put a save into {@code output/} without converting anything.
     * <p>
     * The point is the NBT: editing a world used to mean converting it first, which is a long way round for someone
     * who only wants to open the file that is already on the device.
     */
    private void showAddSheet() {
        BottomSheetDialog sheet = new BottomSheetDialog(requireContext());
        View content = getLayoutInflater().inflate(R.layout.sheet_add_output, null);

        content.findViewById(R.id.rowArchive).setOnClickListener(v -> {
            sheet.dismiss();
            // Any MIME type: a .mcworld has none registered, and most providers report octet-stream for both.
            pickArchive.launch(new String[]{"*/*"});
        });
        content.findViewById(R.id.rowFolder).setOnClickListener(v -> {
            sheet.dismiss();
            pickFolder.launch(null);
        });

        sheet.setContentView(content);
        sheet.show();
    }

    private void onArchivePicked(@Nullable Uri uri) {
        if (uri == null || binding == null) {
            return;
        }
        Context context = requireContext();
        long size = WorldFiles.documentSize(context, uri);
        if (size > Limits.load(context).archive) {
            confirmLarge(Text.get(R.string.FilesFragment_21)
                    .replace("$1", WorldFiles.humanSize(size))
                    .replace("$2", freeSpace(context)), () -> importArchive(uri));
            return;
        }
        importArchive(uri);
    }

    /** Copy the picked archive into {@code output/} as it is. */
    private void importArchive(Uri uri) {
        if (binding == null) {
            return;
        }
        Context context = requireContext();
        Store store = Store.get(context);
        store.runOnIo(() -> {
            File target = null;
            try (java.io.BufferedInputStream in = openStream(context, uri)) {
                // The name decides nothing: a .mcworld has no registered type and some providers drop the
                // extension, so the header is what tells a zip from a rar. Every container Arcana can read is
                // accepted, because the details screen can open it - only unpacking it needs the extra code.
                String name = WorldFiles.displayName(context, uri);
                WorldFiles.Container container = WorldFiles.sniff(in);
                if (!WorldFiles.isOutputArchive(name)) {
                    if (!Archives.isSupported(container)) {
                        imported(false);
                        return;
                    }
                    name = WorldFiles.baseName(name) + extensionOf(container);
                }
                target = WorldFiles.unique(Paths.output(context), name);
                WorldFiles.copyTo(in, target);
                imported(true);
            } catch (Exception failure) {
                if (target != null) {
                    WorldFiles.delete(target);
                }
                imported(false);
            }
        });
    }

    private void onFolderPicked(@Nullable Uri uri) {
        if (uri == null || binding == null) {
            return;
        }
        Context context = requireContext();
        // Walking a tree costs a provider round trip per folder, so it happens off the UI thread - and it can take a
        // while on a big one, which is why the toast says so.
        Toast.makeText(context, Text.get(R.string.FilesFragment_24), Toast.LENGTH_SHORT).show();
        Store.get(context).runOnIo(() -> {
            long size = WorldFiles.treeSize(context, uri);
            long limit = Limits.load(context).folder;
            if (!isAdded()) {
                return;
            }
            requireActivity().runOnUiThread(() -> {
                if (binding == null) {
                    return;
                }
                if (size > limit) {
                    confirmLarge(Text.get(R.string.FilesFragment_22)
                            .replace("$1", WorldFiles.humanSize(size))
                            .replace("$2", freeSpace(context)), () -> importFolder(uri));
                } else {
                    importFolder(uri);
                }
            });
        });
    }

    /** Pack the picked folder into a zip inside {@code output/}. */
    private void importFolder(Uri uri) {
        if (binding == null) {
            return;
        }
        Context context = requireContext();
        String name = folderName(context, uri);

        Store store = Store.get(context);
        store.runOnIo(() -> {
            // A SAF tree has to become a real directory before it can be zipped: the packer walks files, not
            // document ids. Cache is the right place for the intermediate copy, and it is deleted either way.
            File staging = new File(context.getCacheDir(), "import-" + System.nanoTime());
            File target = WorldFiles.unique(Paths.output(context), name + ".zip");
            try {
                WorldFiles.copyTree(context, uri, staging);
                if (!WorldFiles.isWorld(staging)) {
                    throw new java.io.IOException("no level.dat");
                }
                WorldFiles.zip(staging, target);
                imported(true);
            } catch (Exception failure) {
                WorldFiles.delete(target);
                imported(false);
            } finally {
                WorldFiles.delete(staging);
            }
        });
    }

    /** @return the free space both prompts report, so the user can judge the copy against it. */
    private static String freeSpace(Context context) {
        long free = Space.free(context);
        return free > 0L ? WorldFiles.humanSize(free) : "?";
    }

    /**
     * Ask before moving something big into {@code output/}.
     *
     * @param message what the user is about to copy, with its size filled in.
     * @param proceed the import to run if they agree.
     */
    private void confirmLarge(String message, Runnable proceed) {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(Text.get(R.string.FilesFragment_19))
                .setMessage(message)
                .setNegativeButton(Text.get(R.string.action_cancel), null)
                .setPositiveButton(Text.get(R.string.FilesFragment_20), (dialog, which) -> proceed.run())
                .show();
    }

    /** The extension a container normally carries, so a provider that dropped it still produces a usable name. */
    private static String extensionOf(WorldFiles.Container container) {
        switch (container) {
            case RAR:
                return ".rar";
            case SEVEN_Z:
                return ".7z";
            case TAR:
                return ".tar";
            case GZIP:
                return ".tar.gz";
            default:
                return ".zip";
        }
    }

    private static java.io.BufferedInputStream openStream(Context context, Uri uri) throws java.io.IOException {
        InputStream raw = context.getContentResolver().openInputStream(uri);
        if (raw == null) {
            throw new java.io.IOException("no stream for " + uri);
        }
        return new java.io.BufferedInputStream(raw);
    }

    /** The SFTP-ish display name of a tree is unusable, so ask the document itself. */
    private static String folderName(Context context, Uri uri) {
        try {
            androidx.documentfile.provider.DocumentFile root =
                    androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri);
            String name = root == null ? null : root.getName();
            if (name != null && !name.trim().isEmpty()) {
                return WorldFiles.baseName(name);
            }
        } catch (RuntimeException ignored) {
            // Fall through to the generic name.
        }
        return "world";
    }

    private void imported(boolean ok) {
        if (!isAdded()) {
            return;
        }
        requireActivity().runOnUiThread(() -> {
            if (binding == null) {
                return;
            }
            Store.get(requireContext()).refreshOutputs();
            Toast.makeText(requireContext(), Text.get(ok ? R.string.FilesFragment_16 : R.string.FilesFragment_17),
                    Toast.LENGTH_SHORT).show();
        });
    }

    // ---------------------------------------------------------------------------------------------

    @Override
    public void onQueueChanged() {
        // Nothing on this page depends on the conversion list.
    }

    @Override
    public void onOutputsChanged() {
        if (binding == null) {
            return;
        }
        List<Store.OutputFile> outputs = Store.get(requireContext()).outputs;
        adapter.submit(outputs);
        binding.empty.setVisibility(outputs.isEmpty() ? View.VISIBLE : View.GONE);
    }

    // ---------------------------------------------------------------------------------------------
    // Row actions
    // ---------------------------------------------------------------------------------------------

    @Override
    public void onEnqueue(Store.OutputFile file) {
        boolean added = Store.get(requireContext()).enqueue(file.file, file.bedrock);
        Toast.makeText(requireContext(),
                added ? Text.get(R.string.FilesFragment_03) : Text.get(R.string.FilesFragment_04), Toast.LENGTH_SHORT).show();
        // Jump to the conversion page when something actually landed: the button's whole point is the list on the
        // other page, and staying put makes it look like nothing happened.
        if (added && requireActivity() instanceof MainActivity activity) {
            activity.showConvertPage();
        }
    }

    @Override
    public void onDetails(Store.OutputFile file) {
        OutputActions.details(requireActivity(), file);
    }

    @Override
    public void onExport(Store.OutputFile file, boolean mcworld) {
        // The copy is identical, only the extension differs: mcworld is what Bedrock imports, zip is what a Java
        // launcher or a PC expects.
        export(file, mcworld);
    }

    private void export(Store.OutputFile file, boolean mcworld) {
        String name = WorldFiles.stripArchiveExtension(file.name) + (mcworld ? ".mcworld" : ".zip");
        if (requireActivity() instanceof MainActivity activity) {
            activity.startExport(file.file, name);
        }
    }

    @Override
    public void onShare(Store.OutputFile file) {
        Importers.share(requireActivity(), file.file, file.bedrock);
    }

    @Override
    public void onImport(Store.OutputFile file) {
        Importers.openInGame(requireActivity(), file.file, file.bedrock);
    }

    @Override
    public void onDelete(Store.OutputFile file) {
        List<Store.OutputFile> targets = file == null ? adapter.selectedFiles() : List.of(file);
        OutputActions.confirmDelete(requireActivity(), targets, () -> {
            adapter.clearSelection();
            Store.get(requireContext()).refreshOutputs();
        });
    }

    @Override
    public void onSelectionChanged() {
        if (binding == null) {
            return;
        }
        boolean selecting = adapter.isSelectionMode();
        binding.selectionBar.setVisibility(selecting ? View.VISIBLE : View.GONE);
        binding.tvSelectionCount.setText(selecting ? Text.get(R.string.FilesFragment_05) + adapter.selectedCount() + Text.get(R.string.FilesFragment_06) : "");
        binding.btnSelectExport.setEnabled(adapter.selectedCount() == 1);
        binding.btnSelectShare.setEnabled(adapter.selectedCount() == 1);
        binding.btnSelectDelete.setEnabled(adapter.selectedCount() > 0);
        // The header doubles as the gesture legend, so it has to change with the mode instead of leaving the user to
        // work out why swiping stopped opening the strip.
        binding.tvHint.setText(selecting
                ? Text.get(R.string.FilesFragment_07)
                : Text.get(R.string.FilesFragment_08));
        binding.btnMultiselect.setColorFilter(selecting
                ? ContextCompat.getColor(requireContext(), R.color.accent)
                : ContextCompat.getColor(requireContext(), R.color.text_dim));
        binding.btnMultiselect.setContentDescription(selecting ? Text.get(R.string.FilesFragment_09) : Text.get(R.string.FilesFragment_10));
    }
}

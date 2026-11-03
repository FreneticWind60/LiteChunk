package com.frenetic.litechunk;

import android.view.LayoutInflater;
import android.view.View;

import androidx.fragment.app.FragmentActivity;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.List;

/**
 * The five things a finished archive can be used for, shared by the result list on the conversion page and the
 * output file manager, so both behave identically.
 */
final class OutputActions {

    interface OnDeleted {
        void onDeleted();
    }

    private OutputActions() {
    }

    static void showSheet(FragmentActivity activity, Store.OutputFile file) {
        BottomSheetDialog sheet = new BottomSheetDialog(activity);
        View content = LayoutInflater.from(activity).inflate(R.layout.sheet_output_actions, null);
        content.<android.widget.TextView>findViewById(R.id.tvTitle).setText(file.name);
        content.<android.widget.TextView>findViewById(R.id.tvSubtitle).setText(
                file.editionLabel() + " · " + WorldFiles.humanSize(file.size));

        content.findViewById(R.id.rowEnqueue).setOnClickListener(view -> {
            sheet.dismiss();
            enqueue(activity, file);
        });
        content.findViewById(R.id.rowDetails).setOnClickListener(view -> {
            sheet.dismiss();
            details(activity, file);
        });
        content.findViewById(R.id.rowExportZip).setOnClickListener(view -> {
            sheet.dismiss();
            export(activity, file, false);
        });
        View exportMcworld = content.findViewById(R.id.rowExportMcworld);
        exportMcworld.setVisibility(file.bedrock ? View.VISIBLE : View.GONE);
        exportMcworld.setOnClickListener(view -> {
            sheet.dismiss();
            export(activity, file, true);
        });
        content.findViewById(R.id.rowShare).setOnClickListener(view -> {
            sheet.dismiss();
            share(activity, file);
        });
        content.findViewById(R.id.rowImport).setOnClickListener(view -> {
            sheet.dismiss();
            importGame(activity, file);
        });
        content.findViewById(R.id.rowDelete).setOnClickListener(view -> {
            sheet.dismiss();
            confirmDelete(activity, java.util.Collections.singletonList(file), null);
        });

        sheet.setContentView(content);
        sheet.show();
    }

    /** Open the details screen, which reads {@code level.dat} out of the archive into cache. */
    static void details(FragmentActivity activity, Store.OutputFile file) {
        activity.startActivity(new android.content.Intent(activity, LevelActivity.class)
                .putExtra(LevelActivity.EXTRA_PATH, file.file.getAbsolutePath()));
    }

    static void enqueue(FragmentActivity activity, Store.OutputFile file) {
        boolean added = Store.get(activity).enqueue(file.file, file.bedrock);
        android.widget.Toast.makeText(activity,
                added ? Text.get(R.string.OutputActions_01) : Text.get(R.string.OutputActions_02), android.widget.Toast.LENGTH_SHORT).show();
        if (added) {
            ((MainActivity) activity).showConvertPage();
        }
    }

    /** Export as a copy with the requested extension; the content is identical, only the name changes. */
    static void export(FragmentActivity activity, Store.OutputFile file, boolean mcworld) {
        String name = WorldFiles.baseName(file.name) + (mcworld ? ".mcworld" : ".zip");
        ((MainActivity) activity).startExport(file.file, name);
    }

    static void share(FragmentActivity activity, Store.OutputFile file) {
        Importers.share(activity, file.file, file.bedrock);
    }

    static void importGame(FragmentActivity activity, Store.OutputFile file) {
        Importers.openInGame(activity, file.file, file.bedrock);
    }

    static void confirmDelete(FragmentActivity activity, List<Store.OutputFile> files, OnDeleted callback) {
        if (files.isEmpty()) {
            return;
        }
        String message = files.size() == 1
                ? Text.get(R.string.OutputActions_03) + files.get(0).name + Text.get(R.string.OutputActions_04)
                : Text.get(R.string.OutputActions_05) + files.size() + Text.get(R.string.OutputActions_06);
        new MaterialAlertDialogBuilder(activity)
                .setTitle(Text.get(R.string.OutputActions_07))
                .setMessage(message)
                .setNegativeButton(Text.get(R.string.OutputActions_08), null)
                .setPositiveButton(Text.get(R.string.OutputActions_09), (dialog, which) -> {
                    Store.get(activity).deleteOutputs(files);
                    if (callback != null) {
                        callback.onDeleted();
                    }
                })
                .show();
    }

    /** Wrap a file produced by this round so the shared actions can be reused for it. */
    static Store.OutputFile wrap(File file) {
        boolean bedrock = file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".mcworld")
                || WorldFiles.isBedrockArchive(file);
        return new Store.OutputFile(file, file.isFile() ? file.length() : 0L, bedrock);
    }
}

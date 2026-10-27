package com.frenetic.litechunk;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.frenetic.litechunk.databinding.FragmentFilesBinding;

import java.util.List;

/**
 * The file manager page: everything inside {@code output/}.
 * <p>
 * The five per-file actions live behind a swipe so the list stays readable, and the multi select mode mirrors MT
 * Manager (swipe across the top of a row, or tap the button in the header).
 */
public class FilesFragment extends Fragment implements Store.Listener, FileAdapter.Listener {

    private FragmentFilesBinding binding;
    private FileAdapter adapter;

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
                Toast.makeText(requireContext(), "导出一次只能选一个存档", Toast.LENGTH_SHORT).show();
                return;
            }
            export(selected.get(0), !selected.get(0).bedrock);
        });
        binding.btnSelectShare.setOnClickListener(v -> {
            List<Store.OutputFile> selected = adapter.selectedFiles();
            if (selected.size() != 1) {
                Toast.makeText(requireContext(), "分享一次只能选一个存档", Toast.LENGTH_SHORT).show();
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
                added ? "已放入转化列表" : "该存档已在转化列表中", Toast.LENGTH_SHORT).show();
        // Jump to the conversion page when something actually landed: the button's whole point is the list on the
        // other page, and staying put makes it look like nothing happened.
        if (added && requireActivity() instanceof MainActivity activity) {
            activity.showConvertPage();
        }
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
        binding.tvSelectionCount.setText(selecting ? "已选 " + adapter.selectedCount() + " 项" : "");
        binding.btnSelectExport.setEnabled(adapter.selectedCount() == 1);
        binding.btnSelectShare.setEnabled(adapter.selectedCount() == 1);
        binding.btnSelectDelete.setEnabled(adapter.selectedCount() > 0);
        // The header doubles as the gesture legend, so it has to change with the mode instead of leaving the user to
        // work out why swiping stopped opening the strip.
        binding.tvHint.setText(selecting
                ? "点行选择，再点右上角退出多选"
                : "左滑展开操作，右滑进入多选");
        binding.btnMultiselect.setColorFilter(selecting
                ? ContextCompat.getColor(requireContext(), R.color.accent)
                : ContextCompat.getColor(requireContext(), R.color.text_dim));
        binding.btnMultiselect.setContentDescription(selecting ? "退出多选" : "多选");
    }
}

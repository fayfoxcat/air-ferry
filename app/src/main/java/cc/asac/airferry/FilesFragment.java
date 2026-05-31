package cc.asac.airferry;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class FilesFragment extends Fragment {
    private static final String TAG = "FilesFragment";

    private RecyclerView recyclerView;
    private FileRecordAdapter adapter;
    private EditText etSearch;
    private TextView tvSelectAll;
    private TextView tvEmpty;
    private SwipeRevealTouchListener swipeTouchListener;

    private boolean isMultiSelectMode = false;
    private int pendingSavePosition = -1;
    private ActivityResultLauncher<Intent> saveFileLauncher;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        saveFileLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
                        pendingSavePosition = -1;
                        return;
                    }
                    Uri uri = result.getData().getData();
                    if (uri == null) {
                        pendingSavePosition = -1;
                        return;
                    }
                    FileRecord record = null;
                    if (pendingSavePosition >= 0 && adapter != null) {
                        record = adapter.getRecordAt(pendingSavePosition);
                    }
                    if (record == null || record.filePath == null || record.filePath.isEmpty()) {
                        pendingSavePosition = -1;
                        return;
                    }
                    File srcFile = new File(record.filePath);
                    if (!srcFile.exists()) {
                        Toast.makeText(getContext(), R.string.files_no_file_to_share, Toast.LENGTH_SHORT).show();
                        pendingSavePosition = -1;
                        return;
                    }
                    try (InputStream is = new FileInputStream(srcFile);
                         OutputStream os = requireActivity().getContentResolver().openOutputStream(uri)) {
                        byte[] buf = new byte[8192];
                        int len;
                        while ((len = is.read(buf)) > 0) {
                            os.write(buf, 0, len);
                        }
                        os.flush();
                        Toast.makeText(getContext(), R.string.save_success, Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        Toast.makeText(getContext(), e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                    pendingSavePosition = -1;
                }
        );
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_files, container, false);

        TextView tvTitle = view.findViewById(R.id.tv_files_title);
        ViewCompat.setOnApplyWindowInsetsListener(tvTitle, (v, insets) -> {
            Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), sys.top + 20, v.getPaddingRight(), v.getPaddingBottom());
            return insets;
        });

        tvEmpty = view.findViewById(R.id.tv_empty);

        etSearch = view.findViewById(R.id.et_search);
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                filterRecords(s.toString().trim());
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        tvSelectAll = view.findViewById(R.id.tv_select_all);
        tvSelectAll.setOnClickListener(v -> {
            if (adapter.areAllSelected()) {
                adapter.deselectAll();
                tvSelectAll.setText(R.string.files_select_all);
            } else {
                adapter.selectAll();
                tvSelectAll.setText(R.string.files_deselect_all);
            }
        });

        recyclerView = view.findViewById(R.id.rv_files);
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));

        adapter = new FileRecordAdapter(new ArrayList<>(), new FileRecordAdapter.OnFileActionListener() {
            @Override public void onSave(int position) { handleSave(position); }
            @Override public void onShare(int position) { handleShare(position); }
            @Override public void onDelete(int position) { handleDelete(position); }
            @Override public void onLongPress(int position) { enterMultiSelectMode(position); }
        });
        recyclerView.setAdapter(adapter);

        swipeTouchListener = new SwipeRevealTouchListener(recyclerView, 240);
        recyclerView.addOnItemTouchListener(swipeTouchListener);

        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(),
                new OnBackPressedCallback(true) {
                    @Override
                    public void handleOnBackPressed() {
                        if (isMultiSelectMode) {
                            exitMultiSelectMode();
                        } else {
                            setEnabled(false);
                            requireActivity().getOnBackPressedDispatcher().onBackPressed();
                            setEnabled(true);
                        }
                    }
                });

        refreshData();
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshData();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden) {
            refreshData();
        }
    }

    private void refreshData() {
        String query = etSearch != null ? etSearch.getText().toString().trim() : "";
        List<FileRecord> records = FileRecordManager.get(requireContext()).getFilteredRecords(query);
        adapter.setRecords(records);
        if (tvEmpty != null) {
            tvEmpty.setVisibility(records.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    private void filterRecords(String query) {
        if (swipeTouchListener != null) swipeTouchListener.closeActiveItem();
        if (isMultiSelectMode) exitMultiSelectMode();
        List<FileRecord> records = FileRecordManager.get(requireContext()).getFilteredRecords(query);
        adapter.setRecords(records);
        if (tvEmpty != null) {
            tvEmpty.setVisibility(records.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    private void handleSave(int position) {
        FileRecord r = adapter.getRecordAt(position);
        if (r == null || r.filePath == null || r.filePath.isEmpty() || !new File(r.filePath).exists()) {
            Toast.makeText(getContext(), R.string.files_no_file_to_share, Toast.LENGTH_SHORT).show();
            return;
        }
        pendingSavePosition = position;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_TITLE, r.fileName);
        saveFileLauncher.launch(intent);
        swipeTouchListener.closeActiveItem();
    }

    private void handleShare(int position) {
        FileRecord r = adapter.getRecordAt(position);
        if (r == null || r.filePath == null || r.filePath.isEmpty() || !new File(r.filePath).exists()) {
            Toast.makeText(getContext(), R.string.files_no_file_to_share, Toast.LENGTH_SHORT).show();
            return;
        }
        Uri uri = FileProvider.getUriForFile(requireContext(),
                requireContext().getPackageName() + ".fileprovider", new File(r.filePath));
        Intent si = new Intent(Intent.ACTION_SEND);
        si.setType(MimeTypeHelper.fromFilename(r.fileName));
        si.putExtra(Intent.EXTRA_STREAM, uri);
        si.putExtra(Intent.EXTRA_SUBJECT, r.fileName);
        si.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(si, r.fileName));
        swipeTouchListener.closeActiveItem();
    }

    private void handleDelete(int position) {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.files_action_delete)
                .setMessage(R.string.files_delete_confirm)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    FileRecordManager.get(requireContext()).deleteRecord(position);
                    refreshData();
                    swipeTouchListener.closeActiveItem();
                    Toast.makeText(getContext(), R.string.files_deleted, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void enterMultiSelectMode(int initialPosition) {
        isMultiSelectMode = true;
        adapter.setMultiSelectMode(true);
        adapter.toggleSelection(initialPosition);
        tvSelectAll.setVisibility(View.VISIBLE);
        tvSelectAll.setText(R.string.files_select_all);
        swipeTouchListener.closeActiveItem();

        Activity activity = requireActivity();
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).setMultiActionBarVisible(true);
        }
        setupMultiActionBar();
    }

    private void setupMultiActionBar() {
        Activity activity = getActivity();
        if (activity == null) return;
        View saveBtn = activity.findViewById(R.id.btn_multi_save);
        View deleteBtn = activity.findViewById(R.id.btn_multi_delete);
        View shareBtn = activity.findViewById(R.id.btn_multi_share);
        if (saveBtn != null) {
            saveBtn.setOnClickListener(v -> multiSave());
        }
        if (deleteBtn != null) {
            deleteBtn.setOnClickListener(v -> multiDelete());
        }
        if (shareBtn != null) {
            shareBtn.setOnClickListener(v -> multiShare());
        }
    }

    private void multiSave() {
        Set<Integer> selected = adapter.getSelectedPositions();
        if (selected.isEmpty()) {
            Toast.makeText(getContext(), R.string.files_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        int pos = selected.iterator().next();
        swipeTouchListener.closeActiveItem();
        exitMultiSelectMode();
        handleSave(pos);
    }

    private void multiShare() {
        Set<Integer> selected = adapter.getSelectedPositions();
        if (selected.isEmpty()) return;
        ArrayList<Uri> uris = new ArrayList<>();
        for (int pos : selected) {
            FileRecord r = adapter.getRecordAt(pos);
            if (r != null && r.filePath != null && !r.filePath.isEmpty()) {
                File f = new File(r.filePath);
                if (f.exists()) {
                    Uri uri = FileProvider.getUriForFile(requireContext(),
                            requireContext().getPackageName() + ".fileprovider", f);
                    uris.add(uri);
                }
            }
        }
        if (uris.isEmpty()) {
            Toast.makeText(getContext(), R.string.files_no_file_to_share, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent si;
        if (uris.size() == 1) {
            si = new Intent(Intent.ACTION_SEND);
            si.setType(MimeTypeHelper.fromFilename(adapter.getRecordAt(selected.iterator().next()).fileName));
            si.putExtra(Intent.EXTRA_STREAM, uris.get(0));
        } else {
            si = new Intent(Intent.ACTION_SEND_MULTIPLE);
            si.setType("*/*");
            si.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        }
        si.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(si, getString(R.string.files_action_share)));
        exitMultiSelectMode();
    }

    private void multiDelete() {
        Set<Integer> selected = adapter.getSelectedPositions();
        if (selected.isEmpty()) return;
        int count = selected.size();
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.files_action_delete)
                .setMessage(getString(R.string.files_delete_multi_confirm, count))
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    FileRecordManager.get(requireContext()).deleteRecords(selected);
                    exitMultiSelectMode();
                    refreshData();
                    Toast.makeText(getContext(),
                            getString(R.string.files_multi_deleted, count),
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void exitMultiSelectMode() {
        isMultiSelectMode = false;
        adapter.setMultiSelectMode(false);
        tvSelectAll.setVisibility(View.GONE);

        Activity activity = getActivity();
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).setMultiActionBarVisible(false);
        }
    }

}
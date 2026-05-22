package cc.asac.airferry;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class FileRecordAdapter extends RecyclerView.Adapter<FileRecordAdapter.ViewHolder> {

    private List<FileRecord> records;
    private final Set<Integer> selectedPositions = new HashSet<>();
    private boolean isMultiSelectMode = false;
    private final OnFileActionListener listener;

    public interface OnFileActionListener {
        void onSave(int position);
        void onShare(int position);
        void onDelete(int position);
        void onLongPress(int position);
    }

    public FileRecordAdapter(List<FileRecord> records, OnFileActionListener listener) {
        this.records = records;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_file_record, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        FileRecord record = records.get(position);
        holder.position = position;
        holder.tvFileName.setText(record.fileName);
        holder.tvMeta.setText(formatMeta(record));

        holder.cbSelect.setChecked(selectedPositions.contains(position));
        holder.cbSelect.setVisibility(isMultiSelectMode ? View.VISIBLE : View.GONE);

        holder.itemContent.setOnLongClickListener(v -> {
            if (listener != null) listener.onLongPress(holder.getAdapterPosition());
            return true;
        });

        holder.itemContent.setOnClickListener(v -> {
            if (isMultiSelectMode) {
                toggleSelection(holder.getAdapterPosition());
            }
        });

        holder.btnSave.setOnClickListener(v -> {
            if (listener != null) listener.onSave(holder.getAdapterPosition());
        });
        holder.btnShare.setOnClickListener(v -> {
            if (listener != null) listener.onShare(holder.getAdapterPosition());
        });
        holder.btnDelete.setOnClickListener(v -> {
            if (listener != null) listener.onDelete(holder.getAdapterPosition());
        });
    }

    @Override
    public int getItemCount() {
        return records.size();
    }

    public void setRecords(List<FileRecord> newRecords) {
        this.records = newRecords;
        selectedPositions.clear();
        notifyDataSetChanged();
    }

    public FileRecord getRecordAt(int position) {
        if (position < 0 || position >= records.size()) return null;
        return records.get(position);
    }

    public void setMultiSelectMode(boolean enabled) {
        isMultiSelectMode = enabled;
        if (!enabled) selectedPositions.clear();
        notifyDataSetChanged();
    }

    public void toggleSelection(int position) {
        if (position < 0 || position >= records.size()) return;
        if (selectedPositions.contains(position)) {
            selectedPositions.remove(position);
        } else {
            selectedPositions.add(position);
        }
        notifyItemChanged(position);
    }

    public void selectAll() {
        selectedPositions.clear();
        for (int i = 0; i < records.size(); i++) {
            selectedPositions.add(i);
        }
        notifyDataSetChanged();
    }

    public void deselectAll() {
        selectedPositions.clear();
        notifyDataSetChanged();
    }

    public boolean areAllSelected() {
        return selectedPositions.size() == records.size() && !records.isEmpty();
    }

    public int getSelectedCount() {
        return selectedPositions.size();
    }

    public Set<Integer> getSelectedPositions() {
        return new HashSet<>(selectedPositions);
    }

    private String formatMeta(FileRecord r) {
        return formatTimestamp(r.timestamp) + "  |  " + formatFileSize(r.fileSize) + "  |  " + formatDuration(r.decodeDurationMs);
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private String formatTimestamp(long millis) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        return sdf.format(new Date(millis));
    }

    private String formatDuration(long ms) {
        if (ms < 1000) return ms + "ms";
        if (ms < 60000) return String.format(Locale.US, "%.1fs", ms / 1000.0);
        long minutes = ms / 60000;
        long seconds = (ms % 60000) / 1000;
        return String.format(Locale.US, "%dm %ds", minutes, seconds);
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final FrameLayout itemContent;
        final CheckBox cbSelect;
        final TextView tvFileName;
        final TextView tvMeta;
        final TextView btnSave;
        final TextView btnShare;
        final TextView btnDelete;
        int position;

        ViewHolder(View itemView) {
            super(itemView);
            itemContent = itemView.findViewById(R.id.item_content);
            cbSelect = itemView.findViewById(R.id.cb_select);
            tvFileName = itemView.findViewById(R.id.tv_file_name);
            tvMeta = itemView.findViewById(R.id.tv_meta);
            btnSave = itemView.findViewById(R.id.btn_action_save);
            btnShare = itemView.findViewById(R.id.btn_action_share);
            btnDelete = itemView.findViewById(R.id.btn_action_delete);
        }
    }
}
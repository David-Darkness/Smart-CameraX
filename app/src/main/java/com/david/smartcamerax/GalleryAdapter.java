package com.david.smartcamerax;

import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

public class GalleryAdapter extends ListAdapter<MediaItem, GalleryAdapter.ViewHolder> {

    public interface Listener {
        void onItemClick(MediaItem item, int position);
    }

    private final Listener listener;

    // DIFF callback estático
    private static final DiffUtil.ItemCallback<MediaItem> DIFF = new DiffUtil.ItemCallback<>() {
        @Override
        public boolean areItemsTheSame(@NonNull MediaItem oldItem, @NonNull MediaItem newItem) {
            return oldItem.uri.equals(newItem.uri);
        }

        @Override
        public boolean areContentsTheSame(@NonNull MediaItem oldItem, @NonNull MediaItem newItem) {
            return oldItem.equals(newItem);
        }
    };

    public GalleryAdapter(Listener listener) {
        super(DIFF);
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_gallery, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        MediaItem item = getItem(position);
        holder.bind(item);
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onItemClick(item, position);
        });
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        private final ImageView iv;
        private final ImageView ivBadge;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            iv = itemView.findViewById(R.id.iv_thumb);
            ivBadge = itemView.findViewById(R.id.iv_video_badge);
        }

        void bind(MediaItem item) {
            Uri uri = item.uri;
            iv.setImageURI(uri); // para simplificar; se puede reemplazar por Glide para mejor performance
            if (ivBadge != null) {
                ivBadge.setVisibility(item.isVideo ? View.VISIBLE : View.GONE);
            }
        }
    }
}

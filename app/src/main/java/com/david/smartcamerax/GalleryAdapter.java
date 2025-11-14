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

public class GalleryAdapter extends ListAdapter<Uri, GalleryAdapter.ViewHolder> {

    public interface Listener {
        void onItemClick(Uri imageUri, int position);
    }

    private final Listener listener;

    // DIFF callback estático
    private static final DiffUtil.ItemCallback<Uri> DIFF = new DiffUtil.ItemCallback<>() {
        @Override
        public boolean areItemsTheSame(@NonNull Uri oldItem, @NonNull Uri newItem) {
            return oldItem.equals(newItem);
        }

        @Override
        public boolean areContentsTheSame(@NonNull Uri oldItem, @NonNull Uri newItem) {
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
        Uri uri = getItem(position);
        holder.bind(uri);
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onItemClick(uri, position);
        });
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        private final ImageView iv;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            iv = itemView.findViewById(R.id.iv_thumb);
        }

        void bind(Uri uri) {
            // setImageURI simple para thumbs; se puede mejorar con Glide/Picasso
            iv.setImageURI(uri);
        }
    }
}

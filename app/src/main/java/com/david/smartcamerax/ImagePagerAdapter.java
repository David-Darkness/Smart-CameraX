package com.david.smartcamerax;

import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class ImagePagerAdapter extends RecyclerView.Adapter<ImagePagerAdapter.VH> {

    private final List<String> items;

    public ImagePagerAdapter(List<String> items) {
        this.items = items;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_image_pager, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        String s = items.get(position);
        if (s != null) {
            Uri uri = Uri.parse(s);
            holder.iv.setImageURI(uri);
        } else {
            holder.iv.setImageURI(null);
        }
    }

    @Override
    public int getItemCount() {
        return items == null ? 0 : items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView iv;

        VH(@NonNull View itemView) {
            super(itemView);
            iv = itemView.findViewById(R.id.iv_page);
        }
    }
}


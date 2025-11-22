package com.david.smartcamerax;

import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.VideoView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class ImagePagerAdapter extends RecyclerView.Adapter<ImagePagerAdapter.VH> {

    private final List<String> items;
    private final boolean[] videoFlags;

    public ImagePagerAdapter(List<String> items, boolean[] videoFlags) {
        this.items = items;
        this.videoFlags = videoFlags;
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
        boolean isVideo = videoFlags != null && position < videoFlags.length && videoFlags[position];
        if (s != null) {
            Uri uri = Uri.parse(s);
            if (isVideo) {
                holder.iv.setVisibility(View.GONE);
                holder.vv.setVisibility(View.VISIBLE);
                holder.vv.setVideoURI(uri);
                holder.vv.start();
            } else {
                holder.vv.setVisibility(View.GONE);
                holder.iv.setVisibility(View.VISIBLE);
                holder.iv.setImageURI(uri);
            }
        } else {
            holder.iv.setImageURI(null);
            holder.vv.setVideoURI(null);
        }
    }

    @Override
    public int getItemCount() {
        return items == null ? 0 : items.size();
    }

    @Override
    public void onViewRecycled(@NonNull VH holder) {
        super.onViewRecycled(holder);
        try { holder.vv.stopPlayback(); } catch (Exception ignored) {}
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView iv;
        final VideoView vv;
        VH(@NonNull View itemView) {
            super(itemView);
            iv = itemView.findViewById(R.id.iv_page);
            vv = itemView.findViewById(R.id.vv_page);
        }
    }
}

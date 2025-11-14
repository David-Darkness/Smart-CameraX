package com.david.smartcamerax;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.viewpager2.widget.ViewPager2;

import java.util.ArrayList;

public class ImageViewerActivity extends AppCompatActivity {

    private static final String EXTRA_LIST = "extra_image_list";
    private static final String EXTRA_POS = "extra_image_pos";

    public static void start(Context ctx, ArrayList<String> uris, int position) {
        Intent i = new Intent(ctx, ImageViewerActivity.class);
        i.putStringArrayListExtra(EXTRA_LIST, uris);
        i.putExtra(EXTRA_POS, position);
        ctx.startActivity(i);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_image_viewer);

        ViewPager2 vp = findViewById(R.id.vp_images);
        ImageButton btnClose = findViewById(R.id.btn_close);

        ArrayList<String> items = getIntent().getStringArrayListExtra(EXTRA_LIST);
        int pos = getIntent().getIntExtra(EXTRA_POS, 0);

        if (items == null) items = new ArrayList<>();

        ImagePagerAdapter adapter = new ImagePagerAdapter(items);
        vp.setAdapter(adapter);
        vp.setCurrentItem(Math.max(0, Math.min(pos, items.size() - 1)), false);

        btnClose.setOnClickListener(v -> finish());
    }
}

package com.example.ponycui_home.svgaplayer;

import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import android.widget.Toast;

import com.opensource.svgaplayer.SVGAClickAreaListener;
import com.opensource.svgaplayer.SVGADrawable;
import com.opensource.svgaplayer.SVGAImageView;
import com.opensource.svgaplayer.coil3.SvgaViewLoaderKt;
import com.opensource.svgaplayer.loader.SvgaSource;
import kotlin.Unit;

import org.jetbrains.annotations.NotNull;


/**
 * Created by miaojun on 2019/6/21.
 * mail:1290846731@qq.com
 */
public class AnimationFromClickActivity extends AppCompatActivity {

    SVGAImageView animationView = null;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        animationView = new SVGAImageView(this);
        animationView.setOnAnimKeyClickListener(new SVGAClickAreaListener() {
            @Override
            public void onClick(@NotNull String clickKey) {
                Toast.makeText(AnimationFromClickActivity.this,clickKey,Toast.LENGTH_SHORT).show();
            }
        });
        animationView.setBackgroundColor(Color.WHITE);
        setContentView(animationView);
        loadAnimation();
    }

    private void loadAnimation() {
        SvgaViewLoaderKt.loadSvga(animationView, new SvgaSource.Asset("MerryChristmas.svga"), options -> {
            options.setOnReady(() -> {
                SVGADrawable drawable = (SVGADrawable) animationView.getDrawable();
                drawable.getDynamicItem().setClickArea("img_10");
                return Unit.INSTANCE;
            });
            options.setOnError(error -> {
                Log.e("SvgaClick", "Asset load failed", error);
                return Unit.INSTANCE;
            });
            return Unit.INSTANCE;
        });
    }

}


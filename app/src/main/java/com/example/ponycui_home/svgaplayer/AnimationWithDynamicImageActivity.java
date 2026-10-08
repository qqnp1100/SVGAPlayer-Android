package com.example.ponycui_home.svgaplayer;

import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.opensource.svgaplayer.SVGAImageView;
import com.opensource.svgaplayer.coil3.SvgaBindings;
import com.opensource.svgaplayer.coil3.SvgaViewLoaderKt;
import kotlin.Unit;

public class AnimationWithDynamicImageActivity extends AppCompatActivity {

    SVGAImageView animationView = null;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        animationView = new SVGAImageView(this);
        animationView.setBackgroundColor(Color.GRAY);
        setContentView(animationView);
        loadAnimation();
    }

    private void loadAnimation() {
        SvgaBindings.Builder bindings = new SvgaBindings.Builder();
        bindings.image("99",
                "https://github.com/PonyCui/resources/blob/master/svga_replace_avatar.png?raw=true",
                0, true, false);
        SvgaViewLoaderKt.loadSvga(animationView,
                "https://github.com/yyued/SVGA-Samples/blob/master/kingset.svga?raw=true",
                options -> {
                    options.setBindings(bindings.build());
                    options.setOnError(error -> {
                        Log.e("SvgaDynamicImage", "Animation or dynamic image load failed", error);
                        return Unit.INSTANCE;
                    });
                    return Unit.INSTANCE;
                });
    }

}

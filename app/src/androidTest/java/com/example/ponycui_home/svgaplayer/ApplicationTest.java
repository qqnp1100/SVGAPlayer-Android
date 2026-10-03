package com.example.ponycui_home.svgaplayer;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.assertEquals;

@RunWith(AndroidJUnit4.class)
public class ApplicationTest {
    @Test public void applicationContext() {
        assertEquals("com.example.ponycui_home.svgaplayer", InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageName());
    }
}

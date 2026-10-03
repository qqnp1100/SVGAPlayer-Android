package com.example.ponycui_home.svgaplayer

import android.os.Bundle
import android.util.Log
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.opensource.svgaplayer.SVGAImageView
import com.opensource.svgaplayer.coil3.loadSvga
import com.opensource.svgaplayer.compose.*

class SvgaModernActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val urls = listOf("https://pic.vchat-onlie.com/head_bg_vip10.svga", "https://pic.vchat-onlie.com/head_bg_vip9.svga")
        if (intent.getBooleanExtra("view", false)) {
            val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xff202632.toInt()) }
            column.addView(TextView(this).apply { text = "Coil 3 · Android View"; setTextColor(-1) })
            urls.forEach { url ->
                val view = SVGAImageView(this)
                column.addView(view, LinearLayout.LayoutParams(-1, 600))
                view.loadSvga(url) { onReady = { Log.i("SvgaModern", "View ready: $url") }; onError = { Log.e("SvgaModern", "View load", it) } }
            }
            setContentView(column)
        } else setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().background(Color(0xff202632)).padding(24.dp)) {
                    Text("Native Compose · Coil 3", color = Color.White)
                    urls.forEach { url ->
                        val state = rememberSvgaState()
                        SvgaView(url, Modifier.size(220.dp), state = state,
                            contentDescription = "SVGA ${url.substringAfterLast('/')}",
                            onReady = { Log.i("SvgaModern", "Compose ready: $url") },
                            onError = { Log.e("SvgaModern", "Compose load", it) },
                            error = { Text(it.message ?: "Load error", color = Color.Red) })
                        Row {
                            Button(onClick = { state.pause() }) { Text("Pause") }
                            Button(onClick = { state.resume() }) { Text("Resume") }
                            Button(onClick = { state.replay() }) { Text("Replay") }
                        }
                    }
                }
            }
        }
    }
}

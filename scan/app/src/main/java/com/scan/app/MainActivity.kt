package com.scan.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.scan.app.ui.ScanColors
import com.scan.app.ui.ScanTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ScanTheme(lang = Lang.AR) {
                Surface(modifier = Modifier.fillMaxSize(), color = ScanColors.Bg) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("SCAN")
                    }
                }
            }
        }
    }
}

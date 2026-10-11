package com.kindle.converter

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import com.kindle.converter.ui.KindleConverterTheme
import com.kindle.converter.ui.MainTab
import com.kindle.converter.ui.MainTabScreen
import com.kindle.converter.viewmodel.ConverterViewModel
import com.kindle.converter.viewmodel.WebClipperViewModel

class MainActivity : ComponentActivity() {

    private val webViewModel: WebClipperViewModel by viewModels()
    private val converterViewModel: ConverterViewModel by viewModels()
    private val selectedTabState = mutableStateOf(MainTab.WEB)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            ),
            navigationBarStyle = androidx.activity.SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            )
        )
        handleSendIntent(intent)
        setContent {
            KindleConverterTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainTabScreen(
                        webViewModel = webViewModel,
                        converterViewModel = converterViewModel,
                        selectedTab = selectedTabState.value,
                        onTabSelected = { selectedTabState.value = it }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSendIntent(intent)
    }

    private fun handleSendIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.action == Intent.ACTION_SEND) {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrBlank()) {
                webViewModel.addUrls(sharedText)
                selectedTabState.value = MainTab.WEB
            }
        }
    }
}

package com.example.tabletpanel

import android.Manifest
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.tabletpanel.ui.PanelScreen
import kotlinx.coroutines.launch

/** Full-screen, always-on display. Long-press the clock for tablet settings. */
class MainActivity : ComponentActivity() {
    private val vm: PanelViewModel by viewModels()

    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.refreshLocation()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()

        setContent {
            PanelScreen(vm, onRequestLocation = ::requestLocation, onExit = ::finishAndRemoveTask)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    vm.brightness.collect { b ->
                        window.attributes = window.attributes.apply {
                            screenBrightness = b ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                        }
                    }
                }
                launch { vm.restartRequests.collect { recreate() } }
            }
        }

        if (savedInstanceState == null) requestLocation()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun requestLocation() {
        locationPermission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}

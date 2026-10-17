package com.kriztech.scan

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.kriztech.scan.ui.theme.KrizScanTheme

class MainActivity : ComponentActivity() {

    private val vm: DocViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        A11y.init(this)
        enableEdgeToEdge()
        handleIncomingIntent(intent)
        setContent {
            KrizScanTheme {
                App(vm)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val uris = mutableListOf<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val streamUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                if (streamUri != null) {
                    uris.add(streamUri)
                } else if (intent.data != null) {
                    uris.add(intent.data!!)
                } else if (intent.clipData != null && intent.clipData!!.itemCount > 0) {
                    uris.add(intent.clipData!!.getItemAt(0).uri)
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val streamUris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }
                if (streamUris != null) {
                    uris.addAll(streamUris.filterNotNull())
                }
                if (uris.isEmpty() && intent.clipData != null) {
                    for (i in 0 until intent.clipData!!.itemCount) {
                        uris.add(intent.clipData!!.getItemAt(i).uri)
                    }
                }
            }
            Intent.ACTION_VIEW -> {
                intent.data?.let { uris.add(it) }
            }
        }

        if (uris.isNotEmpty()) {
            val isPdf = intent.type?.equals("application/pdf", ignoreCase = true) == true ||
                uris.any { it.toString().endsWith(".pdf", ignoreCase = true) }
            vm.importFromUris(this, uris, isPdf)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Accessibility Mode: the hardware volume keys act as the shutter.
        if (A11y.active) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        A11y.captureHandler?.invoke()
                    }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        A11y.shutdown()
        super.onDestroy()
    }
}

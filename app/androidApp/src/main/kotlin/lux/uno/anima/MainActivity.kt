package lux.uno.anima

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import lux.uno.anima.platform.AndroidPlatform

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Storage and notifications need a Context once; everything downstream is Context-free.
        AndroidPlatform.initialize(this)
        requestNotificationPermission()

        setContent {
            App()
        }
    }

    /**
     * From API 33 a card expiry reminder cannot appear without this. Asking once on launch
     * keeps it out of the payment method flow, where a system dialog would interrupt typing.
     */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST)
    }

    private companion object {
        const val NOTIFICATION_PERMISSION_REQUEST = 1001
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
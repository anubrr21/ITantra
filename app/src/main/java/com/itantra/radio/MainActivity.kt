package com.itantra.radio

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import androidx.core.content.ContextCompat
import com.itantra.radio.network.TransportState
import com.itantra.radio.service.RadioService
import com.itantra.radio.ui.PairingScreen
import com.itantra.radio.ui.RadioScreen
import com.itantra.radio.ui.theme.ItantraTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.flowOf

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private var radioService by mutableStateOf<RadioService?>(null)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            radioService = (binder as RadioService.LocalBinder).getService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            radioService = null
        }
    }

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        if (results[Manifest.permission.RECORD_AUDIO] == true && radioService == null) {
            startAndBindRadioService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (hasRecordAudioPermission()) {
            startAndBindRadioService()
        }
        requestPermissions.launch(requiredPermissions())

        setContent {
            ItantraTheme {
                val service = radioService
                if (service != null) {
                    RadioApp(service)
                } else {
                    Text("Starting radio service…")
                }
            }
        }
    }

    private fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun startAndBindRadioService() {
        val intent = Intent(this, RadioService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        unbindService(connection)
        super.onDestroy()
    }

    private fun requiredPermissions(): Array<String> {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.NEARBY_WIFI_DEVICES
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms += Manifest.permission.BLUETOOTH_CONNECT
            perms += Manifest.permission.BLUETOOTH_SCAN
        }
        return perms.toTypedArray()
    }
}

@Composable
private fun RadioApp(service: RadioService) {
    val transport by service.transportFlow.collectAsState()
    val transportState by (transport?.state ?: flowOf(TransportState.Idle))
        .collectAsState(initial = TransportState.Idle)

    if (transportState is TransportState.Connected) {
        RadioScreen(service)
    } else {
        PairingScreen(
            service = service,
            hasChosenLink = transport != null,
            transportState = transportState,
            onLinkChosen = { link -> service.useLink(link) },
        )
    }
}

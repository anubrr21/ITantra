package com.itantra.radio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.itantra.radio.ptt.PttMode
import com.itantra.radio.ptt.TransmitState
import com.itantra.radio.service.RadioService

@Composable
fun RadioScreen(service: RadioService) {
    val mode by service.pttController.mode.collectAsState()
    val transmitState by service.pttController.transmitState.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("iTantra Radio", style = MaterialTheme.typography.headlineMedium)
            Text(
                text = when (transmitState) {
                    TransmitState.TRANSMITTING -> "Transmitting"
                    TransmitState.RECEIVING -> "Receiving"
                    TransmitState.IDLE -> "Idle"
                },
                style = MaterialTheme.typography.bodyLarge,
            )
        }

        Box(
            modifier = Modifier
                .size(160.dp)
                .clip(CircleShape)
                .background(
                    if (transmitState == TransmitState.TRANSMITTING) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
                .pointerInput(mode) {
                    if (mode == PttMode.PUSH_TO_TALK) {
                        detectTapGestures(
                            onPress = {
                                service.onPttPressed()
                                tryAwaitRelease()
                                service.onPttReleased()
                            },
                        )
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (mode == PttMode.PUSH_TO_TALK) "HOLD\nTO TALK" else "PHONE\nMODE",
                textAlign = TextAlign.Center,
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Phone mode")
            Switch(
                checked = mode == PttMode.PHONE_MODE,
                onCheckedChange = { checked ->
                    service.setPttMode(if (checked) PttMode.PHONE_MODE else PttMode.PUSH_TO_TALK)
                },
            )
        }
    }
}

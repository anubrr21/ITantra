package com.itantra.radio.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.itantra.radio.network.TransportPeer
import com.itantra.radio.network.TransportState
import com.itantra.radio.service.RadioLink
import com.itantra.radio.service.RadioService

@Composable
fun PairingScreen(
    service: RadioService,
    hasChosenLink: Boolean,
    transportState: TransportState,
    onLinkChosen: (RadioLink) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("iTantra", style = MaterialTheme.typography.headlineLarge)
        Text("Digital walkie-talkie radio link", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(32.dp))

        if (!hasChosenLink) {
            Button(onClick = { onLinkChosen(RadioLink.WIFI_DIRECT) }, modifier = Modifier.fillMaxWidth()) {
                Text("Connect via WiFi Direct")
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = { onLinkChosen(RadioLink.BLUETOOTH_CLASSIC) }, modifier = Modifier.fillMaxWidth()) {
                Text("Connect via Bluetooth")
            }
        } else {
            Row {
                Button(onClick = { service.host() }) { Text("Host") }
                Spacer(Modifier.width(12.dp))
                Button(onClick = { service.discover() }) { Text("Find peers") }
            }
            Spacer(Modifier.height(16.dp))
            when (transportState) {
                is TransportState.Discovering -> Text("Searching / waiting for a peer…")
                is TransportState.Connecting -> Text("Connecting…")
                is TransportState.Failed -> Text("Failed: ${transportState.reason}")
                is TransportState.PeersFound -> PeerList(transportState.peers) { peer -> service.connectTo(peer) }
                else -> {}
            }
        }
    }
}

@Composable
private fun PeerList(peers: List<TransportPeer>, onSelect: (TransportPeer) -> Unit) {
    LazyColumn {
        items(peers) { peer ->
            ListItem(
                headlineContent = { Text(peer.displayName) },
                modifier = Modifier.clickable { onSelect(peer) },
            )
        }
    }
}

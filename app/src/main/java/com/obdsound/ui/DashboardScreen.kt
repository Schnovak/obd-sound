package com.obdsound.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.obdsound.audio.CarSound
import com.obdsound.domain.model.ConnectionState
import com.obdsound.ui.theme.*

@Composable
fun DashboardScreen(viewModel: MainViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Connection status
        ConnectionStatusBar(state.connectionState) { viewModel.disconnect() }

        Spacer(Modifier.height(8.dp))

        // Car selector
        CarSelector(state.selectedCar) { viewModel.selectCar(it) }

        Spacer(Modifier.height(8.dp))

        // RPM gauge
        RpmGauge(
            rpm = if (state.demoMode) state.rpm.toInt() else state.engineData.rpm,
            modifier = Modifier.size(200.dp)
        )

        // Gear indicator
        Text(
            text = if (state.gear == 0) "N" else "${state.gear}",
            color = if (state.gear == 0) Yellow else Color.White,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(8.dp))

        // Data row
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            DataCard("SPEED", if (state.demoMode) "---" else "${state.engineData.speedKmh}", "km/h")
            DataCard("THROTTLE", if (state.demoMode) "%.0f".format(state.demoThrottle) else "%.0f".format(state.engineData.throttlePercent), "%")
        }

        Spacer(Modifier.height(8.dp))

        // Demo mode toggle
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Demo Mode", color = GrayText, fontSize = 14.sp)
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = state.demoMode,
                onCheckedChange = { viewModel.toggleDemoMode() },
                colors = SwitchDefaults.colors(checkedThumbColor = Orange)
            )
        }

        if (state.demoMode) {
            Spacer(Modifier.height(4.dp))

            // Gear shift buttons
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Shift down
                Button(
                    onClick = { viewModel.shiftDown() },
                    colors = ButtonDefaults.buttonColors(containerColor = DarkSurface),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.size(64.dp)
                ) {
                    Text("-", fontSize = 28.sp, color = Orange, fontWeight = FontWeight.Bold)
                }

                Spacer(Modifier.width(16.dp))

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("GEAR", color = GrayText, fontSize = 10.sp)
                    Text(
                        if (state.gear == 0) "N" else "${state.gear}",
                        color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold
                    )
                }

                Spacer(Modifier.width(16.dp))

                // Shift up
                Button(
                    onClick = { viewModel.shiftUp() },
                    colors = ButtonDefaults.buttonColors(containerColor = DarkSurface),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.size(64.dp)
                ) {
                    Text("+", fontSize = 28.sp, color = Orange, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.weight(1f))

            // Gas and Brake pedals
            Row(
                Modifier.fillMaxWidth().height(120.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // Brake pedal
                PedalButton(
                    label = "BRAKE",
                    color = Red,
                    pressed = state.brakePressed,
                    pressure = state.demoBrake / 100f,
                    onPress = { viewModel.onBrakePressed() },
                    onRelease = { viewModel.onBrakeReleased() },
                    modifier = Modifier.weight(1f).fillMaxHeight().padding(end = 8.dp)
                )

                // Gas pedal
                PedalButton(
                    label = "GAS",
                    color = Green,
                    pressed = state.gasPressed,
                    pressure = state.demoThrottle / 100f,
                    onPress = { viewModel.onGasPressed() },
                    onRelease = { viewModel.onGasReleased() },
                    modifier = Modifier.weight(1f).fillMaxHeight().padding(start = 8.dp)
                )
            }
        } else {
            Spacer(Modifier.height(8.dp))

            // Vehicle max RPM setting
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(DarkSurface, RoundedCornerShape(8.dp))
                    .padding(12.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Vehicle Max RPM", color = GrayText, fontSize = 12.sp)
                    Text("${state.vehicleMaxRpm}", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = state.vehicleMaxRpm.toFloat(),
                    onValueChange = { viewModel.setVehicleMaxRpm(it.toInt()) },
                    valueRange = 3000f..15000f,
                    steps = 23,  // 500 RPM increments
                    colors = SliderDefaults.colors(
                        thumbColor = Orange,
                        activeTrackColor = Orange
                    )
                )
                Text(
                    "Match to your car's redline for accurate sound",
                    color = GrayText, fontSize = 10.sp
                )
            }

            Spacer(Modifier.weight(1f))

            // Device list for OBD mode
            if (state.connectionState is ConnectionState.Disconnected ||
                state.connectionState is ConnectionState.Error
            ) {
                DeviceList(state.pairedDevices, { viewModel.connect(it) }, { viewModel.loadPairedDevices() })
            }
        }

        Spacer(Modifier.height(8.dp))

        // Start/Stop sound
        Button(
            onClick = { viewModel.toggleSound() },
            colors = ButtonDefaults.buttonColors(
                containerColor = if (state.soundPlaying) Red else Orange
            ),
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                if (state.soundPlaying) "STOP ENGINE" else "START ENGINE",
                fontSize = 18.sp, fontWeight = FontWeight.Bold, color = DarkBackground
            )
        }
    }
}

@Composable
private fun PedalButton(
    label: String,
    color: Color,
    pressed: Boolean,
    pressure: Float = if (pressed) 1f else 0f,
    onPress: () -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier
) {
    val p = pressure.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .background(DarkSurface, RoundedCornerShape(16.dp))
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        onPress()
                        tryAwaitRelease()
                        onRelease()
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        // Progressive fill from bottom
        if (p > 0f) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(p)
                    .align(Alignment.BottomCenter)
                    .background(
                        color.copy(alpha = 0.3f + 0.5f * p),
                        RoundedCornerShape(16.dp)
                    )
            )
        }
        Text(
            label,
            color = if (p > 0.5f) DarkBackground else color,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun CarSelector(selected: CarSound, onSelect: (CarSound) -> Unit) {
    LazyRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(CarSound.entries) { car ->
            val isSelected = car == selected
            Box(
                modifier = Modifier
                    .background(
                        if (isSelected) Orange else DarkSurface,
                        RoundedCornerShape(8.dp)
                    )
                    .clickable { onSelect(car) }
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Text(
                    car.displayName,
                    color = if (isSelected) DarkBackground else GrayText,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
private fun ConnectionStatusBar(state: ConnectionState, onDisconnect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkSurface, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(12.dp).background(
                when (state) {
                    is ConnectionState.Connected -> Green
                    is ConnectionState.Connecting -> Yellow
                    is ConnectionState.Error -> Red
                    is ConnectionState.Disconnected -> GrayText
                }, CircleShape
            )
        )
        Spacer(Modifier.width(8.dp))
        Text(
            when (state) {
                is ConnectionState.Connected -> "Connected: ${state.deviceName}"
                is ConnectionState.Connecting -> "Connecting..."
                is ConnectionState.Error -> "Error: ${state.message}"
                is ConnectionState.Disconnected -> "Disconnected"
            },
            color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f)
        )
        if (state is ConnectionState.Connected) {
            TextButton(onClick = onDisconnect) {
                Text("Disconnect", color = Red, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun RpmGauge(rpm: Int, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 14.dp.toPx()
            val pad = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)

            drawArc(DarkSurface, 135f, 270f, false, Offset(pad, pad), arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round))

            val frac = (rpm / 9000f).coerceIn(0f, 1f)
            drawArc(
                when { frac > 0.85f -> Red; frac > 0.7f -> Orange; else -> Green },
                135f, 270f * frac, false, Offset(pad, pad), arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$rpm", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
            Text("RPM", color = GrayText, fontSize = 14.sp)
        }
    }
}

@Composable
private fun DataCard(label: String, value: String, unit: String) {
    Column(
        Modifier.background(DarkSurface, RoundedCornerShape(8.dp))
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, color = GrayText, fontSize = 10.sp)
        Text(value, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(unit, color = GrayText, fontSize = 12.sp)
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun DeviceList(
    devices: List<BluetoothDevice>,
    onDeviceClick: (BluetoothDevice) -> Unit,
    onRefresh: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("Paired Devices", color = GrayText, fontSize = 14.sp)
            TextButton(onClick = onRefresh) { Text("Refresh", color = Orange, fontSize = 12.sp) }
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 160.dp)) {
            items(devices) { device ->
                Row(
                    Modifier.fillMaxWidth().clickable { onDeviceClick(device) }
                        .background(DarkSurface, RoundedCornerShape(8.dp)).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(device.name ?: "Unknown", color = Color.White, fontSize = 14.sp)
                        Text(device.address, color = GrayText, fontSize = 12.sp)
                    }
                    Text("Connect", color = Orange, fontSize = 12.sp)
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

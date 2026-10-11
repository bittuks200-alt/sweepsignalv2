package com.sweep.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notifier.ensureChannels(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { Screen() }
            }
        }
    }
}

private val timeFmt = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault())

@Composable
fun Screen() {
    val ctx = LocalContext.current
    val st by Monitor.state.collectAsState()
    val scope = rememberCoroutineScope()
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) perm.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    LazyColumn(
        Modifier.fillMaxSize().systemBarsPadding().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Column {
                Text("Liquidity Sweep Signals", style = MaterialTheme.typography.titleLarge)
                Text("D1 High/Low sweep -> M1 reversal. Signals only, no auto-trading.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val i = Intent(ctx, MonitorService::class.java)
                    if (st.running) ctx.startService(i.setAction("STOP"))
                    else ContextCompat.startForegroundService(ctx, i)
                }) { Text(if (st.running) "Stop" else "Start monitoring") }
                OutlinedButton(
                    onClick = { scope.launch(Dispatchers.IO) { Monitor.backtest() } },
                    enabled = !st.busy
                ) { Text(if (st.busy) "Running..." else "Backtest 7d") }
            }
        }
        st.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        items(Monitor.assets) { a -> AssetCard(a, st.syms[a.symbol]) }
        if (st.backtest.isNotEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Backtest (last 7 days, no fees/slippage)", fontWeight = FontWeight.Bold)
                        st.backtest.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
        item { Text("Signal log", fontWeight = FontWeight.Bold) }
        items(st.log) { e ->
            Text(
                "${timeFmt.format(Date(e.time))}  ${e.symbol}  ${e.text}",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (e.important) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}

@Composable
fun AssetCard(a: Asset, s: SymState?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(a.name, fontWeight = FontWeight.Bold)
                Text(if (s == null) "--" else fmt(s.price))
            }
            if (s == null) {
                Text("Not started", style = MaterialTheme.typography.bodySmall)
            } else {
                Text("Prev day  High ${fmt(s.high)}   Low ${fmt(s.low)}", style = MaterialTheme.typography.bodySmall)
                Text(s.status, style = MaterialTheme.typography.bodySmall)
                s.trade?.let {
                    Text(
                        "${it.side} @ ${fmt(it.entry)}   SL ${fmt(it.sl)}   TP ${fmt(it.tp)}",
                        color = if (it.side == Side.BUY) Color(0xFF66BB6A) else Color(0xFFEF5350),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

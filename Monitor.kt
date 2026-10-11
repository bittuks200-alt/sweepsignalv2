package com.sweep.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

const val DAY = 86_400_000L

data class Asset(val name: String, val symbol: String)

data class SymState(
    val price: Double = Double.NaN, val high: Double = Double.NaN, val low: Double = Double.NaN,
    val status: String = "...", val trade: Trade? = null
)

data class UiState(
    val running: Boolean = false,
    val syms: Map<String, SymState> = emptyMap(),
    val log: List<Event> = emptyList(),
    val backtest: List<String> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null
)

object Monitor {
    // Gold = PAXG (tokenized gold, tracks XAU spot closely, trades 24/7)
    val assets = listOf(
        Asset("Gold (PAXG)", "PAXGUSDT"),
        Asset("Bitcoin", "BTCUSDT"),
        Asset("Ethereum", "ETHUSDT")
    )
    val state = MutableStateFlow(UiState())

    fun update(f: UiState.() -> UiState) { state.update { it.f() } }

    /** Daily candles open at 00:00 UTC. Levels for day D = High/Low of day D-1. */
    private fun feed(e: StrategyEngine, c: Candle, levels: Map<Long, Candle>): List<Event> {
        val d = c.openTime / DAY
        levels[d - 1]?.let { e.setLevels(d, it.high, it.low) }
        return e.onCandle(c)
    }

    fun tick(ctx: Context, a: Asset, e: StrategyEngine, last: MutableMap<String, Long>) {
        val levels = Binance.klines(a.symbol, "1d", 4).dropLast(1).associateBy { it.openTime / DAY }
        val warm = a.symbol !in last
        val raw = Binance.klines(a.symbol, "1m", if (warm) 1000 else 200)
        val price = raw.last().close
        val fresh = raw.dropLast(1).filter { it.openTime > (last[a.symbol] ?: 0L) }
        val events = fresh.flatMap { feed(e, it, levels) }
        fresh.lastOrNull()?.let { last[a.symbol] = it.openTime }
        if (!warm) events.filter { it.important }.forEach { Notifier.signal(ctx, it) }
        val st = SymState(price, e.prevHigh, e.prevLow, e.status(), e.trade)
        update {
            copy(
                syms = syms + (a.symbol to st),
                log = (events + log).sortedByDescending { it.time }.take(100)
            )
        }
    }

    fun backtest(days: Int = 7) {
        update { copy(busy = true, backtest = emptyList(), error = null) }
        try {
            val out = assets.map { a ->
                val levels = Binance.klines(a.symbol, "1d", days + 3).dropLast(1).associateBy { it.openTime / DAY }
                val all = ArrayList<Candle>()
                var end: Long? = null
                while (all.size < days * 1440) {
                    val page = Binance.klines(a.symbol, "1m", 1000, end)
                    if (page.isEmpty()) break
                    all.addAll(if (end == null) page.dropLast(1) else page)
                    end = page.first().openTime - 1
                }
                val e = StrategyEngine(a.symbol)
                all.sortedBy { it.openTime }.forEach { feed(e, it, levels) }
                val t = e.closed
                val w = t.count { it.result == "TP" }
                val rate = if (t.isEmpty()) 0 else w * 100 / t.size
                val net = String.format(Locale.US, "%+.2f", t.sumOf { it.pnlPct })
                "${a.name}: ${t.size} trades | $w TP / ${t.size - w} SL | win $rate% | net $net%"
            }
            update { copy(backtest = out, busy = false) }
        } catch (ex: Exception) {
            update { copy(busy = false, error = "Backtest failed: ${ex.message}") }
        }
    }
}

object Notifier {
    private const val CH_SIGNAL = "signals"
    private const val CH_MONITOR = "monitor"

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_SIGNAL, "Trade signals", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_MONITOR, "Monitoring", NotificationManager.IMPORTANCE_LOW))
    }

    fun ongoing(ctx: Context): Notification {
        ensureChannels(ctx)
        return NotificationCompat.Builder(ctx, CH_MONITOR)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Sweep Signals running")
            .setContentText("Watching Gold, BTC, ETH")
            .setOngoing(true).build()
    }

    fun signal(ctx: Context, e: Event) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val n = NotificationCompat.Builder(ctx, CH_SIGNAL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(e.symbol)
            .setContentText(e.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(e.text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true).build()
        ctx.getSystemService(NotificationManager::class.java)
            .notify((e.time % 1_000_000_000L).toInt() + e.symbol.hashCode(), n)
    }
}

class MonitorService : Service() {
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            job?.cancel()
            Monitor.update { copy(running = false) }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(this, 1, Notifier.ongoing(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (job?.isActive == true) return START_STICKY
        Monitor.update { copy(running = true, error = null) }
        job = CoroutineScope(Dispatchers.IO).launch {
            val engines = Monitor.assets.associate { it.symbol to StrategyEngine(it.symbol) }
            val last = mutableMapOf<String, Long>()
            while (isActive) {
                var err: String? = null
                for (a in Monitor.assets) {
                    try {
                        Monitor.tick(applicationContext, a, engines.getValue(a.symbol), last)
                    } catch (ex: Exception) {
                        err = "${a.name}: ${ex.message}"
                    }
                }
                val finalErr = err
                Monitor.update { copy(error = finalErr) }
                delay(15_000)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        Monitor.update { copy(running = false) }
        super.onDestroy()
    }
}

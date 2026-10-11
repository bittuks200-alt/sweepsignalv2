package com.sweep.app

import java.util.Locale

data class Candle(val openTime: Long, val open: Double, val high: Double, val low: Double, val close: Double)

enum class Side { BUY, SELL }

data class Trade(
    val symbol: String, val side: Side, val entry: Double, val sl: Double, val tp: Double,
    val openTime: Long, var closeTime: Long = 0, var result: String = "OPEN", var pnlPct: Double = 0.0
)

data class Event(val time: Long, val symbol: String, val text: String, val important: Boolean = false)

/** Tweak these to tune the strategy. */
data class Config(
    val swingLookback: Int = 30,   // M1 candles looked back to find the swing High/Low (TP)
    val signalExpiry: Int = 30,    // M1 candles to wait for green/red reversal candle
    val readyExpiry: Int = 5,      // M1 candles to wait for the trigger break after reversal candle
    val oneTradePerDay: Boolean = true
)

fun fmt(v: Double): String = if (v.isNaN()) "--" else String.format(Locale.US, "%.2f", v)

private enum class Stage { WATCH_BREAK, WAIT_SIGNAL, READY }

private class Branch(val side: Side) {
    var stage = Stage.WATCH_BREAK
    var signal: Candle? = null
    var swing = 0.0
    var waited = 0
    fun reset() { stage = Stage.WATCH_BREAK; signal = null; waited = 0 }
}

/**
 * Liquidity-sweep state machine (per symbol). Feed CLOSED M1 candles in order via onCandle().
 *
 * BUY : M1 close < prev-day Low -> wait GREEN candle -> next candle breaks green High -> BUY
 *       SL = green Low, TP = swing High (where the drop started)
 * SELL: M1 close > prev-day High -> wait RED candle -> next candle breaks red Low -> SELL
 *       SL = red High, TP = swing Low (where the rally started)
 */
class StrategyEngine(val symbol: String, private val cfg: Config = Config()) {
    var prevHigh = Double.NaN; private set
    var prevLow = Double.NaN; private set
    var trade: Trade? = null; private set
    val closed = mutableListOf<Trade>()

    private var dayKey = -1L
    private var tradedToday = false
    private val history = ArrayDeque<Candle>()
    private val buy = Branch(Side.BUY)
    private val sell = Branch(Side.SELL)

    fun setLevels(day: Long, high: Double, low: Double) {
        if (day == dayKey) return
        dayKey = day; prevHigh = high; prevLow = low; tradedToday = false
        buy.reset(); sell.reset()
    }

    fun onCandle(c: Candle): List<Event> {
        val ev = mutableListOf<Event>()
        if (!prevHigh.isNaN()) {
            val t = trade
            if (t != null) {
                manage(t, c, ev)
            } else if (!(cfg.oneTradePerDay && tradedToday)) {
                step(buy, c, ev)
                if (trade == null && !(cfg.oneTradePerDay && tradedToday)) step(sell, c, ev)
            }
        }
        history.addLast(c)
        while (history.size > 200) history.removeFirst()
        return ev
    }

    fun status(): String {
        if (prevHigh.isNaN()) return "WAIT_FOR_LEVELS"
        trade?.let { return "POSITION_OPEN (${it.side})" }
        if (cfg.oneTradePerDay && tradedToday) return "DONE_FOR_DAY"
        return "BUY: ${label(buy)}  |  SELL: ${label(sell)}"
    }

    private fun label(b: Branch): String {
        val isBuy = b.side == Side.BUY
        return when (b.stage) {
            Stage.WATCH_BREAK -> if (isBuy) "WATCH_LOW_BREAK" else "WATCH_HIGH_BREAK"
            Stage.WAIT_SIGNAL -> if (isBuy) "WAIT_BUY_SIGNAL" else "WAIT_SELL_SIGNAL"
            Stage.READY -> if (isBuy) "BUY_READY" else "SELL_READY"
        }
    }

    private fun swingOf(isBuy: Boolean, c: Candle): Double {
        val src = history.takeLast(cfg.swingLookback) + c
        return if (isBuy) src.maxOf { it.high } else src.minOf { it.low }
    }

    private fun note(ev: MutableList<Event>, c: Candle, text: String, important: Boolean = false) {
        ev += Event(c.openTime + 60_000, symbol, text, important)
    }

    private fun step(b: Branch, c: Candle, ev: MutableList<Event>) {
        val isBuy = b.side == Side.BUY
        when (b.stage) {
            Stage.WATCH_BREAK -> {
                val broke = if (isBuy) c.close < prevLow else c.close > prevHigh
                if (broke) {
                    b.swing = swingOf(isBuy, c)
                    b.stage = Stage.WAIT_SIGNAL; b.waited = 0
                    note(ev, c, if (isBuy) "M1 closed below Daily Low ${fmt(prevLow)} -> waiting for GREEN candle"
                                else "M1 closed above Daily High ${fmt(prevHigh)} -> waiting for RED candle")
                }
            }
            Stage.WAIT_SIGNAL -> {
                b.waited++
                val reversal = if (isBuy) c.close > c.open else c.close < c.open
                if (reversal) {
                    b.signal = c; b.stage = Stage.READY; b.waited = 0
                    note(ev, c, "${if (isBuy) "Green" else "Red"} candle found -> ${if (isBuy) "BUY" else "SELL"}_READY")
                } else if (b.waited >= cfg.signalExpiry) {
                    b.reset()
                    note(ev, c, "Signal expired (no reversal candle)")
                }
            }
            Stage.READY -> {
                val s = b.signal
                if (s == null) { b.reset(); return }
                b.waited++
                val triggered = if (isBuy) c.high > s.high else c.low < s.low
                val invalid = if (isBuy) c.low < s.low else c.high > s.high
                when {
                    triggered -> enter(b, s, c, ev)
                    invalid || b.waited >= cfg.readyExpiry -> {
                        b.stage = Stage.WAIT_SIGNAL; b.signal = null; b.waited = 0
                    }
                }
            }
        }
    }

    private fun enter(b: Branch, s: Candle, c: Candle, ev: MutableList<Event>) {
        val isBuy = b.side == Side.BUY
        val entry = if (isBuy) maxOf(s.high, c.open) else minOf(s.low, c.open)
        val sl = if (isBuy) s.low else s.high
        val tp = b.swing
        val ok = if (isBuy) tp > entry && sl < entry else tp < entry && sl > entry
        if (!ok) {
            b.stage = Stage.WAIT_SIGNAL; b.signal = null; b.waited = 0
            note(ev, c, "Setup skipped (TP not beyond entry)")
            return
        }
        val t = Trade(symbol, b.side, entry, sl, tp, c.openTime)
        trade = t; tradedToday = true
        note(ev, c, "${b.side} signal @ ${fmt(entry)} | SL ${fmt(sl)} | TP ${fmt(tp)}", true)
        manage(t, c, ev)
    }

    private fun manage(t: Trade, c: Candle, ev: MutableList<Event>) {
        val isBuy = t.side == Side.BUY
        val slHit = if (isBuy) c.low <= t.sl else c.high >= t.sl
        val tpHit = if (isBuy) c.high >= t.tp else c.low <= t.tp
        if (!slHit && !tpHit) return
        val exit = if (slHit) t.sl else t.tp   // if both in one candle, assume SL first (conservative)
        t.result = if (slHit) "SL" else "TP"
        t.pnlPct = (exit - t.entry) / t.entry * 100.0 * (if (isBuy) 1 else -1)
        t.closeTime = c.openTime + 60_000
        closed += t
        trade = null
        note(ev, c, "${t.side} closed by ${t.result} @ ${fmt(exit)} (${String.format(Locale.US, "%+.2f", t.pnlPct)}%)", true)
    }
}

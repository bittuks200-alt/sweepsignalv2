package com.sweep.app

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/** Free public market data, no API key needed. */
object Binance {
    private val hosts = listOf("https://data-api.binance.vision", "https://api.binance.com")

    /** Returns candles oldest -> newest. The LAST one is usually still forming. */
    fun klines(symbol: String, interval: String, limit: Int, endTime: Long? = null): List<Candle> {
        var lastError: Exception? = null
        for (h in hosts) {
            try {
                val url = "$h/api/v3/klines?symbol=$symbol&interval=$interval&limit=$limit" +
                        (if (endTime != null) "&endTime=$endTime" else "")
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val arr = JSONArray(body)
                return List(arr.length()) { i ->
                    val k = arr.getJSONArray(i)
                    Candle(k.getLong(0), k.getString(1).toDouble(), k.getString(2).toDouble(),
                        k.getString(3).toDouble(), k.getString(4).toDouble())
                }
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("No data")
    }
}

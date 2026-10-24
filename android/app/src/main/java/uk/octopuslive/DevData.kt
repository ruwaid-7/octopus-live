package uk.octopuslive

import android.content.Context
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.sin

/**
 * Test data for the "Octopus Live Dev" build: when it's switched on (Prefs.devMode, only possible
 * from the dev panel), the app shows these values instead of asking Octopus, so every screen,
 * the widget and the notifications can be checked with any numbers.
 */
object DevData {
    // Keys for the values typed into the dev panel
    const val WATTS = "dev_watts"
    const val ELEC_KWH = "dev_elecKwh"
    const val ELEC_COST = "dev_elecCost"         // £, -1 = unknown (no API key)
    const val HAS_GAS = "dev_hasGas"             // 1/0
    const val GAS_KW = "dev_gasKw"
    const val GAS_ON_MIN = "dev_gasOnMin"        // 0 = off
    const val GAS_KWH = "dev_gasKwh"
    const val GAS_COST = "dev_gasCost"
    const val PRICE = "dev_price"                // p/kWh, -1 = unknown
    const val NEXT_PRICE = "dev_nextPrice"       // -1 = none
    const val NEXT_IN_MIN = "dev_nextInMin"
    const val BASE_W = "dev_baseW"
    const val AGE_MIN = "dev_ageMin"             // how old the meter reading is
    const val ERROR = "dev_error"                // non-empty = the update "fails"
    const val TARIFF = "dev_tariff"
    const val HISTORY = "dev_history"            // JSON of fake past days

    private val zone get() = ZoneId.systemDefault()

    fun num(p: Prefs, key: String, default: Double): Double = p.str(key)?.toDoubleOrNull() ?: default

    /** The "live" reading, or throws (like a failed update) when an error is set. */
    fun reading(c: Context): Reading {
        val p = Prefs(c)
        p.str(ERROR)?.takeIf { it.isNotBlank() }?.let { throw ApiException(it) }
        val now = System.currentTimeMillis()
        val age = num(p, AGE_MIN, 0.0).toLong()
        val readAtMs = now - age * 60_000
        val hasGas = p.str(HAS_GAS) != "0"
        val gasOn = num(p, GAS_ON_MIN, 0.0).toInt()
        val gasTo = LocalTime.now().minusMinutes(60).let { it.withMinute(if (it.minute < 30) 0 else 30) }
        val hhmm = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)
        return Reading(
            demandWatts = num(p, WATTS, 420.0).toInt(),
            todayKwh = num(p, ELEC_KWH, 6.2),
            readAt = java.time.Instant.ofEpochMilli(readAtMs).atZone(zone).toLocalTime().format(hhmm),
            fetchedAt = now,
            gasTodayKwh = if (hasGas) num(p, GAS_KWH, 18.4) else null,
            gasReadAt = if (hasGas) gasTo.format(hhmm) else null,
            viaServer = !p.relayUrl.isNullOrBlank(),
            gasKw = if (hasGas) (if (gasOn > 0) num(p, GAS_KW, 8.4) else 0.0) else null,
            gasRunMinutes = if (hasGas) gasOn else 0,
            gasRunSince = if (hasGas && gasOn > 0) gasTo.plusMinutes(30).minusMinutes(gasOn.toLong()).format(hhmm) else null,
            readAtMs = readAtMs,
        )
    }

    /** Costs, prices, today's half-hours and history to match the values above. */
    fun insights(c: Context): InsightsData {
        val p = Prefs(c)
        val now = System.currentTimeMillis()
        val dayStart = LocalDate.now().atStartOfDay(zone).toInstant().toEpochMilli()
        val nowSlot = ((now - dayStart) / 1_800_000L).toInt().coerceIn(0, 47)
        val hasGas = p.str(HAS_GAS) != "0"
        val price = num(p, PRICE, 25.6).takeIf { it >= 0 }
        val next = num(p, NEXT_PRICE, -1.0).takeIf { it >= 0 }
        val elecCost = num(p, ELEC_COST, 1.94).takeIf { it >= 0 }
        val gasCost = if (hasGas) num(p, GAS_COST, 1.32).takeIf { it >= 0 } else null

        // A believable day: low at night, peaks at breakfast and dinner, scaled to today's kWh
        val elecTotal = num(p, ELEC_KWH, 6.2)
        val shape = (0..nowSlot).map { i -> profile(i) }
        val elecSlots = shape.map { it / shape.sum() * elecTotal }
        val gasTotal = num(p, GAS_KWH, 18.4)
        val gasCount = max(0, nowSlot - 1)   // gas arrives about an hour late
        val gasShape = (0 until gasCount).map { i -> if (i in 12..17 || i in 34..44) 2.0 + sin(i * 1.3) else 0.05 }
        val gasSlots = if (!hasGas || gasCount == 0) emptyList() else gasShape.map { it / gasShape.sum() * gasTotal }

        return InsightsData(
            updated = now,
            tariff = p.str(TARIFF)?.takeIf { it.isNotBlank() } ?: "E-1R-VAR-22-11-01-M",
            priceNow = price,
            priceNext = next,
            priceNextAt = next?.let { now + num(p, NEXT_IN_MIN, 45.0).toLong() * 60_000 },
            elecCostToday = elecCost,
            gasCostToday = gasCost,
            todaySlots = elecSlots,
            todayGasSlots = gasSlots,
            todaySlotPence = elecSlots.map { kwh -> price?.let { kwh * it } },
            elecStanding = 60.9,
            gasStanding = if (hasGas) 28.8 else null,
            mpan = "2300012345912",
            mprn = if (hasGas) "1812342809" else null,
            baseW = num(p, BASE_W, 120.0).toInt(),
            history = history(p, hasGas),
        )
    }

    private fun profile(i: Int): Double {
        val h = i / 2.0
        return 0.15 + 0.9 * bump(h, 7.5, 1.0) + 0.6 * bump(h, 12.5, 1.2) + 1.6 * bump(h, 18.5, 1.6) + 0.05 * sin(i * 2.7)
    }
    private fun bump(x: Double, mid: Double, width: Double) = Math.exp(-((x - mid) * (x - mid)) / (2 * width * width))

    /** Fake past days (made by "Fill 30 days" in the panel). */
    private fun history(p: Prefs, hasGas: Boolean): List<DayStats> = try {
        val a = org.json.JSONArray(p.str(HISTORY) ?: "[]")
        (0 until a.length()).map { InsightsData.dayFromJson(a.getJSONObject(it)) }
            .map { if (hasGas) it else it.copy(gasKwh = null, gasCost = null) }
    } catch (e: Exception) { emptyList() }

    fun fillHistory(c: Context, days: Int) {
        val rnd = java.util.Random()
        val a = org.json.JSONArray()
        for (d in days downTo 1) {
            val date = LocalDate.now().minusDays(d.toLong())
            val weekend = date.dayOfWeek.value >= 6
            val elec = (if (weekend) 9.0 else 6.0) + rnd.nextGaussian() * 1.8
            val gas = 10.0 + rnd.nextDouble() * 10 + (if (weekend) 4.0 else 0.0)
            a.put(InsightsData.dayToJson(DayStats(
                date = date.toString(),
                elecKwh = max(1.5, elec), gasKwh = gas,
                elecCost = max(1.5, elec) * 0.256 + 0.609, gasCost = gas * 0.063 + 0.288,
                baseW = 80 + rnd.nextInt(80), fetchedAt = System.currentTimeMillis(),
            )))
        }
        Prefs(c).setStr(HISTORY, a.toString())
    }
}

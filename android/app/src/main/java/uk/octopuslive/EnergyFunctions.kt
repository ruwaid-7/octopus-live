package uk.octopuslive

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.appfunctions.AppFunctionDeclaration
import androidx.appfunctions.AppFunctionSerializable
import androidx.appfunctions.AppFunctionService
import androidx.appfunctions.AppFunctionServiceEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Functions an on-device assistant (such as Gemini) can call to answer questions like
 * "how much electricity am I using?" or "is the heating on?". Android 16+ only; on older
 * phones the service stays disabled and nothing changes.
 *
 * The compiler generates the real service, [EnergyAppFunctionService], from this class.
 */
@RequiresApi(Build.VERSION_CODES.BAKLAVA)
@AppFunctionServiceEntryPoint(
    serviceName = "EnergyAppFunctionService",
    appFunctionXmlFileName = "energy_app_functions",
)
abstract class BaseEnergyAppFunctionService : AppFunctionService() {

    /**
     * Gets the home's live energy use from the Octopus Energy smart meter (Octopus Home Mini):
     * electricity being used right now in watts, electricity and gas used so far today in kWh,
     * today's cost in pounds, and the current electricity price.
     *
     * @return The latest home energy reading, including a one-sentence summary.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun getLiveEnergyUsage(): EnergyUsage = withContext(Dispatchers.IO) {
        val r = latestReading()
        val ins = Insights(this@BaseEnergyAppFunctionService).load()
        val elecCost = ins?.elecCostToday
        val gasCost = ins?.gasCostToday
        val parts = mutableListOf<String>()
        r.demandWatts?.let { parts += "Using $it W of electricity right now" }
        parts += "${fmt(r.todayKwh)} kWh of electricity today" + (elecCost?.let { " (£${fmt(it)})" } ?: "")
        r.gasTodayKwh?.let { g -> parts += "${fmt(g)} kWh of gas today" + (gasCost?.let { " (£${fmt(it)})" } ?: "") }
        ins?.priceNow?.let { parts += "electricity costs ${fmt(it, 1)}p per kWh now" }
        EnergyUsage(
            electricityWatts = r.demandWatts,
            electricityTodayKwh = r.todayKwh,
            electricityCostTodayPounds = elecCost,
            gasTodayKwh = r.gasTodayKwh,
            gasCostTodayPounds = gasCost,
            gasIsOn = r.gasRunMinutes > 0,
            priceNowPencePerKwh = ins?.priceNow,
            readingTime = r.readAt,
            summary = parts.joinToString(", ") + " (meter reading at ${r.readAt}).",
        )
    }

    /**
     * Checks whether the gas (boiler, heating or hot water) is on, how long it has been running,
     * and how much gas has been used today. Gas meter readings arrive every half-hour, about an
     * hour behind real time.
     *
     * @return The gas status, including a one-sentence summary.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun getGasStatus(): GasStatus = withContext(Dispatchers.IO) {
        val r = latestReading()
        val on = r.gasRunMinutes > 0
        val summary = when {
            r.gasTodayKwh == null && r.gasKw == null -> "No smart gas meter data is available."
            on -> "The gas has been on for about ${duration(r.gasRunMinutes)}" +
                (r.gasRunSince?.let { ", since $it" } ?: "") +
                (r.gasKw?.let { ", using about ${fmt(it, 1)} kW" } ?: "") + "."
            else -> "The gas is off" + (r.gasReadAt?.let { " as of the $it reading" } ?: "") + "."
        } + (r.gasTodayKwh?.let { " ${fmt(it)} kWh of gas used today." } ?: "")
        GasStatus(
            isOn = on,
            runningMinutes = r.gasRunMinutes,
            runningSince = r.gasRunSince,
            latestKw = r.gasKw,
            todayKwh = r.gasTodayKwh,
            readingTime = r.gasReadAt,
            summary = summary,
        )
    }

    /**
     * Finds when electricity is cheapest over the next 24 hours on the home's Octopus Energy
     * tariff (useful for timing the washing machine, dishwasher or car charging).
     *
     * @return The current price, the cheapest upcoming times and a one-sentence summary.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun getCheapestElectricityTimes(): CheapestElectricity = withContext(Dispatchers.IO) {
        val insights = Insights(this@BaseEnergyAppFunctionService)
        val data = insights.load()
        val rates = insights.upcomingRates(24 * 3600_000L)
        val now = System.currentTimeMillis()
        val priceNow = data?.priceNow ?: Insights.rateAt(rates, now)
        if (rates.isEmpty()) {
            return@withContext CheapestElectricity(
                priceNowPencePerKwh = priceNow, cheapestPencePerKwh = null, cheapestTimes = emptyList(),
                summary = "Price information isn't available yet. Add your Octopus API key in the Octopus Live app.",
            )
        }
        val lowest = rates.minOf { it.p }
        // Join neighbouring half-hours at the lowest price into blocks
        val blocks = mutableListOf<Rate>()
        for (r in rates.filter { Math.abs(it.p - lowest) < 0.005 }) {
            val last = blocks.lastOrNull()
            if (last != null && last.to == r.from) blocks[blocks.size - 1] = Rate(last.from, r.to, last.p) else blocks += r
        }
        val times = blocks.take(4).map { b ->
            PriceTime(
                startTime = Insights.formatTime(maxOf(b.from, now)),
                endTime = if (b.to == Long.MAX_VALUE) "later" else Insights.formatTime(b.to),
                pencePerKwh = b.p,
            )
        }
        val flat = rates.all { Math.abs(it.p - lowest) < 0.005 }
        val summary = if (flat) {
            "The price stays at ${fmt(lowest, 1)}p per kWh all day, so any time is the same."
        } else {
            "Cheapest is ${fmt(lowest, 1)}p per kWh: " +
                times.joinToString(", ") { "${it.startTime}–${it.endTime}" } +
                (priceNow?.let { ". It's ${fmt(it, 1)}p now." } ?: ".")
        }
        CheapestElectricity(priceNow, lowest, times, summary)
    }

    /** A fresh reading when the cached one is more than 90 seconds old; the cached one if fetching fails. */
    private fun latestReading(): Reading {
        val prefs = Prefs(this)
        val cached = prefs.loadReading()
        if (cached != null && System.currentTimeMillis() - cached.fetchedAt < 90_000L) return cached
        if (!prefs.isConfigured) {
            throw androidx.appfunctions.AppFunctionElementNotFoundException(
                "Octopus Live isn't set up yet. Open the app and add your Octopus API key or relay address."
            )
        }
        return try {
            OctopusApi(this).fetch().also { prefs.saveReading(it) }
        } catch (e: Exception) {
            cached ?: throw androidx.appfunctions.AppFunctionAppUnknownException(
                "Couldn't reach Octopus: ${e.message}"
            )
        }
    }

    private fun fmt(v: Double, places: Int = 2) = String.format(Locale.UK, "%.${places}f", v)

    private fun duration(min: Int) = when {
        min < 60 -> "$min minutes"
        min % 60 == 0 -> "${min / 60} hour" + if (min / 60 == 1) "" else "s"
        else -> "${min / 60} h ${min % 60} min"
    }
}

/** The home's latest energy reading. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class EnergyUsage(
    /** Electricity being used right now, in watts. Null if the meter hasn't reported. */
    val electricityWatts: Int?,
    /** Electricity used since midnight, in kWh. */
    val electricityTodayKwh: Double,
    /** Cost of today's electricity so far in pounds, including the standing charge. */
    val electricityCostTodayPounds: Double?,
    /** Gas used since midnight, in kWh. Null if there's no smart gas meter. */
    val gasTodayKwh: Double?,
    /** Cost of today's gas so far in pounds, including the standing charge. */
    val gasCostTodayPounds: Double?,
    /** True if the gas appears to be on (latest half-hour above the threshold). */
    val gasIsOn: Boolean,
    /** Current electricity price in pence per kWh, including VAT. */
    val priceNowPencePerKwh: Double?,
    /** Local time (HH:mm) of the meter reading. */
    val readingTime: String,
    /** A one-sentence summary to read out to the user. */
    val summary: String,
)

/** Whether the gas is on. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class GasStatus(
    /** True if the gas appears to be on. */
    val isOn: Boolean,
    /** How long it has been on, in minutes (0 when off). */
    val runningMinutes: Int,
    /** Local time (HH:mm) the current run started, if on. */
    val runningSince: String?,
    /** Average gas power over the latest half-hour, in kW. */
    val latestKw: Double?,
    /** Gas used since midnight, in kWh. */
    val todayKwh: Double?,
    /** Local time (HH:mm) of the latest half-hourly gas reading. */
    val readingTime: String?,
    /** A one-sentence summary to read out to the user. */
    val summary: String,
)

/** A period at one electricity price. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class PriceTime(
    /** Local start time (HH:mm). */
    val startTime: String,
    /** Local end time (HH:mm). */
    val endTime: String,
    /** Price in pence per kWh, including VAT. */
    val pencePerKwh: Double,
)

/** The cheapest upcoming electricity. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class CheapestElectricity(
    /** Current electricity price in pence per kWh. */
    val priceNowPencePerKwh: Double?,
    /** Lowest price in the next 24 hours, in pence per kWh. */
    val cheapestPencePerKwh: Double?,
    /** When that lowest price applies (up to four periods). */
    val cheapestTimes: List<PriceTime>,
    /** A one-sentence summary to read out to the user. */
    val summary: String,
)

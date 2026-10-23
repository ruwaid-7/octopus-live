package uk.octopuslive

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import uk.octopuslive.OctopusApi.Companion.objects
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** A price for a period [from, to) in epoch ms; [p] is pence (per kWh, or per day for standing charges), inc VAT. */
data class Rate(val from: Long, val to: Long, val p: Double)

/** One finished day. Costs are in £ and include the standing charge. */
data class DayStats(
    val date: String,          // yyyy-MM-dd
    val elecKwh: Double,
    val gasKwh: Double?,
    val elecCost: Double?,
    val gasCost: Double?,
    val baseW: Int?,           // lowest half-hour average power ("always on")
    val fetchedAt: Long,
)

/** Everything the app shows beyond the live reading. */
data class InsightsData(
    val updated: Long = 0,
    val tariff: String? = null,
    val priceNow: Double? = null,       // p/kWh
    val priceNext: Double? = null,
    val priceNextAt: Long? = null,
    val elecCostToday: Double? = null,  // £
    val gasCostToday: Double? = null,
    val todaySlots: List<Double> = emptyList(),   // kWh per half-hour since midnight, up to the current half-hour
    val todayGasSlots: List<Double> = emptyList(),   // gas kWh per half-hour, up to the latest reading (about an hour late)
    val todaySlotPence: List<Double?> = emptyList(), // electricity cost of each of todaySlots, in pence (null = price unknown)
    val elecStanding: Double? = null,   // p/day
    val gasStanding: Double? = null,    // p/day
    val mpan: String? = null,
    val mprn: String? = null,
    val baseW: Int? = null,
    val history: List<DayStats> = emptyList(),    // oldest first, up to 7 days, not including today
    val error: String? = null,
) {
    fun toJson(): String = JSONObject().apply {
        put("updated", updated); put("tariff", tariff)
        put("priceNow", priceNow); put("priceNext", priceNext); put("priceNextAt", priceNextAt)
        put("elecCostToday", elecCostToday); put("gasCostToday", gasCostToday)
        put("todaySlots", JSONArray(todaySlots)); put("todayGasSlots", JSONArray(todayGasSlots))
        put("todaySlotPence", JSONArray().apply { todaySlotPence.forEach { put(it ?: JSONObject.NULL) } })
        put("elecStanding", elecStanding); put("gasStanding", gasStanding); put("mpan", mpan); put("mprn", mprn)
        put("baseW", baseW); put("error", error)
        put("history", JSONArray().apply { history.forEach { put(dayToJson(it)) } })
    }.toString()

    companion object {
        fun fromJson(s: String?): InsightsData? = try {
            if (s == null) null else JSONObject(s).let { j ->
                InsightsData(
                    updated = j.optLong("updated"),
                    tariff = j.optStr("tariff"),
                    priceNow = j.optNum("priceNow"), priceNext = j.optNum("priceNext"),
                    priceNextAt = j.optNum("priceNextAt")?.toLong(),
                    elecCostToday = j.optNum("elecCostToday"), gasCostToday = j.optNum("gasCostToday"),
                    todaySlots = j.optJSONArray("todaySlots")?.let { a -> (0 until a.length()).map { a.optDouble(it, 0.0) } } ?: emptyList(),
                    todayGasSlots = j.optJSONArray("todayGasSlots")?.let { a -> (0 until a.length()).map { a.optDouble(it, 0.0) } } ?: emptyList(),
                    todaySlotPence = j.optJSONArray("todaySlotPence")?.let { a ->
                        (0 until a.length()).map { if (a.isNull(it)) null else a.optDouble(it) }
                    } ?: emptyList(),
                    elecStanding = j.optNum("elecStanding"), gasStanding = j.optNum("gasStanding"),
                    mpan = j.optStr("mpan"), mprn = j.optStr("mprn"),
                    baseW = j.optNum("baseW")?.toInt(),
                    history = j.optJSONArray("history").objects().map { dayFromJson(it) },
                    error = j.optStr("error"),
                )
            }
        } catch (e: Exception) { null }

        fun dayToJson(d: DayStats) = JSONObject().apply {
            put("date", d.date); put("elecKwh", d.elecKwh); put("gasKwh", d.gasKwh)
            put("elecCost", d.elecCost); put("gasCost", d.gasCost); put("baseW", d.baseW); put("fetchedAt", d.fetchedAt)
        }

        fun dayFromJson(j: JSONObject) = DayStats(
            date = j.optString("date"), elecKwh = j.optDouble("elecKwh", 0.0),
            gasKwh = j.optNum("gasKwh"), elecCost = j.optNum("elecCost"), gasCost = j.optNum("gasCost"),
            baseW = j.optNum("baseW")?.toInt(), fetchedAt = j.optLong("fetchedAt"),
        )

        private fun JSONObject.optNum(k: String): Double? =
            if (isNull(k)) null else optDouble(k, Double.NaN).takeUnless { it.isNaN() }

        private fun JSONObject.optStr(k: String): String? =
            if (isNull(k)) null else optString(k).takeIf { it.isNotBlank() }
    }
}

/**
 * Tariff, prices, costs, history and "always on" usage.
 *
 * These change slowly, so each call to [refresh] does at most a few small requests:
 * tariff codes once a day, prices every 3 hours (public REST endpoints), today's half-hours every
 * 30 minutes, and one past day of history at a time until the last 7 days are filled.
 * Needs the API key; the live reading itself can still come from a relay.
 */
class Insights(context: Context) {

    private val prefs = Prefs(context)
    private val api = OctopusApi(context)
    private val zone: ZoneId get() = ZoneId.systemDefault()

    fun load(): InsightsData? = InsightsData.fromJson(prefs.str(K_DATA))

    /** Blocking - call off the main thread. Returns the latest data (also saved). */
    fun refresh(): InsightsData? {
        if (prefs.apiKey.isNullOrBlank()) return load()
        val now = System.currentTimeMillis()
        if (now - prefs.long(K_LAST_RUN) < MIN_GAP_MS) return load()
        prefs.setLong(K_LAST_RUN, now)

        var error: String? = null
        try {
            val token = api.token()
            api.deviceId(token)
            ensureTariffs(token)
            refreshRates()
            refreshToday(token)
            backfillOneDay(token)
        } catch (e: ApiException) {
            if (e.code?.startsWith("KT-CT-11") == true) prefs.token = null
            error = e.message
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        }
        val data = compute(error)
        prefs.setStr(K_DATA, data.toJson())
        return data
    }

    // ---------------------------------------------------------------- tariff codes

    private fun ensureTariffs(token: String) {
        if (prefs.str(K_ELEC_TARIFF) != null && prefs.str(K_MPAN) != null &&
            System.currentTimeMillis() - prefs.long(K_TARIFF_AT) < TARIFF_EVERY_MS) return
        val account = prefs.accountNumber?.takeIf { it.isNotBlank() } ?: prefs.foundAccount ?: return
        val d = api.graphql(
            """query { account(accountNumber: "${api.clean(account)}") {
              electricityAgreements(active: true) { meterPoint { mpan direction agreements(includeInactive: true) { validFrom validTo tariff { ... on TariffType { productCode tariffCode } } } } }
              gasAgreements(active: true) { meterPoint { mprn agreements(includeInactive: true) { validFrom validTo tariff { tariffCode productCode } } } } } }""",
            token,
        )
        val acc = d.getJSONObject("account")
        val elecPoints = acc.optJSONArray("electricityAgreements").objects()
            .mapNotNull { it.optJSONObject("meterPoint") }
            .filter { it.optString("direction") != "EXPORT" }
        val gasPoints = acc.optJSONArray("gasAgreements").objects().mapNotNull { it.optJSONObject("meterPoint") }
        // Meter numbers for Settings ("-" = none, so we don't ask again every time)
        prefs.setStr(K_MPAN, elecPoints.firstNotNullOfOrNull { it.optString("mpan").takeIf { m -> m.isNotBlank() } } ?: "-")
        prefs.setStr(K_MPRN, gasPoints.firstNotNullOfOrNull { it.optString("mprn").takeIf { m -> m.isNotBlank() } } ?: "-")
        val elec = elecPoints
            .flatMap { it.optJSONArray("agreements").objects() }
            .let { currentAgreement(it) }
        val gas = acc.optJSONArray("gasAgreements").objects()
            .mapNotNull { it.optJSONObject("meterPoint") }
            .flatMap { it.optJSONArray("agreements").objects() }
            .let { currentAgreement(it) }

        prefs.setStr(K_ELEC_PRODUCT, elec?.optString("productCode")?.takeIf { it.isNotBlank() })
        prefs.setStr(K_ELEC_TARIFF, elec?.optString("tariffCode")?.takeIf { it.isNotBlank() })
        prefs.setStr(K_GAS_PRODUCT, gas?.optString("productCode")?.takeIf { it.isNotBlank() })
        prefs.setStr(K_GAS_TARIFF, gas?.optString("tariffCode")?.takeIf { it.isNotBlank() })
        prefs.setLong(K_TARIFF_AT, System.currentTimeMillis())
        prefs.setLong(K_RATES_AT, 0)   // tariff may have changed: fetch prices again
    }

    /** The agreement in force now; returns its tariff object. */
    private fun currentAgreement(list: List<JSONObject>): JSONObject? {
        val now = System.currentTimeMillis()
        return list.filter { a ->
            val from = parseAgreementDate(a.optString("validFrom")) ?: Long.MIN_VALUE
            val to = if (a.isNull("validTo")) Long.MAX_VALUE else parseAgreementDate(a.optString("validTo")) ?: Long.MAX_VALUE
            from <= now && now < to
        }.maxByOrNull { parseAgreementDate(it.optString("validFrom")) ?: 0L }?.optJSONObject("tariff")
    }

    private fun parseAgreementDate(s: String?): Long? {
        if (s.isNullOrBlank() || s == "null") return null
        return OctopusApi.epoch(s) ?: try { LocalDate.parse(s.take(10)).atStartOfDay(zone).toInstant().toEpochMilli() } catch (e: Exception) { null }
    }

    // ---------------------------------------------------------------- prices (public REST)

    private fun refreshRates() {
        val elecTariff = prefs.str(K_ELEC_TARIFF) ?: return
        val elecProduct = prefs.str(K_ELEC_PRODUCT) ?: return
        val today = LocalDate.now(zone)
        // Agile and similar publish tomorrow's prices around 16:00: fetch again soon after that
        val publish = today.atTime(16, 15).atZone(zone).toInstant().toEpochMilli()
        val missedPublish = System.currentTimeMillis() >= publish && prefs.long(K_RATES_AT) < publish
        val fresh = prefs.str(K_RATES_DAY) == today.toString() &&
            System.currentTimeMillis() - prefs.long(K_RATES_AT) < RATES_EVERY_MS && !missedPublish
        if (fresh) return

        val from = today.minusDays(8).atStartOfDay(zone)
        val to = today.plusDays(2).atStartOfDay(zone)
        val base = "https://api.octopus.energy/v1/products/${enc(elecProduct)}/electricity-tariffs/${enc(elecTariff)}"
        prefs.setStr(K_ELEC_RATES, ratesToJson(fetchRates("$base/standard-unit-rates/", from, to)))
        prefs.setStr(K_ELEC_STANDING, ratesToJson(fetchRates("$base/standing-charges/", from, to)))

        val gasTariff = prefs.str(K_GAS_TARIFF)
        val gasProduct = prefs.str(K_GAS_PRODUCT)
        if (gasTariff != null && gasProduct != null) {
            val g = "https://api.octopus.energy/v1/products/${enc(gasProduct)}/gas-tariffs/${enc(gasTariff)}"
            prefs.setStr(K_GAS_RATES, ratesToJson(fetchRates("$g/standard-unit-rates/", from, to)))
            prefs.setStr(K_GAS_STANDING, ratesToJson(fetchRates("$g/standing-charges/", from, to)))
        }
        prefs.setStr(K_RATES_DAY, today.toString())
        prefs.setLong(K_RATES_AT, System.currentTimeMillis())
    }

    private fun fetchRates(url: String, from: ZonedDateTime, to: ZonedDateTime): List<Rate> {
        val utc = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
        val f = from.withZoneSameInstant(ZoneOffset.UTC).format(utc)
        val t = to.withZoneSameInstant(ZoneOffset.UTC).format(utc)
        val all = mutableListOf<JSONObject>()
        var next: String? = "$url?period_from=$f&period_to=$t&page_size=1500"
        var pages = 0
        while (next != null && pages < 5) {
            val j = api.restGet(next)
            all += j.optJSONArray("results").objects()
            next = j.optString("next").takeIf { !j.isNull("next") && it.startsWith("http") }
            pages++
        }
        // Prefer Direct Debit prices where both are listed
        val dd = all.filter { it.isNull("payment_method") || it.optString("payment_method").equals("DIRECT_DEBIT", true) }
        val chosen = dd.ifEmpty { all }
        return chosen.mapNotNull { r ->
            val p = r.optDouble("value_inc_vat", Double.NaN)
            val a = OctopusApi.epoch(r.optString("valid_from", null)) ?: Long.MIN_VALUE
            val b = if (r.isNull("valid_to")) Long.MAX_VALUE else OctopusApi.epoch(r.optString("valid_to", null)) ?: Long.MAX_VALUE
            if (p.isNaN()) null else Rate(a, b, p)
        }.sortedBy { it.from }
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    // ---------------------------------------------------------------- today's half-hours

    private fun refreshToday(token: String) {
        val today = LocalDate.now(zone).toString()
        val fresh = prefs.str(K_TODAY_DAY) == today && System.currentTimeMillis() - prefs.long(K_TODAY_AT) < TODAY_EVERY_MS
        if (fresh) return
        val elecId = prefs.deviceId ?: return
        val start = LocalDate.now(zone).atStartOfDay(zone)
        val now = ZonedDateTime.now(zone)
        prefs.setStr(K_TODAY_ELEC, slotsToJson(api.halfHourly(token, elecId, start, now)))
        val gasId = prefs.gasDeviceId
        if (gasId != null && gasId != OctopusApi.NO_GAS) {
            prefs.setStr(K_TODAY_GAS, slotsToJson(api.halfHourly(token, gasId, start, now)))
        }
        prefs.setStr(K_TODAY_DAY, today)
        prefs.setLong(K_TODAY_AT, System.currentTimeMillis())
    }

    // ---------------------------------------------------------------- history

    /** Fills the last 7 days, one day per call; re-fetches yesterday once its late gas readings are in. */
    private fun backfillOneDay(token: String) {
        val elecId = prefs.deviceId ?: return
        // History saved by older versions counted empty days and part-day always-on; start it afresh
        if (prefs.str(K_HISTORY_VER) != HISTORY_VER) {
            prefs.setStr(K_HISTORY, null)
            prefs.setStr(K_HISTORY_VER, HISTORY_VER)
        }
        val today = LocalDate.now(zone)
        val history = loadHistory().associateBy { it.date }.toMutableMap()

        val missing = (1..7).map { today.minusDays(it.toLong()) }.firstOrNull { day ->
            val rec = history[day.toString()] ?: return@firstOrNull true
            // Gas arrives about an hour late: refresh a day once, after 03:00 the next morning
            val complete = day.plusDays(1).atStartOfDay(zone).plusHours(3).toInstant().toEpochMilli()
            rec.fetchedAt < complete && System.currentTimeMillis() >= complete
        } ?: return

        val start = missing.atStartOfDay(zone)
        val end = missing.plusDays(1).atStartOfDay(zone)
        val elec = api.halfHourly(token, elecId, start, end).filter { it.first < end.toInstant().toEpochMilli() }
        val gasId = prefs.gasDeviceId?.takeIf { it != OctopusApi.NO_GAS }
        val gas = gasId?.let { id -> api.halfHourly(token, id, start, end).filter { it.first < end.toInstant().toEpochMilli() } }

        val dayStart = start.toInstant().toEpochMilli()
        // No readings at all (e.g. before the Home Mini was set up): remember the day so it isn't
        // fetched again, but with no usage or cost, so it doesn't count as a real day.
        val noData = elec.isEmpty()
        history[missing.toString()] = DayStats(
            date = missing.toString(),
            elecKwh = elec.sumOf { it.second } / 1000.0,
            gasKwh = gas?.takeIf { it.isNotEmpty() }?.let { g -> g.sumOf { it.second } / 1000.0 },
            elecCost = if (noData) null else cost(elec, rates(K_ELEC_RATES), rates(K_ELEC_STANDING), dayStart),
            gasCost = gas?.takeIf { it.isNotEmpty() }?.let { cost(it, rates(K_GAS_RATES), rates(K_GAS_STANDING), dayStart) },
            // Only a (nearly) full day gives a fair "always-on" figure; a part day may have no night-time
            baseW = if (elec.size >= FULL_DAY_SLOTS) baseload(elec) else null,
            fetchedAt = System.currentTimeMillis(),
        )
        val keep = today.minusDays(7).toString()
        val list = history.values.filter { it.date >= keep && it.date < today.toString() }.sortedBy { it.date }
        prefs.setStr(K_HISTORY, JSONArray().apply { list.forEach { put(InsightsData.dayToJson(it)) } }.toString())
    }

    private fun loadHistory(): List<DayStats> = try {
        JSONArray(prefs.str(K_HISTORY) ?: "[]").let { a -> (0 until a.length()).map { InsightsData.dayFromJson(a.getJSONObject(it)) } }
    } catch (e: Exception) { emptyList() }

    // ---------------------------------------------------------------- maths

    /** £ for a list of half-hours: sum(kWh x price) + the day's standing charge. Null if any used slot has no price. */
    private fun cost(slots: List<Pair<Long, Double>>, unit: List<Rate>, standing: List<Rate>, dayStart: Long): Double? {
        if (unit.isEmpty()) return null
        var pence = 0.0
        for ((t, wh) in slots) {
            if (wh <= 0) continue
            val r = rateAt(unit, t) ?: return null
            pence += wh / 1000.0 * r
        }
        pence += rateAt(standing, dayStart + 12 * 3600_000L) ?: 0.0
        return pence / 100.0
    }

    /** Lowest half-hour average power in watts, ignoring empty slots (gaps in the data). */
    private fun baseload(slots: List<Pair<Long, Double>>): Int? =
        slots.map { it.second }.filter { it > 0 }.minOrNull()?.let { Math.round(it * 2).toInt() }

    private fun compute(error: String?): InsightsData {
        val now = System.currentTimeMillis()
        val zoneNow = ZonedDateTime.now(zone)
        val dayStart = zoneNow.toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
        val elecRates = rates(K_ELEC_RATES)

        val sameDay = prefs.str(K_TODAY_DAY) == zoneNow.toLocalDate().toString()
        val elecSlots = if (sameDay) slotsFromJson(prefs.str(K_TODAY_ELEC)) else emptyList()
        val gasSlots = if (sameDay) slotsFromJson(prefs.str(K_TODAY_GAS)) else emptyList()

        // Today's electricity: half-hours we have, plus anything since then at the current price
        val reading = prefs.loadReading()
        val liveKwh = reading?.todayKwh ?: 0.0
        val slotKwh = elecSlots.sumOf { it.second } / 1000.0
        val priceNow = rateAt(elecRates, now)
        val elecCost = cost(elecSlots, elecRates, rates(K_ELEC_STANDING), dayStart)?.let { c ->
            c + (liveKwh - slotKwh).coerceAtLeast(0.0) * (priceNow ?: 0.0) / 100.0
        }
        val hasGas = prefs.str(K_GAS_TARIFF) != null && prefs.gasDeviceId != OctopusApi.NO_GAS
        // Gas: half-hours we have, plus anything newer that the live reading (e.g. the relay) already
        // includes, at the current gas price, so the £ always matches the kWh shown next to it
        val gasRates = rates(K_GAS_RATES)
        val gasSlotKwh = gasSlots.sumOf { it.second } / 1000.0
        val gasCost = if (hasGas) cost(gasSlots, gasRates, rates(K_GAS_STANDING), dayStart)?.let { c ->
            val liveGasKwh = reading?.gasTodayKwh ?: gasSlotKwh
            c + (liveGasKwh - gasSlotKwh).coerceAtLeast(0.0) * (rateAt(gasRates, now) ?: 0.0) / 100.0
        } else null

        val next = elecRates.firstOrNull { it.from > now && priceNow != null && Math.abs(it.p - priceNow) > 0.005 }

        // Half-hours from midnight up to the current one (later ones haven't happened yet)
        val nowSlot = ((now - dayStart) / (30 * 60_000L)).toInt().coerceIn(0, 47)
        val slots = DoubleArray(nowSlot + 1)
        val slotPence = arrayOfNulls<Double>(nowSlot + 1)
        for ((t, wh) in elecSlots) {
            val i = ((t - dayStart) / (30 * 60_000L)).toInt()
            if (i in slots.indices) {
                slots[i] = wh / 1000.0
                slotPence[i] = rateAt(elecRates, t)?.let { wh / 1000.0 * it }
            }
        }
        // Gas: up to the latest half-hour Octopus has sent
        val lastGas = gasSlots.maxOfOrNull { ((it.first - dayStart) / (30 * 60_000L)).toInt() }?.coerceIn(0, 47)
        val gasArr = DoubleArray(if (hasGas && lastGas != null) lastGas + 1 else 0)
        for ((t, wh) in gasSlots) {
            val i = ((t - dayStart) / (30 * 60_000L)).toInt()
            if (i in gasArr.indices) gasArr[i] = wh / 1000.0
        }
        val noon = dayStart + 12 * 3600_000L
        val completed = elecSlots.filter { it.first + 30 * 60_000L <= now }

        return InsightsData(
            updated = now,
            tariff = prefs.str(K_ELEC_TARIFF),
            priceNow = priceNow,
            priceNext = next?.p, priceNextAt = next?.from,
            elecCostToday = elecCost, gasCostToday = gasCost,
            todaySlots = slots.toList(),
            todayGasSlots = gasArr.toList(),
            todaySlotPence = slotPence.toList(),
            elecStanding = rateAt(rates(K_ELEC_STANDING), noon),
            gasStanding = if (hasGas) rateAt(rates(K_GAS_STANDING), noon) else null,
            mpan = prefs.str(K_MPAN)?.takeIf { it != "-" },
            mprn = prefs.str(K_MPRN)?.takeIf { it != "-" },
            baseW = baseload(completed),
            history = loadHistory(),
            error = error,
        )
    }

    /** Blocks of upcoming half-hours at or below [pence], starting within the next [horizonMs]. */
    fun cheapBlocks(pence: Double, horizonMs: Long): List<Rate> {
        val now = System.currentTimeMillis()
        val cheap = rates(K_ELEC_RATES).filter { it.to > now && it.from < now + horizonMs && it.p <= pence && it.to != Long.MAX_VALUE }
        val blocks = mutableListOf<Rate>()
        for (r in cheap) {
            val last = blocks.lastOrNull()
            if (last != null && last.to == r.from) blocks[blocks.size - 1] = Rate(last.from, r.to, minOf(last.p, r.p))
            else blocks += r
        }
        return blocks
    }

    /** Electricity prices that overlap the next [horizonMs] (used by the Gemini functions). */
    fun upcomingRates(horizonMs: Long): List<Rate> {
        val now = System.currentTimeMillis()
        return rates(K_ELEC_RATES).filter { it.to > now && it.from < now + horizonMs }.sortedBy { it.from }
    }

    private fun rates(key: String): List<Rate> = try {
        JSONArray(prefs.str(key) ?: "[]").let { a ->
            (0 until a.length()).map { a.getJSONArray(it).let { r -> Rate(r.getLong(0), r.getLong(1), r.getDouble(2)) } }
        }
    } catch (e: Exception) { emptyList() }

    private fun ratesToJson(list: List<Rate>) =
        JSONArray().apply { list.forEach { put(JSONArray().put(it.from).put(it.to).put(it.p)) } }.toString()

    private fun slotsToJson(list: List<Pair<Long, Double>>) =
        JSONArray().apply { list.forEach { put(JSONArray().put(it.first).put(it.second)) } }.toString()

    private fun slotsFromJson(s: String?): List<Pair<Long, Double>> = try {
        JSONArray(s ?: "[]").let { a -> (0 until a.length()).map { a.getJSONArray(it).let { r -> r.getLong(0) to r.getDouble(1) } } }
    } catch (e: Exception) { emptyList() }

    companion object {
        /** Price in force at time [t] (latest-starting match wins). */
        fun rateAt(list: List<Rate>, t: Long): Double? = list.filter { it.from <= t && t < it.to }.maxByOrNull { it.from }?.p

        fun formatTime(ms: Long): String =
            Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))

        private const val MIN_GAP_MS = 60_000L
        private const val HISTORY_VER = "2"
        private const val K_HISTORY_VER = "ins_historyVer"
        private const val FULL_DAY_SLOTS = 44   // of 48 half-hours (allows for a few gaps)
        private const val TARIFF_EVERY_MS = 6 * 3600_000L   // notice a tariff switch within a few hours
        private const val RATES_EVERY_MS = 3 * 3600_000L
        private const val TODAY_EVERY_MS = 30 * 60_000L

        private const val K_DATA = "ins_data"
        private const val K_LAST_RUN = "ins_lastRun"
        private const val K_ELEC_PRODUCT = "ins_elecProduct"
        private const val K_ELEC_TARIFF = "ins_elecTariff"
        private const val K_GAS_PRODUCT = "ins_gasProduct"
        private const val K_GAS_TARIFF = "ins_gasTariff"
        private const val K_TARIFF_AT = "ins_tariffAt"
        private const val K_RATES_DAY = "ins_ratesDay"
        private const val K_RATES_AT = "ins_ratesAt"
        private const val K_ELEC_RATES = "ins_elecRates"
        private const val K_ELEC_STANDING = "ins_elecStanding"
        private const val K_GAS_RATES = "ins_gasRates"
        private const val K_GAS_STANDING = "ins_gasStanding"
        private const val K_TODAY_DAY = "ins_todayDay"
        private const val K_TODAY_AT = "ins_todayAt"
        private const val K_TODAY_ELEC = "ins_todayElec"
        private const val K_TODAY_GAS = "ins_todayGas"
        private const val K_MPAN = "ins_mpan"
        private const val K_MPRN = "ins_mprn"
        private const val K_HISTORY = "ins_history"
    }
}

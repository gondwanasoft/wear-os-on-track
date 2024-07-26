package au.gondwanasoftware.ontrack

import android.util.Log
import au.gondwanasoftware.ontrack.presentation.defaultGaugeRangeIndex
import au.gondwanasoftware.ontrack.presentation.gaugeRanges
import au.gondwanasoftware.ontrack.presentation.tag
import au.gondwanasoftware.ontrack.presentation.unitsDistanceAbbrevs
import au.gondwanasoftware.ontrack.presentation.unitsEnergyAbbrevs
import java.text.DecimalFormat
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

enum class MetricType(val index: Int) {
    ENERGY(0),
    STEPS(1),
    DISTANCE(2),
    FLOORS(3)
}

data class RelData(     // data returned by calcRel()
    val rel: Float,
    val aheadString: String
)

data class CardData(    // data returned by getAheadCard()
    val relProportion: Float,
    val aheadString: String,
    val aheadPercentString: String
)

data class ComplicationData(    // data returned by getAheadComplication
    val relProportion: Float,
    val aheadString: String,
    val aheadBehind: String
)

data class TileData(    // data returned by getAheadTile
    val relProportion: Float,
    val aheadString: String,
    val aheadBehind: String
)

data class DetailData(   // data returned by getDetail
    val actStart: Float,            // proportion of day
    val actEnd: Float,              // proportion of day
    val coastAtDayStart: Float,
    val trackAtActStart: Float,     // on-track value
    val trackAtActEnd: Float,       // on-track value
    val goal: Float,                // on-track value at end of day
    val achievTime: Float,          // time of achiev reading as proportion of day
    val achievValue: Float,         // current achiev
    val trackAtAchievTime: Float,
    val coastAtAchievTime: Float    // achiev value that would have met goal if only BMR from now
)

class Metric(private val metricIndex: Int, val name: String, val icon: Int, val precision: Int) {
    // Class for activity type objects (energy, steps, distance and floors); NOT the non-imperial system of measurement.

    private var decimalFormatPatternCommas : String
    private var decimalFormatPatternNoCommas : String
    private var settings = Settings()

    // Daily constants (based on settings):
    private var todayDuration: Long = 0
    private lateinit var actStart: ZonedDateTime
    private lateinit var actEnd: ZonedDateTime
    private var actStartDuration: Long = 0
    private var actDuration: Long = 0
    private var goalToday: Float = 0f           // goalPerDay adjusted for today's actual duration by adding or subtracting bmr
    private var bmr: Float = 0f                 // units/ms
    private var effectiveBMR: Float = 0f        // units/ms

    private var unitAbbrev: String? = null
    var multiplier: Float = 1f
    private var trackBeforeAct: Float = 0f      // units
    private var trackDuringAct: Float = 0f      // units
    private var activityTrackRate: Float = 0f   // units/ms
    private var dailyConstantsDate: ZonedDateTime? = null   // date on which recalcDailyConstants() was last run
    private var areSettingsComplete: Boolean = false
    private var gaugeRange: Float = gaugeRanges[defaultGaugeRangeIndex] / 100f        // proportion

    init {
        fun makeDecimalFormatPattern(separator: String) : String {
            val whole = "###$separator##0"
            return if (precision > 0) "$whole.${"000".substring(0,precision)}" else whole
        }

        //Log.i(tagSettings, "Metric.init() name=$name isLoaded=${settings.isLoaded}")
        decimalFormatPatternCommas = makeDecimalFormatPattern(",")
        decimalFormatPatternNoCommas = makeDecimalFormatPattern("")
    }

    fun checkCurrent(dataStoreTimestamp: Long) {
        // compare currentTimestamp with settings.timestamp; if current is newer, unload settings so they'll be reloaded when needed.
        if (dataStoreTimestamp > settings.timestamp) settings.isLoaded = false
    }

    val isLoaded: Boolean
        get() = settings.isLoaded

    val bmrText: String?
        get() =
            if (metricIndex != MetricType.ENERGY.index) null
            else "(${if (settings.subtractBmr) "ex" else "in"}cluding BMR)"

    val includeCoast: Boolean       // true if coast line is angled (ie, coast can differ from goal)
        get() = metricIndex == MetricType.ENERGY.index && !settings.subtractBmr

    fun onSettingsChange(newSettings: Settings) {    // one or more settings may have changed
        settings = newSettings
        //Log.i(tagSettings, "Metric::onSettingsChange() isLoaded=${settings.isLoaded} goalSteps=${settings.goals[1]}")
        dailyConstantsDate = null     // to trigger recalcDailyConstants() when required

        // Unit Abbreviation and multiplier:
        if (metricIndex == MetricType.ENERGY.index) {
            unitAbbrev = unitsEnergyAbbrevs[if(settings.isKJ)1; else 0]
            multiplier = if (settings.isKJ) 4.184f; else 1f
            if (settings.isMale == null || settings.dob == null || settings.height == null || settings.weight == null)
                return   // no BMR settings, but why do this?
        } else if (metricIndex == MetricType.DISTANCE.index) {
            unitAbbrev = unitsDistanceAbbrevs[if(settings.isImperial)1; else 0]
            multiplier = if (settings.isImperial) 0.00062137f else 0.001f   // miles or km from metres
        }
    }

    //************************************************************************************************* Clever maths *****

    private fun recalcDailyConstants(startOfToday: ZonedDateTime, zoneId: ZoneId) {
        // Date or settings changed; recalc all vars that depend on date or settings:
        //   todayDuration, actStart, actEnd, actStartDuration, actDuration, goalToday, bmr, effectiveBmr
        // Sets areSettingsComplete: will be false if inadequate or invalid settings.
        areSettingsComplete = false
        dailyConstantsDate = startOfToday

        // Check that goal setting has a value:
        //Log.i(tagSettings, "Metric::recalcDailyConstants() isLoaded=${settings.isLoaded} checking non-null goal=${settings.goals[metricIndex]}")
        if (settings.goals[metricIndex] == null) return

        // Times and durations:
        val actStartTime = LocalTime.ofSecondOfDay(settings.actStart.toLong())
        val actEndTime = if (settings.actEnd < 86400) LocalTime.ofSecondOfDay(settings.actEnd.toLong()); else LocalTime.MAX
        val date = startOfToday.toLocalDate()
        actStart = ZonedDateTime.of(date, actStartTime, zoneId)
        actStartDuration = startOfToday.until(actStart, ChronoUnit.MILLIS)  // may be different to actStartTime if DST change
        actEnd = ZonedDateTime.of(date, actEndTime, zoneId)
        actDuration = actStart.until(actEnd, ChronoUnit.MILLIS)  // may be different to actEndTime if DST change
        //Log.i(tagSettings, "Metric::recalcDailyConstants() actStart=${settings.actStart} dur=$actDuration goal=${settings.goals[metricIndex]} subtractBMR=${settings.subtractBmr}")
        val startOfTomorrow = startOfToday.plusDays(1)
        todayDuration = startOfToday.until(startOfTomorrow, ChronoUnit.MILLIS)
        val todayDurationExcess = todayDuration - 86400000  // ms by which today exceeds 24 h

        // BMR:
        var bmrPerDay = 0f  // assumes 24-hour day, in same units as reading (ie, Calories)
        bmr = 0f
        effectiveBMR = 0f       // per ms
        if (metricIndex == MetricType.ENERGY.index) { // fix bmrPerDay, bmr and effectiveBmr
            // BMR calc is from https://dev.fitbit.com/build/reference/web-api/intraday/get-activity-intraday-by-interval/
            val dobEpochYear = settings.dob!! / 365.2421f
            val todayEpochYear = startOfToday.toEpochSecond() / 3.1556918E7f
            val ageYears = todayEpochYear - dobEpochYear    // ignores time zones
            val s = if (settings.isMale == true) 5; else -161
            bmrPerDay = 9.99f * settings.weight!! + 6.25f * settings.height!! - 4.92f * ageYears + s    // kCal/day
            bmr = bmrPerDay / 24 / 3600000  // per ms
            if (!settings.subtractBmr) effectiveBMR = bmr    // per ms
        }

        // Goal:
        goalToday = settings.goals[metricIndex]!! + todayDurationExcess * bmr
        if (bmr != 0f && settings.subtractBmr) goalToday -= todayDuration * bmr
        if (goalToday <= 0) {
            Log.e(tag, "goalToday <= 0")
            return
        }

        // Track rate:
        activityTrackRate = (settings.goals[metricIndex]!! - bmrPerDay) / actDuration  // per ms
        if (bmr != 0f && !settings.subtractBmr) activityTrackRate += bmr
        trackBeforeAct = effectiveBMR * actStartDuration
        trackDuringAct = activityTrackRate * actDuration

        // Gauge Range:
        gaugeRange = gaugeRanges[if (metricIndex == MetricType.ENERGY.index) settings.rangeEnergy; else settings.rangeOther] / 100f

        // Status:
        areSettingsComplete = true  // daily constants have been calculated and are valid for settingsDate
    }

    private data class CalcTrackResult(
        val track: Float,
        val readingDurationIntoToday: Long    // ms
    )

    private fun calcTrack(timestamp: Instant) : CalcTrackResult? {
        // timestamp: time for which the track value should be calculated.
        // Checks settingsDate and calls recalcDailyConstants() if nec, so calcTrack() should be called before any other fun uses daily constants.
        // Returns null if settings are incomplete.

        // calcTrack() is called AFTER MainActivity.onStop() for every metric (whether card is visible or not)...
        // ...seemingly because composable structure updates composables after onStop(). Probably doesn't matter.

        val zoneId = ZoneId.systemDefault()
        val zonedTimestamp: ZonedDateTime = timestamp.atZone(zoneId)
        val startOfToday = zonedTimestamp.truncatedTo(ChronoUnit.DAYS)
        if (dailyConstantsDate == null || dailyConstantsDate!! < startOfToday) recalcDailyConstants(startOfToday, zoneId)
        if (!areSettingsComplete) return null

        val readingDurationIntoToday = startOfToday.until(zonedTimestamp, ChronoUnit.MILLIS)
        val readingDurationBeyondActStart = actStart.until(zonedTimestamp, ChronoUnit.MILLIS)
        val readingDurationBeyondActEnd = actEnd.until(zonedTimestamp, ChronoUnit.MILLIS)

        // track calc must consider bmr to be 0 if subtractBmr
        val track: Float = if (readingDurationBeyondActStart <= 0) {   // before actStart
            readingDurationIntoToday * effectiveBMR
        } else if (readingDurationBeyondActEnd >= 0) {  // after actEnd
            effectiveBMR * (actStartDuration + readingDurationBeyondActEnd) + trackDuringAct
        } else {    // in activity period
            trackBeforeAct + activityTrackRate * readingDurationBeyondActStart
        }

        //Log.i(tagSettings,"calcTrack($name) track=$track")
        return CalcTrackResult(track, readingDurationIntoToday)
    }

    private fun calcRel(achiev: Float, timestamp: Instant, commas: Boolean = true) : RelData? {
        val calcTrackResult = calcTrack(timestamp)
        val track = calcTrackResult?.track ?: return null
        var ahead = achiev - track
        if (bmr != 0f && settings.subtractBmr) ahead -= bmr * calcTrackResult.readingDurationIntoToday // achiev includes BMR, so subtract it
        val rel = ahead / goalToday
        //Log.i(tagSettings, "calcRel($name) achiev=$achiev goal=$goalToday ahead=$ahead=${formatNumber(ahead)} RDIT=${calcTrackResult.readingDurationIntoToday}")
        return RelData(rel, formatNumber(ahead, commas))
    }

    //*********************************************************************************************** String formatting *****

    private fun prependPlus(numberString: String) : String {
        return if (numberString[0] != '-') "+$numberString" else numberString
    }

    fun formatNumber(number: Float, commas: Boolean = true, prependPlus: Boolean = false): String {   // does multiplier, commas and precision; doesn't prepend '+'
        val formatPattern = if (commas) decimalFormatPatternCommas; else decimalFormatPatternNoCommas
        var formatted: String = DecimalFormat(formatPattern).format(number * multiplier)
        if (formatted == "-0") formatted = "0"
        if (prependPlus) formatted = prependPlus(formatted)
        return formatted
    }

    fun formatNumberWithUnit(number: Float): String {   // for card, so always with commas
        val s = formatNumber(number)
        return if (unitAbbrev != null) "$s $unitAbbrev"; else s
    }

    private fun formatPercent(number: Float): String {
        return prependPlus((number * 100f).roundToInt().toString()) + "%"
    }

    //************************************************************** functions that return data required for display *****

    fun getAheadCard(achiev: Float, timestamp: Instant): CardData? {
        // achiev: metric progress reading.
        // timestamp: time to which achiev reading applies.
        // Returns ahead as proportion of goal (not %) scaled to relMax, ahead:String, ahead as proportion of goal formatted as % String,
        // or null if settings incomplete.

        //var timestampTest = Instant.parse("2024-03-31T13:00:00.00Z"); timestampTest = timestampTest.plus(18, ChronoUnit.HOURS)    // for testing

        val relData = calcRel(achiev, timestamp) ?: return null
        val rel = relData.rel
        var aheadString = relData.aheadString
        //Log.i(tag,"getAheadCard() $rel $aheadString")
        aheadString = prependPlus(aheadString)
        if (unitAbbrev != null) aheadString += " $unitAbbrev"
        return CardData(rel / gaugeRange, aheadString, formatPercent(rel))
    }

    fun getAheadComplication(achiev: Float, timestamp: Instant): ComplicationData? {
        // Returns (ahead as proportion of goal (not %) scaled to relMax, abs value of ahead formatted as String with commas and precision, ▲▼ if ahead),
        // or null if settings incomplete.
        //Log.i(tagSettings, "getAheadComplication() isLoaded=${settings.isLoaded} timestamp=$timestamp")
        val relData = calcRel(achiev, timestamp, false) ?: return null
        val rel = relData.rel
        var aheadString = relData.aheadString
        var aheadBehind = "▲"
        if (aheadString[0] == '-') {aheadBehind = "▼"; aheadString = aheadString.drop(1)}
        return ComplicationData(rel / gaugeRange, aheadString, aheadBehind)
    }

    fun getAheadTile(achiev: Float, timestamp: Instant): TileData? {
        // Returns (ahead as proportion of goal (not %) scaled to relMax, abs value of ahead formatted as String with commas and precision, units ahead/behind),
        // or null if settings incomplete.
        val relData = calcRel(achiev, timestamp) ?: return null
        val rel = relData.rel
        var aheadBehind = "ahead"
        var aheadString = relData.aheadString
        if (aheadString[0] == '-') {aheadBehind = "behind"; aheadString = aheadString.drop(1)}
        if (unitAbbrev != null) aheadBehind = "$unitAbbrev $aheadBehind"
        return TileData(rel / gaugeRange, aheadString, aheadBehind)
    }

    fun getDetail(achiev: Float, timestamp: Instant): DetailData? {
        // achiev: metric progress reading.
        // timestamp: time to which achiev reading applies.
        // Returns data needed for app MetricDetail composable, or null if settings incomplete.
        val calcTrackResult = calcTrack(timestamp) ?: return null
        // Can use daily constants, because calcTrack() ensures they're up-to-date.

        var achievValue = achiev
        var coastAtDayStart = goalToday     // excludes BMR if appropriate
        var trackAtActStart = 0f            // excludes BMR if appropriate
        var trackAtActEnd = goalToday       // excludes BMR if appropriate
        var coastAtAchievTime = goalToday

        if (metricIndex == MetricType.ENERGY.index) {   // make BMR-specific adjustments
            if (settings.subtractBmr) {
                achievValue -= bmr * calcTrackResult.readingDurationIntoToday
            } else {
                coastAtDayStart -= bmr * todayDuration
                trackAtActStart = bmr * actStartDuration
                trackAtActEnd = goalToday - bmr * (todayDuration - actStartDuration - actDuration)
                coastAtAchievTime = coastAtDayStart + bmr * calcTrackResult.readingDurationIntoToday
            }
        }

        return DetailData(
            actStartDuration.toFloat() / todayDuration,
            (actStartDuration + actDuration).toFloat() / todayDuration,
            coastAtDayStart,
            trackAtActStart,
            trackAtActEnd,
            goalToday,    // excludes BMR if appropriate
            calcTrackResult.readingDurationIntoToday.toFloat() / todayDuration,   // TEST reinstate
            //0.4f,    // TEST fixed value of achievTime for testing
            achievValue,    // excludes BMR if appropriate    // TEST reinstate
            //goalToday * 0.5f,     // TEST fixed value of achievValue for testing
            calcTrackResult.track,
            coastAtAchievTime
        )
    }
}

package au.gondwanasoftware.ontrack

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")
val timestampKey = longPreferencesKey("timestamp")
val isImperialKey = booleanPreferencesKey("is_imperial")
val isKJKey = booleanPreferencesKey("is_kJ")
val goalKeys = arrayOf(
    floatPreferencesKey("goal_energy"),
    floatPreferencesKey("goal_steps"),
    floatPreferencesKey("goal_distance"),
    floatPreferencesKey("goal_floors")
)
val subtractBmrKey = booleanPreferencesKey("subtract_bmr")
val isMaleKey = booleanPreferencesKey("is_male")
val dobKey = longPreferencesKey("dob")
val heightKey = floatPreferencesKey("height")
val weightKey = floatPreferencesKey("weight")
val actStartKey = intPreferencesKey("actStart")
val actEndKey = intPreferencesKey("actEnd")
val rangeEnergyKey = intPreferencesKey("range_energy")
val rangeOtherKey = intPreferencesKey("range_other")

class OnTrackDataStore private constructor() {
    // OnTrackDataStore is a singleton.
    companion object {
        val instance by lazy {
            OnTrackDataStore()
        }
    }

    suspend fun load(context: Context, key: Preferences.Key<Boolean>): Boolean? {
        // Returns the specified Boolean preference, or null if no saved DataStore.
        //Log.i(tagSettings, "OnTrackDataStore load() dataStore={datastore}")
        val value = context.dataStore.data.map { it[key] }
        //Log.i(tagSettings, "OnTrackDataStore load(key): ${value.firstOrNull()}")   // null if value isn't in dataStore (eg, first run after clean install)
        return value.firstOrNull()
    }

    suspend fun loadAll(context: Context): Map<Preferences.Key<*>, Any>? {
        // Returns map of all preferences, or null if no DataStore (eg, first run).
        // https://stackoverflow.com/questions/72097644/how-to-get-all-keys-of-android-preferences-datastore/72101981#72101981
        //Log.i(tagSettings, "OnTrackDataStore loadAll()")
        val keys = context.dataStore.data
            .map {
                it.asMap()
            }
        val map = keys.firstOrNull()
        if (map != null && map.isEmpty()) return null   // will happen if loadAll called with no DataStore
        return map
    }

    suspend fun loadToMetric(context: Context, metric: Metric) {
        // Initialises settings for specified metric.
        //Log.i(tag, "loadToMetric() starting")
        val allDataStoreData = loadAll(context)    // suspends; null if no DataStore (eg, first run)
        //val allDataStoreData: Map<Preferences.Key<*>, Any>? = null        // del testing to ignore DataStore
        //Log.i(tagSettings, "loadToMetric ${metric.name} allDataStoreData=$allDataStoreData size=${allDataStoreData?.size}")
        val settings: Settings
        if (allDataStoreData != null) { // cf. OnTrackViewModel::loadFromDataStore()
            //Log.i(tagSettings, "loadToMetric ${metric.name} initialising settings from DataStore")
            //Log.i(tag, "loadToMetric() dataStore timestamp=${allDataStoreData[timestampKey]}")
            val goalEnergy: Float? = allDataStoreData[goalKeys[MetricType.ENERGY.index]] as Float?
            val goalSteps: Float? = allDataStoreData[goalKeys[MetricType.STEPS.index]] as Float?
            val goalDistance: Float? = allDataStoreData[goalKeys[MetricType.DISTANCE.index]] as Float?
            val goalFloors: Float? = allDataStoreData[goalKeys[MetricType.FLOORS.index]] as Float?
            val goals: Array<Float?> = arrayOf(goalEnergy, goalSteps, goalDistance, goalFloors)
            settings = Settings(
                isLoaded = true,
                timestamp = (allDataStoreData[timestampKey]?:0L) as Long,
                isImperial = allDataStoreData[isImperialKey] as Boolean,
                isKJ = allDataStoreData[isKJKey] as Boolean,
                goals = goals,
                subtractBmr = allDataStoreData[subtractBmrKey] as Boolean,
                isMale = allDataStoreData[isMaleKey] as Boolean?,
                dob = allDataStoreData[dobKey] as Long?,
                height = allDataStoreData[heightKey] as Float?,
                weight = allDataStoreData[weightKey] as Float?,
                actStart = allDataStoreData[actStartKey] as Int,
                actEnd = allDataStoreData[actEndKey] as Int,
                rangeEnergy = allDataStoreData[rangeEnergyKey] as Int,
                rangeOther = allDataStoreData[rangeOtherKey] as Int
            )
        } else {     // dataStore has no data, so use default Settings. settings.isLoaded will be false.
            //Log.i(tagSettings, "loadToMetric ${metric.name} initialising settings from defaults coz no DataStore data")
            settings = Settings()
        }

        //delay(5000) // del: for testing implications of slow load (async race conditions)

        //Log.i(tagSettings, "loadToMetric ${metric.name} goalSteps=${settings.goals[1]} calling metric.onSettingsChange()")
        metric.onSettingsChange(settings) // even if settings are default, this ensures metric.recalcDailyConstants() is called when req'd
        //Log.i(tag, "loadSettings() returning; isLoaded=${settings.isLoaded} settingsTimestamp=${settings.timestamp}")
    }

    /*suspend fun clear(context: Context) {     // debugging: useful for testing clean install
        context.dataStore.edit {it.clear()}
    }*/

    /*suspend fun dump(context: Context) {      // debugging
        // https://stackoverflow.com/questions/72097644/how-to-get-all-keys-of-android-preferences-datastore/72101981#72101981
        Loog.i(tagSettings, "OnTrackDataStore dump()")
        val keys = context.dataStore.data
            .map {
                it.asMap()
            }
        val map = keys.firstOrNull()
        if (map != null && map.isEmpty()) {
            Loog.i(tagSettings, "  size=${map.size} isEmpty=${map.isEmpty()}")
            Loog.i(tagSettings, "  $map")
        } else Loog.i(tagSettings, "  null")
    }*/

    suspend fun <T> save(context: Context, key: Preferences.Key<T>, value: T?) {
        // Persists to dataStore, or removes entry if value==null.
        //Log.i(tagSettings, "OnTrackDataStore.save($value) updating timestamp")
        context.dataStore.edit { settings ->
            settings[timestampKey] = System.currentTimeMillis()
            if (value != null)
                settings[key] = value
            else
                settings.remove(key)
        }
        //Log.i(tagSettings, "OnTrackDataStore.save($value) completed")
    }

    suspend fun save(context: Context, viewModelSettings: Settings) {
        // Save all values in Settings to dataStore (only called when initialising Settings on first run after clean install).
        //Log.i(tag, "OnTrackDataStore.save(Settings) updating timestamp")

        fun saveGoal(settings: MutablePreferences, metricType: MetricType) {
            val goal = viewModelSettings.goals[metricType.index]
            if (goal != null) settings[goalKeys[metricType.index]] = goal
        }

        context.dataStore.edit { settings ->
            settings[timestampKey] = System.currentTimeMillis()
            settings[isImperialKey] = viewModelSettings.isImperial
            settings[isKJKey] = viewModelSettings.isKJ
            saveGoal(settings, MetricType.ENERGY)
            saveGoal(settings, MetricType.STEPS)
            saveGoal(settings, MetricType.DISTANCE)
            saveGoal(settings, MetricType.FLOORS)
            settings[subtractBmrKey] = viewModelSettings.subtractBmr
            if (viewModelSettings.isMale != null) settings[isMaleKey] = viewModelSettings.isMale
            if (viewModelSettings.dob != null) settings[dobKey] = viewModelSettings.dob
            if (viewModelSettings.height != null) settings[heightKey] = viewModelSettings.height
            if (viewModelSettings.weight != null) settings[heightKey] = viewModelSettings.weight
            settings[actStartKey] = viewModelSettings.actStart
            settings[actEndKey] = viewModelSettings.actEnd
            settings[rangeEnergyKey] = viewModelSettings.rangeEnergy
            settings[rangeOtherKey] = viewModelSettings.rangeOther
        }
    }

    fun getFlow(context: Context): Flow<Preferences> {
        // Used by tiles to obtain updates to Settings.
        return context.dataStore.data
    }
}
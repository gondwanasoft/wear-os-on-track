package au.gondwanasoftware.ontrack

import android.app.Application
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleCoroutineScope
import au.gondwanasoftware.ontrack.presentation.defaultGaugeRangeIndex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

@Suppress("ArrayInDataClass")
data class Settings(
    var isLoaded: Boolean = false,  // Settings have been loaded from viewModel or dataStore
    val timestamp: Long = 0,        // System.currentTimeMillis when DataStore was last updated
    val isImperial: Boolean = Locale.getDefault().country in ("US, UK"),
    val isKJ: Boolean = false,       // energy is kJ (otherwise Cal)
    val goals: Array<Float?> = arrayOf(null, 10000f, null, 10f),    // Cal, steps, m, floors
    val subtractBmr: Boolean = false,       // only applicable to ENERGY
    val isMale: Boolean? = null,
    val dob: Long? = null,                  // date of birth (epochDay)
    val height: Float? = null,              // cm
    val weight: Float? = null,              // kg
    val actStart: Int = 21600,              // seconds into non-DST day; default 6 am
    val actEnd: Int = 64800,                // seconds into non-DST day; default 6 pm
    val rangeEnergy: Int = defaultGaugeRangeIndex,
    val rangeOther: Int = defaultGaugeRangeIndex
)

class OnTrackViewModel(application: Application) : AndroidViewModel(application) {
    // OnTrackViewModel attempts to synchronise with OnTrackDataStore (DataStore) by reading
    // from DataStore when ViewModel is uninitialised, and writing ViewModel changes to DataStore.
    // See https://developer.android.com/topic/libraries/architecture/viewmodel
    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()
    private val dataStore = OnTrackDataStore.instance
    private lateinit var coroutineScope: LifecycleCoroutineScope
    private lateinit var settingsChangedListener: ()->Unit
    private lateinit var settingSavedListener: ()->Unit

    //******************************************************************************** MainActivity Synchronisation *****

    fun setCoroutineScope(lifecycleScope: LifecycleCoroutineScope) {
        coroutineScope = lifecycleScope
    }

    fun setListeners(newSettingsChangedListener: () -> Unit, newSettingSavedListener: () -> Unit) {
        settingsChangedListener = newSettingsChangedListener
        settingSavedListener = newSettingSavedListener
    }

    suspend fun onLifecycleStateChange() {
        settings.collect {
            // This is called on start (including on first run after clean install) AND whenever a setting changes.
            // This is called only once when viewModel.load() finishes.
            //Log.i(tagSettings, "OnTrackViewModel onLifecycleStateChange() settings.collect isLoaded=${it.isLoaded}")
            settingsChangedListener()
        }
    }

    //************************************************************************************** Load ViewModel *****

    suspend fun load() {
        // Call load() if viewModel settings aren't initialised.
        // load() will initialise them from dataStore, if that contains data.
        // If dataStore isn't loaded, initialise settings from defaults and write them to dataStore.
        //dataStore.clear(getApplication<Application>().applicationContext)   // del testing
        //Log.i(tagSettings, "OnTrackViewModel load() isLoaded=${isLoaded} ${settings.value.isLoaded} ${_settings.value.isLoaded}")
        //delay(5000) // del: for testing implications of slow load
        val dataStoreIsLoaded = dataStore.load(getApplication<Application>().applicationContext, isImperialKey) != null
        // ^ If dataStore exists, isImperial will have a value.
        //Log.i(tagSettings, "OnTrackViewModel load(): dataStoreIsLoaded = $dataStoreIsLoaded")
        if (dataStoreIsLoaded) loadFromDataStore(); else loadFromDefaults()

        isLoaded = true
        //Log.i(tagSettings,"OnTrackViewModel load() returning; isLoaded=${isLoaded} ${settings.value.isLoaded} ${_settings.value.isLoaded}")
    }

    private suspend fun loadFromDataStore() {   // initialise viewModel settings from dataStore
        //Log.i(tagSettings, "OnTrackViewModel loadFromDataStore()")
        // https://stackoverflow.com/questions/72097644/how-to-get-all-keys-of-android-preferences-datastore/72101981#72101981
        val allDataStoreData = dataStore.loadAll(getApplication<Application>().applicationContext)
        //Log.i(tag, "OnTrackViewModel loadFromDataStore() allDataStoreData=$allDataStoreData")
        //Log.i(tag, "OnTrackViewModel loadFromDataStore() isImperial=${allDataStoreData?.get(isImperialKey)}")
        //Log.i(tag, "OnTrackViewModel loadFromDataStore() goalEnergy=${allDataStoreData?.get(goalKeys[MetricType.ENERGY.index])}")

        _settings.update { currentState ->
            // cf. ComplicationService::loadSettings()
            val goalEnergy: Float? = allDataStoreData!![goalKeys[MetricType.ENERGY.index]] as Float?
            val goalSteps: Float? = allDataStoreData[goalKeys[MetricType.STEPS.index]] as Float?
            val goalDistance: Float? = allDataStoreData[goalKeys[MetricType.DISTANCE.index]] as Float?
            val goalFloors: Float? = allDataStoreData[goalKeys[MetricType.FLOORS.index]] as Float?
            val goals: Array<Float?> = arrayOf(goalEnergy, goalSteps, goalDistance, goalFloors)
            currentState.copy(  // This can crash if dataStore doesn't have values for all non-nullable settings
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
        }
    }

    private suspend fun loadFromDefaults() {
        // Initialise viewModel settings from defaults and save to dataStore.
        // We don't actually have to load anything to ViewModel, because Settings already have default values.
        //Log.i(tagSettings, "OnTrackViewModel loadFromDefaults()")
        //Log.i(tag, "OnTrackViewModel loadFromDefaults() isImperial=${isImperial} isKJ=${isKJ}")
        dataStore.save(getApplication<Application>().applicationContext, settings.value)    // reinstate (bypass initial population of DataStore)
        //Log.i(tagSettings, "OnTrackViewModel loadFromDefaults() returning")
    }

    //***************************************************************************************** Setting Accessors *****

    var isLoaded: Boolean   // true if viewModel data contains previous settings (rather than defaults)
        get() = settings.value.isLoaded
        set(value) {
            _settings.update { currentState -> currentState.copy(isLoaded = value) }
            //Log.i(tagSettings, "OnTrackViewModel.isLoaded().set() ${_settings.value.isLoaded}")
        }

    var isImperial: Boolean
        get() = settings.value.isImperial    // can be wrong if not fully loaded or an async save is still in progress
        set(value) {
            _settings.update { currentState -> currentState.copy(isImperial = value) }
            saveToDataStore(isImperialKey, value)
        }

    var isKJ: Boolean
        get() = settings.value.isKJ    // can be wrong if not fully loaded or an async save is still in progress
        set(value) {
            _settings.update { currentState -> currentState.copy(isKJ = value) }
            saveToDataStore(isKJKey, value)
        }

    val goals: Array<Float?>        // = settings.value.goals // don't use this as a setter; use setGoal
        get() = settings.value.goals

    fun setGoal(metricType: MetricType, value: Float?) {
        val newGoal = if (value != null && value > 0) value; else null  // convert 0 to null (unknown goal)
        val newGoals = settings.value.goals.copyOf()
        newGoals[metricType.index] = newGoal
        _settings.update { currentState -> currentState.copy(goals = newGoals) }
        saveToDataStore(goalKeys[metricType.index], newGoal)
    }

    var subtractBmr: Boolean
        get() = settings.value.subtractBmr    // can be wrong if not fully loaded or an async save is still in progress
        set(value) {
            _settings.update { currentState -> currentState.copy(subtractBmr = value) }
            saveToDataStore(subtractBmrKey, value)
        }

    var isMale: Boolean?
        get() = settings.value.isMale    // can be wrong if not fully loaded or an async save is still in progress
        set(value) {
            _settings.update { currentState -> currentState.copy(isMale = value) }
            saveToDataStore(isMaleKey, value)
        }

    var dob: Long?                      // date of birth
        get() = settings.value.dob    // can be wrong if not fully loaded or an async save is still in progress
        set(value) {
            _settings.update { currentState -> currentState.copy(dob = value) }
            saveToDataStore(dobKey, value)
        }

    var height: Float?
        get() = settings.value.height    // can be wrong if not fully loaded or an async save is still in progress
        set(value) {
            _settings.update { currentState -> currentState.copy(height = value) }
            saveToDataStore(heightKey, value)
        }

    var weight: Float?
        get() = settings.value.weight    // can be wrong if not fully loaded or an async save is still in progress
        set(value) {
            _settings.update { currentState -> currentState.copy(weight = value) }
            saveToDataStore(weightKey, value)
        }

    var actStart: Int
        get() = settings.value.actStart    // can be wrong if not fully loaded or an async save is still in progress
        set(value) {
            _settings.update { currentState -> currentState.copy(actStart = value) }
            saveToDataStore(actStartKey, value)
        }

    var actEnd: Int
        get() = settings.value.actEnd    // can be wrong if not fully loaded or an async save is still in progress
        set(value) {
            _settings.update { currentState -> currentState.copy(actEnd = value) }
            saveToDataStore(actEndKey, value)
        }

    var rangeEnergy: Int
        get() = settings.value.rangeEnergy    // can be wrong if not fully loaded or an async save is still in progress
        set(value) {
            _settings.update { currentState -> currentState.copy(rangeEnergy = value) }
            saveToDataStore(rangeEnergyKey, value)
        }

    var rangeOther: Int
        get() = settings.value.rangeOther    // can be wrong if not fully loaded or an async save is still in progress
        set(value) {
            _settings.update { currentState -> currentState.copy(rangeOther = value) }
            saveToDataStore(rangeOtherKey, value)
        }

    private fun <T> saveToDataStore(key: Preferences.Key<T>, value: T?) {
        coroutineScope.launch {
            dataStore.save(getApplication<Application>().applicationContext, key, value)
            //Log.i(tagSettings, "OnTrackViewModel saveToDataStore() continuing")
            settingSavedListener()
        }
    }
}
package au.gondwanasoftware.ontrack

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import au.gondwanasoftware.ontrack.presentation.MainActivity
import au.gondwanasoftware.ontrack.presentation.tag
import kotlinx.coroutines.launch

class ComplicConfigActivity : ComponentActivity() {
    // This Activity has no UI!
    // Complics use PROVIDER_CONFIG_ACTION to prompt for permissions and/or settings if nec:
    // https://developer.android.com/reference/kotlin/androidx/wear/watchface/complications/datasource/ComplicationDataSourceService

    private val complicNames: Array<String> = arrayOf(
        "EnergyComplicationService",
        "StepsComplicationService",
        "DistanceComplicationService",
        "FloorsComplicationService"
    )

    private var returningFromSettings = false

    private val settingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
        returningFromSettings = result.resultCode == RESULT_OK
        //returningFromSettings = true    // del testing
        //Log.i(tag,"settingsLauncher result=${result.resultCode} returningFromSettings=$returningFromSettings")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.extras == null) {
            Log.e(tag, "ComplicOnfigActivity.onCreate() called with intent.extras==null")
            reply(RESULT_CANCELED)
        }
    }

    override fun onResume() {
        super.onResume()

        suspend fun areSettingsComplete(): Boolean? {
            // Returns null if error.
            val dataStore = OnTrackDataStore.instance
            val allDataStoreData = dataStore.loadAll(applicationContext) ?: return false
            // ^ dataStore.loadAll() suspends; null if no DataStore (eg, first run)

            @Suppress("DEPRECATION")
            val complicComponentName = intent.extras?.get("android.support.wearable.complications.EXTRA_CONFIG_PROVIDER_COMPONENT").toString()
            // ^ ideally don't use .get() but Wear OS 3 (API 30) doesn't do getParcelable():
            /*val complicComponentName = intent.extras?.getParcelable(
                "android.support.wearable.complications.EXTRA_CONFIG_PROVIDER_COMPONENT",
                ComponentName::class.java
            ).toString()*/
            val lastDotIndex = complicComponentName.indexOfLast { it == '.' }
            val complicName = complicComponentName.drop(lastDotIndex + 1).dropLast(1)
            val metricTypeIndex = complicNames.indexOf(complicName)
            if (metricTypeIndex < 0) return null

            if (allDataStoreData[goalKeys[metricTypeIndex]] == null) return false

            if (metricTypeIndex > 0) return true    // no other settings needed for steps/dist/floors

            // Check whether energy-specific settings are defined:
            val isMale = allDataStoreData[isMaleKey] as Boolean?
            val dob = allDataStoreData[dobKey] as Long?
            val height = allDataStoreData[heightKey] as Float?
            val weight = allDataStoreData[weightKey] as Float?

            return isMale != null && dob != null && height != null && weight != null
        }

        fun insufficent() { // permissions and/or settings are inadequate
            if (returningFromSettings) {
                Log.w(tag, "checkPermAndSettings() settings insufficient returningFromSettings")
                reply(RESULT_CANCELED)  // user didn't fix everything; return fail
            } else {  // give user an opportunity to fix
                val settingsIntent = Intent(applicationContext, MainActivity::class.java)
                settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)    // Added in 1.1 to try to avoid multiple instances on back stack
                settingsIntent.putExtra("complic", true)
                settingsLauncher.launch(settingsIntent)
            }
        }

        // Check permission and settings:
        val isPermissionAllowed = checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
        if (!isPermissionAllowed)     // do permission on its own first, because it's quicker to check than settings
            insufficent()
        else {      // permission is allowed; consider adequacy of settings
            lifecycleScope.launch {
                val settingsComplete = areSettingsComplete()    // suspends
                if (settingsComplete == null) {
                    Log.e(tag, "checkPermAndSettings() settingsComplete==null")
                    reply(RESULT_CANCELED)    // error - shouldn't happen
                } else {
                    if (settingsComplete) {
                        //Log.i(tag, "checkPermAndSettings() settingsComplete so OK")
                        reply(RESULT_OK)
                    } else
                        insufficent()
                }
            }
        }
    }

    private fun reply(resultCode: Int) {    // send resultCode to caller
        setResult(resultCode)
        finish()
    }
}
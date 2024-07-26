package au.gondwanasoftware.ontrack.complication

import android.Manifest
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.util.Log
import androidx.health.services.client.HealthServices
import androidx.health.services.client.PassiveMonitoringClient
import androidx.health.services.client.data.DeltaDataType
import androidx.health.services.client.data.IntervalDataPoint
import androidx.health.services.client.data.PassiveListenerConfig
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import au.gondwanasoftware.ontrack.Metric
import au.gondwanasoftware.ontrack.OnTrackDataStore
import au.gondwanasoftware.ontrack.Settings
import au.gondwanasoftware.ontrack.presentation.MainActivity
import au.gondwanasoftware.ontrack.presentation.tag
import kotlinx.coroutines.launch

enum class ComplicationServiceMsg(val id: Int) {
    NONE(-1),       // no message (invalid); do nothing
    UPDATE(1),      // request update complic(s)
    SETTINGS(2)     // settings have changed
}

abstract class ComplicationService<T : Any>(private val metric: Metric,
                                            private val dataType: DeltaDataType<T, IntervalDataPoint<T>>,
                                            private val iconId: Int) : ComplicationDataSourceService() {
    // Base class used by all metric-specific complication service classes.
    // An instance of this class will be recreated frequently, so it can't retain variable values.
    private lateinit var passiveMonitoringClient: PassiveMonitoringClient
    private var isPassiveMonitoringClientListening = false  // listening for metric updates
    private var complicationRequestListener: ComplicationRequestListener? = null
    private lateinit var passiveListenerConfig : PassiveListenerConfig
    private lateinit var updateRequester: ComplicationDataSourceUpdateRequester
    private var metricReaderRequest: MetricReaderRequest? = null

    /*init {
        //Log.i(tag, "ComplicationService init()")
    }*/

    abstract fun dataPointToFloat(reading: T) : Float

    override fun onCreate() {
        super.onCreate()
        //Log.i(tag, "ComplicationService ${metric.name} onCreate()")
        if (!::passiveMonitoringClient.isInitialized)
            passiveMonitoringClient = HealthServices.getClient(this).passiveMonitoringClient
        updateRequester = ComplicationDataSourceUpdateRequester.create(this, ComponentName(this, this::class.java))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Service could have been started by MainActivity; react appropriately.
        val onTrackMsg = intent?.getIntExtra("Msg", ComplicationServiceMsg.NONE.id) ?: ComplicationServiceMsg.NONE.id
        //Log.i(tag, "ComplicationService ${metric.name} onStartCommand() msg=$onTrackMsg updateRequester=${::updateRequester.isInitialized}")
        when (onTrackMsg) {
            ComplicationServiceMsg.SETTINGS.id -> metric.onSettingsChange(Settings())
            ComplicationServiceMsg.UPDATE.id -> updateRequester.requestUpdateAll()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    /*override fun onDestroy() {
        super.onDestroy()
        //Log.i(tag,"ComplicationService ${metric.name} onDestroy()")
    }*/

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        if (type != ComplicationType.RANGED_VALUE) {
            return null
        }
        return createComplicationData(0.5f, "12", "▲", null)
    }

    override fun onComplicationActivated(complicationInstanceId: Int, type: ComplicationType) {
        // This is only called once per complic, although the object and service can be created and destroyed many times.
        super.onComplicationActivated(complicationInstanceId, type)
        //Log.i(tagSettings, "ComplicationService onComplicationActivated()")

        val permitted = checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
        //Log.i(tag, "ComplicationService${metric.name} permitted=$permitted")
        if (!permitted) {
            Log.w(tag,"onComplicationActivated(): not permitted")
            // Doesn't matter at this stage: onComplicationRequest() will prompt to "SEE APP".
        }
    }

    override fun onComplicationDeactivated(complicationInstanceId: Int) {
        super.onComplicationDeactivated(complicationInstanceId)
        //Log.i(tag, "ComplicationService $iconId onComplicationDeactivated()")
        if (metricReaderRequest != null) {
            metricReaderRequest?.cancel()
            metricReaderRequest = null
            complicationRequestListener?.onComplicationData(null)
            complicationRequestListener = null
        }
    }

    override fun onComplicationRequest(request: ComplicationRequest, listener: ComplicationRequestListener) {
        // Should fire every five minutes, as well as in response to unscheduled update requests.

        suspend fun checkSettings() {
            //Log.i(tagSettings, "ComplicationService ${metric.name} checkSettings() isLoaded=${metric.isLoaded}")
            if (!metric.isLoaded) OnTrackDataStore.instance.loadToMetric(applicationContext, metric)  // suspends
        }

        fun doRequest() {
            var isComplicationListening = true

            fun onMetricDataReceived(response: MetricRequestResponse) {
                //Log.i(tag,"ComplicationService ${metric.name} onMetricDataReceived() dataPoint=$dataPoint")
                metricReaderRequest = null
                if (!isComplicationListening) return

                var complicationData : RangedValueComplicationData? = null
                val appIntent = Intent(this, MainActivity::class.java)
                appIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)       // lint doesn't like this but it seems necessary
                val complicationPendingIntent = PendingIntent.getActivity(
                    this,
                    request.complicationInstanceId,
                    appIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )

                when (response.type) {
                    MetricReaderResponseType.OK -> {
                        val dataPoint: IntervalDataPoint<Any> = response.dataPoint!!
                        val endInstant = dataPoint.getEndInstant(bootInstant)
                        @Suppress("UNCHECKED_CAST") val readingFloat = dataPointToFloat(dataPoint.value as T)
                        val aheadData = metric.getAheadComplication(readingFloat, endInstant)   // null if missing setting(s)
                        complicationData = if (aheadData != null) createComplicationData(
                            aheadData.relProportion,   // LUXURY 9 display % rather than abs?
                            aheadData.aheadString,
                            aheadData.aheadBehind,
                            complicationPendingIntent
                        ); else createSeeAppComplicationData(complicationPendingIntent)
                    }
                    MetricReaderResponseType.NONE -> {} // timeout; use null complicationData
                    MetricReaderResponseType.E_PERM -> {
                        complicationData = createSeeAppComplicationData(complicationPendingIntent)
                    }
                }

                listener.onComplicationData(complicationData)
                complicationRequestListener = null
                isComplicationListening = false
            }       // end of onMetricDataReceived()

            //Log.i(tag,"ComplicationService ${metric.name} doRequest() request.complicationInstanceId=${request.complicationInstanceId} isLoaded=${metric.isLoaded}")

            complicationRequestListener = listener

            @Suppress("UNCHECKED_CAST")
            metricReaderRequest = MetricReader.request(this, dataType as DeltaDataType<Any, IntervalDataPoint<Any>>, ::onMetricDataReceived)
        }       // end of doRequest()

        LifecycleService().lifecycleScope.launch {
            checkSettings()
            doRequest()
        }
    }

    private fun createSeeAppComplicationData(complicationPendingIntent: PendingIntent?) =
        createComplicationData(null, "SEE", "APP", complicationPendingIntent)

    private fun createComplicationData(value: Float?, absString: String, sense: String, complicationPendingIntent: PendingIntent?) =
        // LUXURY 9 consider setColorRamp for all RangedValueComplicationData.Builder
        RangedValueComplicationData.Builder(
            // https://developer.android.com/reference/androidx/wear/watchface/complications/data/RangedValueComplicationData.Builder#setValueType(kotlin.Int)
            min = -1f,
            max = 1f,
            value = value?.coerceIn(-1f,1f) ?: RangedValueComplicationData.PLACEHOLDER,
            contentDescription = PlainComplicationText.Builder("${metric.name} $absString $sense").build()
        ).setTitle(
            PlainComplicationText.Builder(absString).build()
        ).setText(
            PlainComplicationText.Builder(sense).build()
        ).setValueType(
            RangedValueComplicationData.TYPE_RATING
        ).setMonochromaticImage(
            MonochromaticImage.Builder(Icon.createWithResource(this, iconId)).build()
        ).setTapAction(
            complicationPendingIntent
        ).build()
}
// LUXURY 9 complic: use passiveMonitoringClient.getCapabilities() cf. MainActivity; handle inability
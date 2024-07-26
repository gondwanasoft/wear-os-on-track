package au.gondwanasoftware.ontrack.tile

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.health.services.client.HealthServices
import androidx.health.services.client.PassiveListenerCallback
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DeltaDataType
import androidx.health.services.client.data.IntervalDataPoint
import androidx.health.services.client.data.PassiveListenerConfig
import androidx.lifecycle.lifecycleScope
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.VERTICAL_ALIGN_BOTTOM
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.Button
import androidx.wear.protolayout.material.CircularProgressIndicator
import androidx.wear.protolayout.material.ProgressIndicatorColors
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.protolayout.material.layouts.EdgeContentLayout
import androidx.wear.tiles.EventBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import au.gondwanasoftware.ontrack.Metric
import au.gondwanasoftware.ontrack.MetricReading
import au.gondwanasoftware.ontrack.OnTrackDataStore
import au.gondwanasoftware.ontrack.R
import au.gondwanasoftware.ontrack.presentation.angleMax
import au.gondwanasoftware.ontrack.presentation.tag
import au.gondwanasoftware.ontrack.timestampKey
import com.google.android.horologist.annotations.ExperimentalHorologistApi
import com.google.android.horologist.compose.tools.buildDeviceParameters
import com.google.android.horologist.tiles.SuspendingTileService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

interface TileServiceData {
    val metric: Metric              // abstract
    var isListening: Boolean        // abstract
    var lastReading: MetricReading? // abstract
}

private const val RESOURCES_VERSION = "0"
private var bootInstant: Instant = Instant.ofEpochMilli(System.currentTimeMillis() - SystemClock.elapsedRealtime())
private var isPermitted:Boolean? = null

@OptIn(ExperimentalHorologistApi::class)
abstract class OnTrackTileService<T : Any>(private val tileServiceData: TileServiceData, private val dataType: DeltaDataType<T, IntervalDataPoint<T>>)
    : SuspendingTileService() {

    val metric = tileServiceData.metric

    /*init {
        /Log.i(tag, "init isListening=$isListening")
    }*/

    override fun onCreate() {
        //Log.i(tag, "onCreate()")
        super.onCreate()

        val dataStore = OnTrackDataStore.instance
        lifecycleScope.launch {
            dataStore.getFlow(applicationContext).collect {     // ensure that Metrics are aware of settings changes
                //Log.i(tag, "onCreate() .collect dataStoreTimestamp=${it[timestampKey]}")
                val dataStoreTimestamp = it[timestampKey] ?: 0
                metric.checkCurrent(dataStoreTimestamp)
                // No need to request tile update coz onTileEnterEvent will always happen after returning from settings, and that updates tile.
            }
        }
    }

    /*override fun onTileAddEvent(requestParams: EventBuilders.TileAddEvent) {
        super.onTileAddEvent(requestParams)
        //Log.i(tag,"onTileAddEvent()")
    }*/

    override fun onTileEnterEvent(requestParams: EventBuilders.TileEnterEvent) {
        // It should be sufficient to check settings adequacy here rather than every tileRequest(), but tileRequest() has to use settings to...
        // ...calculate what to display anyway, so might as well not check settings here.
        super.onTileEnterEvent(requestParams)
        //Log.i(tag, "onTileEnterEvent{metric.name} isPermitted=$isPermitted")

        tileServiceData.isListening = false     // listening might have stopped while not entered; this forces startListening() on next tileRequest()

        // Kludge to ensure tileRequest happens soon after onTileEnterEvent and doesn't get stale reading:
        // Reason: sometimes tileRequest() happens before onTileEnterEvent(), and requestUpdate() doesn't seem to work...
        // ...if onTileEnterEvent() hasn't completed.
        val tileServiceClass = this::class.java
        lifecycleScope.launch {
            val updater = getUpdater(applicationContext)
            for (i in 1..4) {
                //Log.i(tag, "onTileEnterEvent(): calling requestUpdate()")
                updater.requestUpdate(tileServiceClass)
                delay(1500)
            }
        }
    }

    override suspend fun resourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ResourceBuilders.Resources {
        return ResourceBuilders.Resources.Builder()
            .setVersion(RESOURCES_VERSION)
            .addIdToImageMapping("settings_icon", ResourceBuilders.ImageResource.Builder()
                .setAndroidResourceByResId(
                    ResourceBuilders.AndroidImageResourceByResId.Builder()
                        .setResourceId(R.drawable.settings)
                        .build()
                ).build()
            )
            .addIdToImageMapping("metric_icon", ResourceBuilders.ImageResource.Builder()
                .setAndroidResourceByResId(
                    ResourceBuilders.AndroidImageResourceByResId.Builder()
                        .setResourceId(metric.icon)
                        .build()
                ).build()
            )
            .build()
    }

    override suspend fun tileRequest(requestParams: RequestBuilders.TileRequest): TileBuilders.Tile {
        //Log.i(tag, "tileRequest() isLoaded=${metric.isLoaded} isListening=${tileServiceData.isListening}")

        // LUXURY layout() if !permitted: permission-specific text and btn directly to permission

        fun buildTile(layoutElement: LayoutElementBuilders.LayoutElement) :TileBuilders.Tile {
            //Log.i(tag, "buildTile() isPermitted=$isPermitted")

            return TileBuilders.Tile.Builder()
                .setResourcesVersion(RESOURCES_VERSION)
                .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layoutElement))
                .setFreshnessIntervalMillis(60000)  // update every minute coz track can change even if achiev doesn't
                .build()
        }

        if (isPermitted != true) isPermitted = checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
        if (isPermitted != true) return buildTile(layout())    // will show btn to app settings

        if (!metric.isLoaded) OnTrackDataStore.instance.loadToMetric(applicationContext, metric)  // suspends

        return buildTile(if (tileServiceData.isListening) layout(); else startListening())
    }

    private suspend fun startListening(): LayoutElementBuilders.LayoutElement = suspendCoroutine { continuation ->
        var updateTileOnCallback = true
        val serviceObject = this
        val passiveListenerConfig = PassiveListenerConfig.builder().setDataTypes(setOf(dataType)).build()
        val passiveListenerCallback: PassiveListenerCallback = object : PassiveListenerCallback {
            override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
                val metricDataPoints = dataPoints.getData(dataType)
                if (metricDataPoints.isNotEmpty()) {
                    var mostRecentDataPoint = metricDataPoints[0]
                    metricDataPoints.forEach {
                        if (it.endDurationFromBoot >= mostRecentDataPoint.endDurationFromBoot) mostRecentDataPoint = it
                    }
                    val endInstant = mostRecentDataPoint.getEndInstant(bootInstant)
                    //Log.i(tag, "Most recent steps=${mostRecentDataPoint.value} at $endInstant")
                    var readingFloat: Float = -1f
                    val readingLong = mostRecentDataPoint.value as? Long
                    if (readingLong != null) {
                        readingFloat = readingLong.toFloat()
                    } else {
                        val readingDouble = mostRecentDataPoint.value as? Double
                        if (readingDouble != null) readingFloat = readingDouble.toFloat()
                    }   // there has got to be a better way!
                    //Log.i(tag, "readingFloat=$readingFloat")
                    tileServiceData.lastReading = MetricReading(readingFloat, endInstant)
                    if (updateTileOnCallback) {
                        updateTileOnCallback = false
                        //Log.i(tag, "onNewDataPointsReceived() returning a layout() coz updateTileOnCallback")
                        continuation.resume(layout())
                    }
                    //Log.i(tag, "onNewDataPointsReceived() calling requestUpdate() after a reading()")
                    getUpdater(applicationContext).requestUpdate(serviceObject::class.java)
                }
            }

            override fun onRegistrationFailed(throwable: Throwable) {   // can happen if perm withdrawn after start
                Log.w(tag, "startListening() onRegistrationFailed(): no permission?")
                super.onRegistrationFailed(throwable)
                isPermitted = false
                continuation.resume(layout())
            }
        }

        HealthServices.getClient(this).passiveMonitoringClient.setPassiveListenerCallback(passiveListenerConfig, passiveListenerCallback)
        // Seems unnec to restore registrations after boot coz setPassiveListenerCallback() will be called whenever app is recreated.
        // ^ https://developer.android.com/health-and-fitness/guides/health-services/monitor-background#restore-reg
        tileServiceData.isListening = true
        //Log.i(tag, "startListening() has set isListening=${tileServiceData.isListening}")
    }

// TODO clean up from here

    private fun layout(text: String? = null): LayoutElementBuilders.LayoutElement {
        // text: status message to display in lieu of reading.
        // Test layout with large fonts and long strings ("loading...", "Check settings", "22,222", "km behind").
        // LUXURY other fields
        // Initialise display vars to defaults:
        var showBtn = false     // show 'Check settings' button
        var aheadString = "⌛"
        var aheadBehind = text
        var showProgress = false
        var startAngle = 0f
        var endAngle = 0f
        var indicatorColor = Color(getColor(R.color.ahead))

        //Log.i(tag, "layout() text=${text?:"null"} isPermitted=$isPermitted")
        if (isPermitted != true) {
            showBtn = true
        } else if (text == null) {     // no status message so assume there's a reading
            if (tileServiceData.lastReading == null) Log.e(tag, "layout(): lastReading not initialised")
            val timestamp = tileServiceData.lastReading!!.timestamp
            if (timestamp == null) Log.e(tag, "layout() timestamp is null")
            if (timestamp != null) {
                val tileData = metric.getAheadTile(tileServiceData.lastReading!!.value, timestamp)
                if (tileData != null) {
                    aheadString = tileData.aheadString
                    //aheadString = "22,222"  // del test
                    aheadBehind = tileData.aheadBehind
                    val angle = (tileData.relProportion * angleMax).coerceIn(-angleMax, angleMax)
                    if (tileData.relProportion < 0) {
                        startAngle = angle
                        indicatorColor = Color(getColor(if (angle <= -angleMax) R.color.farBehind else R.color.behind))
                    } else if (tileData.relProportion > 0) {
                        endAngle = angle
                        if (angle >= angleMax) indicatorColor = Color(getColor(R.color.farAhead))
                    } else {
                        startAngle = -0.1f; endAngle = 0.1f
                    }
                    /* del screenshots:
                    aheadString = "2.35"
                    aheadBehind = "ahead"
                    startAngle = 0f
                    endAngle = 120f
                    indicatorColor = Color(getColor(R.color.ahead))*/
                    showProgress = true
                } else showBtn = true
            }
        } else indicatorColor = Color(getColor(R.color.behind))     // used for text message; probably not used

        val launchAppAction = ActionBuilders.launchAction(ComponentName(packageName, "$packageName.presentation.MainActivity"))
        val launchAppModifier = ModifiersBuilders.Clickable.Builder()
            .setId("app")
            .setOnClick(launchAppAction)
            .build()

        return EdgeContentLayout.Builder(buildDeviceParameters(this.resources))
            .setEdgeContent(
                LayoutElementBuilders.Box.Builder()
                    .addContent(CircularProgressIndicator.Builder().setStartAngle(-angleMax).setEndAngle(angleMax).build())
                    .apply {
                        if (showProgress) {
                            addContent(CircularProgressIndicator.Builder()
                                .setCircularProgressIndicatorColors(ProgressIndicatorColors(indicatorColor.toArgb(), 0))
                                .setStartAngle(startAngle)
                                .setEndAngle(endAngle)
                                .setProgress(1f)
                                .build()
                            )
                        }
                    }
                    .addContent(
                        LayoutElementBuilders.Box.Builder()
                            .setHeight(DimensionBuilders.ExpandedDimensionProp.Builder().build())
                            .setVerticalAlignment(VERTICAL_ALIGN_BOTTOM)
                            .addContent(
                                LayoutElementBuilders.Image.Builder()
                                    .setWidth(dp(24f))
                                    .setHeight(dp(24f))
                                    .setColorFilter(
                                        LayoutElementBuilders.ColorFilter.Builder()
                                            .setTint(ColorBuilders.ColorProp.Builder(Color.White.toArgb()).build())
                                            .build()
                                    )
                                    .setResourceId("metric_icon")
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            .setPrimaryLabelTextContent(
                Text.Builder(this, metric.name)
                    .setTypography(Typography.TYPOGRAPHY_TITLE2)
                    .setColor(argb(Color.White.toArgb()))
                    .setModifiers(
                        ModifiersBuilders.Modifiers.Builder()
                            .setPadding(ModifiersBuilders.Padding.Builder().setTop(dp(10f)).build())
                            .setClickable(launchAppModifier)
                            .build()
                    ).build()
            ).setContent(
                LayoutElementBuilders.Column.Builder()
                    .apply {
                        if (showBtn) {
                            addContent(
                                Text.Builder(applicationContext, "Check settings")
                                    .setColor(argb(0xfff38187.toInt()))          //.setColor(argb(Color.Red.toArgb()))
                                    .setTypography(Typography.TYPOGRAPHY_TITLE3)
                                    .setModifiers(
                                        ModifiersBuilders.Modifiers.Builder()
                                            .setPadding(ModifiersBuilders.Padding.Builder().setBottom(dp(10f)).build())
                                            .build()
                                    ).build()
                            )
                            addContent(
                                Button.Builder(
                                    applicationContext,
                                     ModifiersBuilders.Clickable.Builder().setId("settings").setOnClick(launchAppAction).build()
                                )
                                .setIconContent("settings_icon")
                                .build()
                            ).build()
                        } else {    // !showBtn
                            if (aheadBehind == null) Log.e(tag, "layout(): aheadBehind == null")
                            addContent(
                                Text.Builder(applicationContext, aheadString)
                                    .setColor(argb(indicatorColor.toArgb()))
                                    .setTypography(Typography.TYPOGRAPHY_DISPLAY1)
                                    .build()
                            )
                            addContent(
                                Text.Builder(applicationContext, aheadBehind ?: "")
                                    .setTypography(Typography.TYPOGRAPHY_CAPTION1)
                                    .setColor(argb(Color.Gray.toArgb()))
                                    .setModifiers(
                                        ModifiersBuilders.Modifiers.Builder()
                                            .setPadding(ModifiersBuilders.Padding.Builder().setBottom(dp(10f)).build())
                                            .build()
                                    )
                                    .build()
                            )
                        }
                    }
                    .setModifiers(
                        ModifiersBuilders.Modifiers.Builder()
                            .setClickable(launchAppModifier)
                            .build()
                    )
                    .build()
            )
            .setResponsiveContentInsetEnabled(true)
            .build()
    }
}
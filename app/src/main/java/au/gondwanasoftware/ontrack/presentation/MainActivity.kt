package au.gondwanasoftware.ontrack.presentation

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.health.services.client.HealthServices
import androidx.health.services.client.PassiveListenerCallback
import androidx.health.services.client.PassiveMonitoringClient
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DeltaDataType
import androidx.health.services.client.data.IntervalDataPoint
import androidx.health.services.client.data.PassiveListenerConfig
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavHostController
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Card
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.Picker
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.RadioButton
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Switch
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.material.Vignette
import androidx.wear.compose.material.VignettePosition
import androidx.wear.compose.material.rememberPickerState
import androidx.wear.compose.material.scrollAway
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import androidx.wear.tiles.TileService
import androidx.wear.tooling.preview.devices.WearDevices
import au.gondwanasoftware.ontrack.DetailData
import au.gondwanasoftware.ontrack.Metric
import au.gondwanasoftware.ontrack.MetricReading
import au.gondwanasoftware.ontrack.MetricType
import au.gondwanasoftware.ontrack.OnTrackViewModel
import au.gondwanasoftware.ontrack.R
import au.gondwanasoftware.ontrack.complication.ComplicationServiceMsg
import au.gondwanasoftware.ontrack.presentation.theme.OnTrackTheme
import com.google.android.horologist.composables.DatePicker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatter.ofLocalizedDate
import java.time.format.FormatStyle
import kotlin.math.min
import kotlin.math.round
import kotlin.math.roundToInt

const val tag = "OnTrackTag"
val unitsEnergyAbbrevs = arrayOf("Cal", "kJ")
val unitsDistanceAbbrevs = arrayOf("km", "mi")
val gaugeRanges = listOf(10, 20, 50, 100)       // percentages of goal that maximises gauge progress arcs
const val defaultGaugeRangeIndex = 2            // default index into gaugeRanges[]
const val angleMax = 160f                       // max angle of one side of progress indicator

class MainActivity : ComponentActivity() {
    private lateinit var passiveMonitoringClient: PassiveMonitoringClient
    private var permissionsGranted = false
    private var isListening = false     // whether we're receiving passiveMonitoringClient callbacks
    val energyEvent = MutableStateFlow(MetricReading())
    val stepsEvent = MutableStateFlow(MetricReading())
    val distanceEvent = MutableStateFlow(MetricReading())
    val floorsEvent = MutableStateFlow(MetricReading())
    var bootInstant: Instant = Instant.ofEpochMilli(0)  // uninitialised
    private lateinit var metrics: Array<Metric>
    private val unitsGeneralLabels = arrayOf("Metric", "Imperial/US")
    private val unitsEnergyLabels = arrayOf("Calories", "Kilojoules")
    private val genderLabels = arrayOf("Male", "Female")
    private val heightUnits = arrayOf("cm", "in")
    private val heightSteps = floatArrayOf(0.5f, 0.25f) // user height scale increments (cm, in)
    private val weightUnits = arrayOf("kg", "lb")
    private val weightSteps = floatArrayOf(0.5f, 1f)    // user weight scale increments (kg, lb)
    private val viewModel: OnTrackViewModel by viewModels()

    //********************************************************************************** Lifecycle Transitions *****

    override fun onCreate(savedInstanceState: Bundle?) {
        //Log.i(tag, "onCreate()")

        installSplashScreen()

        super.onCreate(savedInstanceState)

        setTheme(android.R.style.Theme_DeviceDefault)

        //Log.i(tagSettings, "creating Metrics")
        metrics = arrayOf(
            Metric(0,"Energy", R.drawable.energy, precision = 0),
            Metric(1, "Steps", R.drawable.steps, precision = 0),
            Metric(2, "Distance", R.drawable.distance, precision = 2),
            Metric(3, "Floors", R.drawable.floors, precision = 1)
        )

        viewModel.setCoroutineScope(lifecycleScope)
        viewModel.setListeners(::onSettingsChange, ::onSettingSaved)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                //Log.i(tagSettings, "repeatOnLifecycle() isLoaded=${viewModel.isLoaded}")
                viewModel.onLifecycleStateChange()  // calls onSettingsChange when settings change
            }
        }

        var startSettings = false     // default (full app)
        val complicConfig = intent.getBooleanExtra("complic", false)    // will be true if app started by ComplicConfigActivity
        //Log.i(tag, "onCreate() complicConfig=$complicConfig")
        if (complicConfig) {        // See if app has been started for the purpose of configuring a complication:
            setResult(RESULT_OK)     // so ComplicConfigActivity can know we were here
            startSettings = true
        } else {        // See if app has been started for the purpose of specifying settings for a tile:
            val tileId = intent.getStringExtra(TileService.EXTRA_CLICKABLE_ID)
            if (tileId == "settings") startSettings = true
        }

        setContent {
            val startDestination = if (startSettings) "Settings"; else "MetricCardList"
            AppNavHost(startDestination = startDestination)
        }

        bootInstant = Instant.ofEpochMilli(System.currentTimeMillis() - SystemClock.elapsedRealtime())
    }   // end of onCreate()

    override fun onStart() {
        super.onStart()
        //Log.i(tag, "onStart()")

        fun activate() {    // asynchronously load settings then start listening for metric readings
            // Can/should this be done in onCreate's repeatOnLifecycle?
            lifecycleScope.launch {
                if (!viewModel.isLoaded) viewModel.load()   // suspends
                startListening()
            }
        }

        val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                isGranted: Map<String, Boolean> ->
            if (isGranted[Manifest.permission.ACTIVITY_RECOGNITION] == true) {
                permissionsGranted = true
                activate()
            } else {
                Log.w(tag, "Permission denied.")
            }
        }

        permissionsGranted = checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
        if (permissionsGranted) {
            //Log.i(tag, "Permissions were already granted")
            activate()
        } else {
            requestPermissionLauncher.launch(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION))
        }
    }

    override fun onStop() {
        super.onStop()
        //Log.i(tag, "onStop()")

        if (isListening) {  // calling clearPassiveListenerCallbackAsync() when there wasn't a listener ends the process(!)
            isListening = false
            passiveMonitoringClient.clearPassiveListenerCallbackAsync()     // remove listener
        }
        messageComplics(ComplicationServiceMsg.UPDATE)  // so complications agree with app
    }

    //*********************************************************************************** Settings Callbacks *****

    private fun onSettingsChange() {
        //Log.i(tag, "MainActivity::onSettingsChange()")
        for (metric in metrics) metric.onSettingsChange(viewModel.settings.value)
    }

    private fun onSettingSaved() {
        //Log.i(tagSettings, "MainActivity::onSettingSaved()")
        messageComplics(ComplicationServiceMsg.SETTINGS)
    }

    //*************************************************************************** Complication Communication *****

    private fun messageComplics(msg: ComplicationServiceMsg) {
        fun messageComplic(intent: Intent, metricName: String) {
            val complicComponentName = ComponentName(packageName, "$packageName.complication.${metricName}ComplicationService")
            intent.setComponent(complicComponentName)
            startService(intent)
            // We should normally call stopSelf() after startService(), but OS seems to destroy services anyway (because of requestUpdateAll?)
        }

        val complicIntent = Intent()
        complicIntent.putExtra("Msg", msg.id)
        messageComplic(complicIntent, "Energy")
        messageComplic(complicIntent, "Steps")
        messageComplic(complicIntent, "Distance")
        messageComplic(complicIntent, "Floors")
    }

    //*********************************************************************************************** Running *****

    private fun startListening() {
        // Based on https://developer.android.com/health-and-fitness/guides/health-services/monitor-background#kts
        val healthClient = HealthServices.getClient(this /*context*/)
        passiveMonitoringClient = healthClient.passiveMonitoringClient
        lifecycleScope.launch {
            // Check capabilities:
            //val capabilities = passiveMonitoringClient.getCapabilities()    // can't get active minutes
            //val supportsCalories = DataType.CALORIES_DAILY in capabilities.supportedDataTypesPassiveMonitoring
            //val supportsSteps = DataType.STEPS_DAILY in capabilities.supportedDataTypesPassiveMonitoring
            //val supportsDistance = DataType.DISTANCE_DAILY in capabilities.supportedDataTypesPassiveMonitoring
            //val supportsFloors = DataType.FLOORS_DAILY in capabilities.supportedDataTypesPassiveMonitoring
            // LUXURY 9 Ensure capabilities are supported ^. Assume ALWAYS available if permission granted.

            // Register for passive data:
            val passiveListenerConfig = PassiveListenerConfig.builder()
                .setDataTypes(
                    setOf(
                        DataType.CALORIES_DAILY,
                        DataType.STEPS_DAILY,
                        DataType.DISTANCE_DAILY,
                        DataType.FLOORS_DAILY
                    )
                )
                .build()

            val passiveListenerCallback: PassiveListenerCallback =
                object : PassiveListenerCallback {
                    override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
                        fun <T : Any> emitMostRecentReading(
                            dataType: DeltaDataType<T, IntervalDataPoint<T>>,
                            metricEvent: MutableStateFlow<MetricReading>
                        ) {
                            val metricDataPoints = dataPoints.getData(dataType)
                            if (metricDataPoints.isNotEmpty()) {
                                var mostRecentDataPoint = metricDataPoints[0]
                                metricDataPoints.forEach {
                                    if (it.endDurationFromBoot >= mostRecentDataPoint.endDurationFromBoot) mostRecentDataPoint = it
                                    //Log.i(tag, "endDur=${mostRecentDataPoint.endDurationFromBoot} value=${it.value}")
                                }
                                val endInstant = mostRecentDataPoint.getEndInstant(bootInstant)
                                //Log.i(tag, "Most recent value=${mostRecentDataPoint.value} at $endInstant")
                                var readingFloat: Float = -1f       // invalid sentinel
                                val readingLong = mostRecentDataPoint.value as? Long
                                if (readingLong != null) {
                                    readingFloat = readingLong.toFloat()
                                } else {
                                    val readingDouble = mostRecentDataPoint.value as? Double
                                    if (readingDouble != null) readingFloat = readingDouble.toFloat()
                                }
                                val reading = MetricReading(readingFloat, endInstant)
                                //Log.i(tag,"onNewDataPointsReceived() $dataType.name $readingFloat")
                                val onEmitOkay = metricEvent.tryEmit(reading)
                                if (!onEmitOkay) Log.e(tag, "onEmitOkay=false")
                            }
                        }

                        emitMostRecentReading(DataType.CALORIES_DAILY, energyEvent)
                        emitMostRecentReading(DataType.STEPS_DAILY, stepsEvent)
                        emitMostRecentReading(DataType.DISTANCE_DAILY, distanceEvent)
                        emitMostRecentReading(DataType.FLOORS_DAILY, floorsEvent)
                    }

                    override fun onRegistrationFailed(throwable: Throwable) {
                        // Assume this can't happen: if permissions not granted when starting, startListening() isn't called.
                        // Permission can't be removed while app is running: doing so stops app.
                        Log.e(tag, "startListening() onRegistrationFailed(): no permission?")
                        super.onRegistrationFailed(throwable)
                    }
                }

            //Log.i(tag, "startListening() calling setPassiveListenerCallback()")
            passiveMonitoringClient.setPassiveListenerCallback(passiveListenerConfig, passiveListenerCallback)
            // Seems unnec to restore registrations after boot coz setPassiveListenerCallback() will be called whenever app is recreated.
            // ^ https://developer.android.com/health-and-fitness/guides/health-services/monitor-background#restore-reg
            isListening = true
        }
    }

    // *********************************************************************************************** UI *****

    @Composable
    fun AppNavHost(
        modifier: Modifier = Modifier,
        navController: NavHostController = rememberSwipeDismissableNavController(),
        startDestination: String = "MetricCardList"
    ) {
        SwipeDismissableNavHost(modifier = modifier, navController = navController, startDestination = startDestination) {
            composable("MetricCardList") { MetricCardList(
                onNavToSettings = { navController.navigate("Settings") },
                onNavToEnergyDetail = { navController.navigate("EnergyDetail") },
                onNavToStepsDetail = { navController.navigate("StepsDetail") },
                onNavToDistanceDetail = { navController.navigate("DistanceDetail") },
                onNavToFloorsDetail = { navController.navigate("FloorsDetail") },
            ) }
            composable("EnergyDetail") { MetricDetail(metrics[0], energyEvent) }
            composable("StepsDetail") { MetricDetail(metrics[1], stepsEvent) }
            composable("DistanceDetail") { MetricDetail(metrics[2], distanceEvent) }
            composable("FloorsDetail") { MetricDetail(metrics[3], floorsEvent) }
            composable("Settings") { SettingsList(
                onNavToUnitsSettings = { navController.navigate("UnitsSettingsList")},
                onNavToGoalsSettings = { navController.navigate("GoalsSettingsList")},
                onNavToBodySettings = { navController.navigate("BodySettingsList")},
                onNavToPeriodSettings = { navController.navigate("PeriodSettingsList")},
                onNavToRangesSettings = { navController.navigate("RangesSettingsList")}
            ) }
            composable("UnitsSettingsList") { UnitsSettingsList(
                onNavToGeneralUnits = { navController.navigate("UnitsGeneralSetting")},
                onNavToEnergyUnits = { navController.navigate("UnitsEnergySetting")}
            ) }
            composable("UnitsGeneralSetting") { UnitsGeneralSetting(isImperialInitial = viewModel.isImperial) }
            composable("UnitsEnergySetting") { UnitsEnergySetting(isKJInitial = viewModel.isKJ) }
            composable("GoalsSettingsList") { GoalsSettingsList(
                onNavToEnergy = { navController.navigate("GoalEnergySetting")},
                onNavToSteps = { navController.navigate("GoalStepsSetting")},
                onNavToDistance = { navController.navigate("GoalDistanceSetting")},
                onNavToFloors = { navController.navigate("GoalFloorsSetting")}
            ) }
            composable("GoalEnergySetting") { GoalEnergySetting(
                goalEnergyInitial = viewModel.goals[MetricType.ENERGY.index],
                subtractBmrInitial = viewModel.subtractBmr,
                onDone = { navController.popBackStack()}
            ) }
            composable("GoalStepsSetting") { GoalStepsSetting(
                goalStepsInitial = viewModel.goals[MetricType.STEPS.index],
                onDone = { navController.popBackStack()}
            ) }
            composable("GoalDistanceSetting") { GoalDistanceSetting(
                goalDistanceInitial = viewModel.goals[MetricType.DISTANCE.index],
                onDone = { navController.popBackStack()}
            ) }
            composable("GoalFloorsSetting") { GoalFloorsSetting(goalFloorsInitial = viewModel.goals[MetricType.FLOORS.index]) }
            composable("BodySettingsList") { BodySettingsList(
                onNavToGender = { navController.navigate("BodyGenderSetting")},
                onNavToDOB = { navController.navigate("BodyDOBSetting")},
                onNavToHeight = { navController.navigate("BodyHeightSetting")},
                onNavToWeight = { navController.navigate("BodyWeightSetting")}
            ) }
            composable("BodyGenderSetting") { BodyGenderSetting(isMaleInitial = viewModel.isMale) }
            composable("BodyDOBSetting") { BodyDOBSetting(
                initialDob = viewModel.dob,
                onDone = { navController.popBackStack()})
            }
            composable("BodyHeightSetting") { BodyHeightSetting(initial = viewModel.height, isImperial = viewModel.isImperial) }
            composable("BodyWeightSetting") { BodyWeightSetting(initial = viewModel.weight, isImperial = viewModel.isImperial) }
            composable("PeriodSettingsList") { PeriodSettingsList(
                onNavToStart = { navController.navigate("PeriodStartSetting")},
                onNavToEnd = { navController.navigate("PeriodEndSetting")}
            ) }
            composable("PeriodStartSetting") { PeriodStartSetting(initial = viewModel.actStart) }
            composable("PeriodEndSetting") { PeriodEndSetting(initial = viewModel.actEnd) }
            composable("RangesSettingsList") { RangesSettingsList(
                onNavToEnergyRange = { navController.navigate("RangeEnergySetting")},
                onNavToOtherRange = { navController.navigate("RangeOtherSetting")}
            ) }
            composable("RangeEnergySetting") { RangeEnergySetting(initial = viewModel.rangeEnergy) }
            composable("RangeOtherSetting") { RangeOtherSetting(initial = viewModel.rangeOther) }
        }
    }

    @Composable
    fun MetricCardList(
        onNavToSettings: (() -> Unit)? = null,
        onNavToEnergyDetail: (() -> Unit)? = null,
        onNavToStepsDetail: (() -> Unit)? = null,
        onNavToDistanceDetail: (() -> Unit)? = null,
        onNavToFloorsDetail: (() -> Unit)? = null
    ) {
        val listState = rememberScalingLazyListState()
        OnTrackTheme {
            Scaffold(
                positionIndicator = { PositionIndicator(scalingLazyListState = listState, modifier = Modifier) },
                vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) },
                timeText = { TimeText(modifier = Modifier.scrollAway(listState)) }
            ) {
                ScalingLazyColumn(state = listState) {
                    // https://developer.android.com/reference/kotlin/androidx/wear/compose/foundation/lazy/package-summary
                    item { Heading("On Track", 40.dp) }
                    item { MetricCard(metrics[0], energyEvent, permissionsGranted, onNavToEnergyDetail) }
                    item { MetricCard(metrics[1], stepsEvent, permissionsGranted, onNavToStepsDetail) }
                    item { MetricCard(metrics[2], distanceEvent, permissionsGranted, onNavToDistanceDetail) }
                    item { MetricCard(metrics[3], floorsEvent, permissionsGranted, onNavToFloorsDetail) }
                    item { SettingsBtn(onNavToSettings) }
                }
            }
        }
    }

    @Composable
    fun Heading(text: String, paddingTop: Dp = 0.dp, paddingBottom: Dp = 5.dp, colour: Color = Color.White) {
        Text(
            text = text,
            color = colour,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = paddingTop, bottom = paddingBottom)
        )
    }

    @Composable
    fun MetricCard(metric: Metric, metricEvent: MutableStateFlow<MetricReading>, permitted: Boolean, onNavToDetail: (()->Unit)?) {
        //if (!viewModel.isLoaded) Log.w(tag, "MetricCard() called before viewModel is loaded")
        val trackStartAngle = remember { 270f - angleMax }
        val trackEndAngle = remember { angleMax - 90f }
        val metricState by metricEvent.collectAsState(null)
        // metricState is null if no permission, but can be null for other reasons too.
        //if (metricState == null) Log.w(tag, "MetricCard(): metricState is null")
        val timestamp = metricState?.timestamp   // will be null if it didn't come from listener
        val aheadAbsoluteDefault = remember { if (!permitted) "⚠️"; else if (viewModel.isLoaded) ""; else "⌛" }
        var aheadAbsolute = aheadAbsoluteDefault
        val aheadRelativeDefault = remember { if (!permitted) "️No permission"; else if (viewModel.isLoaded) ""; else "Loading…" }
        var aheadRelative = aheadRelativeDefault
        var startAngle = 270f
        var endAngle = 270f
        var indicatorColor = Color(getColor(R.color.ahead))
        var drawProgress = false
        if (timestamp != null) {        // we have an actual reading
            val value: Float = metricState?.value!!
            val cardData = metric.getAheadCard(value, timestamp)
            if (cardData == null) {
                //Log.w(tagSettings, "MetricCard() displaying with missing settings")
                aheadAbsolute = "⚠️"
                aheadRelative = "Check settings"
            } else {
                aheadAbsolute = cardData.aheadString
                aheadRelative = "${cardData.aheadPercentString} of goal"
                val angle = (cardData.relProportion * angleMax).coerceIn(-angleMax, angleMax)
                if (cardData.relProportion < 0) {
                    startAngle = 270f + angle
                    indicatorColor = Color(getColor(if (angle <= -angleMax) R.color.farBehind else R.color.behind))
                } else if (cardData.relProportion > 0) {
                    endAngle = 270f + angle
                    if (angle >= angleMax) indicatorColor = Color(getColor(R.color.farAhead))
                } else {
                    startAngle = 269.9f; endAngle = 270.1f  // so a small indication is visible
                }
                drawProgress = true
            }
        }
        //Log.i(tag, "metricCard($metricName) recomposing: value=$value at $timestamp")

        Card(
            content = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(text = metric.name, fontSize = 12.sp, fontWeight = FontWeight.Normal)
                        Text(text = aheadAbsolute, fontSize = 18.sp, color = Color.White, modifier = Modifier.padding(0.dp, 3.dp))
                        Text(text = aheadRelative, fontSize = 12.sp, fontWeight = FontWeight.Normal)
                    }
                    Box {
                        CircularProgressIndicator(  // track
                            progress = 0f,          // 0 to hide
                            modifier = Modifier.size(55.dp),
                            startAngle = trackStartAngle,
                            endAngle = trackEndAngle,
                            strokeWidth = 4.dp
                        )
                        if (drawProgress) {
                            CircularProgressIndicator(      // progress
                                progress = 1f,              // 1 to fill whole of progress arc
                                modifier = Modifier.size(55.dp),
                                startAngle = startAngle,    // 270f (12 o'clock) for progress ahead of track
                                endAngle = endAngle,        // 270f (12 o'clock) for progress behind track
                                strokeWidth = 4.dp,
                                indicatorColor = indicatorColor
                            )
                        }
                        Icon(
                            painter = painterResource(id = metric.icon),
                            contentDescription = null,
                            modifier = Modifier.align(Alignment.Center).size(30.dp),
                            tint = indicatorColor
                        )
                    }
                }
            },
            onClick = onNavToDetail!!
        )
    }

    @Composable
    fun MetricDetail(metric: Metric, metricEvent: MutableStateFlow<MetricReading>) {
        val listState = rememberScalingLazyListState()
        val metricState by metricEvent.collectAsState(null)
        val bmrText = remember { metric.bmrText }
        val includeCoast = remember { metric.includeCoast }
        val axisWidth = remember { 4f }
        val actWidth = remember { 2f }
        val actEffect = remember { PathEffect.dashPathEffect(floatArrayOf(3f, 3f)) }
        val trackWidth = remember { 6f }   // should not be less than axisWidth or maths will break
        val heightProportion = remember { 0.95f }   // proportion of canvas height corresponding to metric goal (ignoring achiev)
        val axisColour = remember { Color(getColor(R.color.axis)) }
        val trackColour = remember { Color(getColor(R.color.track)) }
        val trackEffect = remember { PathEffect.dashPathEffect(floatArrayOf(6f, 6f)) }
        val trackPath = Path()
        val achievRadius = remember { 8f }
        var xScale = 0f   // px per dimensionless time unit
        var x0 = 0f       // px of time==0
        var yScale = 0f   // px per metric unit
        var y0 = 0f       // px of matric==0
        var noDataText = remember { if (!permissionsGranted) "️No permission"; else if (viewModel.isLoaded) "️"; else "Loading…" }

        val timestamp = metricState?.timestamp   // will be null if it didn't come from listener
        var detailData: DetailData? = null
        if (timestamp != null) {   // if timestamp is null, detailData will remain null
            val value: Float = metricState?.value!!
            //Log.i(tag, "MetricDetail() timestamp=$timestamp value=$value")
            detailData = metric.getDetail(value, timestamp)
            if (detailData == null) noDataText = "Check settings"
        }
        val headingPaddingBottom = if (detailData != null && bmrText != null) 0.dp; else 5.dp

        OnTrackTheme {
            Scaffold(
                positionIndicator = { PositionIndicator(scalingLazyListState = listState, modifier = Modifier) },
                vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) }
            ) {
                ScalingLazyColumn(state = listState, horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    item {       // icon and heading are grouped so that next item is centred vertically on initial display
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                painter = painterResource(id = metric.icon),
                                contentDescription = null,
                                tint = Color.Gray
                            )
                            Heading(metric.name, colour = Color(getColor(R.color.achiev)), paddingBottom = headingPaddingBottom)
                        }
                    }
                    if (detailData != null) {
                        if (bmrText != null)
                            item {
                                Text(
                                    bmrText,
                                    Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                    Color.Gray,
                                    12.sp,
                                    textAlign = TextAlign.Center
                                )
                            }

                        item { Row(Modifier.padding(bottom = 8.dp)) {
                            Column {
                                TableText("Actual:")
                                TableText("Track:")
                                if (includeCoast) TableText("Coast:")
                                TableText("Goal:")
                            }
                            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(horizontal = 6.dp)) {
                                TableText(metric.formatNumber(detailData.achievValue))
                                TableText(metric.formatNumber(detailData.trackAtAchievTime))
                                if (includeCoast) TableText(metric.formatNumber(detailData.coastAtAchievTime))
                                TableText(metric.formatNumber(detailData.goal))
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                TableText("")
                                TableText(metric.formatNumber(detailData.achievValue - detailData.trackAtAchievTime,
                                    commas = true,
                                    prependPlus = true
                                ))
                                if (includeCoast)
                                    TableText(metric.formatNumber(detailData.achievValue - detailData.coastAtAchievTime,
                                        commas = true,
                                        prependPlus = true
                                    ))
                                TableText(text = metric.formatNumber(
                                    number = detailData.achievValue - detailData.goal,
                                    commas = true,
                                    prependPlus = true
                                ))
                            }
                        }}

                        item {
                            Canvas(
                                modifier = Modifier.fillMaxWidth(0.8f).fillParentMaxHeight(0.6f)
                            ) {
                                fun scaleX(time: Float) : Float {return time * xScale + x0}    // time: 0 to 1
                                fun scaleY(value: Float) : Float {return yScale * (detailData.goal - value) + y0}    // value: metric units
                                fun scale(time: Float, value: Float) : Offset {return Offset(scaleX(time), scaleY(value))} // time: 0 to 1; value: metric units

                                // Calc scaling values:
                                xScale = size.width - axisWidth         // assuming achiev circle is between vert axes
                                x0 = axisWidth / 2                      // assuming achiev circle is between vert axes
                                if (detailData.achievTime < 0.5f) {     // achiev circle might extend left of left axis
                                    val xScaleAchiev = (size.width - axisWidth/2 - achievRadius) / (1f - detailData.achievTime)
                                    if (xScaleAchiev < xScale) {
                                        xScale = xScaleAchiev
                                        x0 = achievRadius - xScale * detailData.achievTime
                                    }
                                } else {  // achiev circle might extend beyond right of right axis
                                    val xScaleAchiev = (size.width - axisWidth/2 - achievRadius) / detailData.achievTime
                                    xScale = min(xScale, xScaleAchiev)
                                }
                                yScale = heightProportion / detailData.goal * (size.height - trackWidth / 2f)
                                y0 = size.height - trackWidth / 2f - yScale * detailData.goal
                                if (detailData.achievValue < detailData.goal / 2f) {  // achiev circle might extend below metric=0 axis
                                    val yScaleAchiev = (size.height - achievRadius) / (detailData.goal / heightProportion - detailData.achievValue)
                                    if (yScaleAchiev < yScale) {
                                        yScale = yScaleAchiev
                                        y0 = detailData.goal * (1f / heightProportion - 1f) * yScale
                                    }
                                } else {        // achiev circle might extend above metric area
                                    val yScaleAchiev = (size.height - trackWidth / 2f - achievRadius) / detailData.achievValue
                                    if (yScaleAchiev < yScale) {
                                        yScale = yScaleAchiev
                                        y0 = achievRadius - (detailData.goal - detailData.achievValue) * yScale
                                    }
                                }

                                //drawRect(color = Color.DarkGray, size = size)    // del test: background for whole canvas
                                var x = scaleX(0f)
                                val yAxis = scaleY(0f) + axisWidth / 2f
                                drawLine(axisColour, Offset(x, 0f), Offset(x, yAxis), axisWidth)   // time=0
                                //drawLine(Color.Blue, Offset(20f, 0f), Offset(20f, size.height/2), axisWidth)   // del width test
                                x = scaleX(detailData.actStart)
                                drawLine(axisColour, Offset(x, 0f), Offset(x, yAxis), actWidth, pathEffect = actEffect)   // time=actStart
                                x = scaleX(detailData.actEnd)
                                drawLine(axisColour, Offset(x, 0f), Offset(x, yAxis), actWidth, pathEffect = actEffect)   // time=actEnd
                                x = scaleX(1f)
                                drawLine(axisColour, Offset(x, 0f), Offset(x, yAxis), axisWidth)   // time=24hr
                                drawLine(axisColour, scale(0f, 0f), scale(1f, 0f), axisWidth)  // activity=0
                                drawLine(trackColour, scale(0f, detailData.coastAtDayStart), scale(1f, detailData.goal), actWidth)  // coast
                                // Track:
                                var coord = scale(0f, 0f)
                                trackPath.moveTo(coord.x, coord.y)
                                coord = scale(detailData.actStart, detailData.trackAtActStart)
                                trackPath.lineTo(coord.x, coord.y)
                                coord = scale(detailData.actEnd, detailData.trackAtActEnd)
                                trackPath.lineTo(coord.x, coord.y)
                                coord = scale(1f, detailData.goal)
                                trackPath.lineTo(coord.x, coord.y)
                                drawPath(path = trackPath, color = trackColour, style = Stroke(width = trackWidth, pathEffect = trackEffect))
                                drawCircle(
                                    color = Color(getColor(R.color.achiev)),
                                    achievRadius,
                                    scale(detailData.achievTime, detailData.achievValue)
                                )
                            }
                        }
                    } else {
                        item { Text(text = "⚠️", fontSize = 18.sp, color = Color.White, modifier = Modifier.padding(0.dp, 3.dp)) }
                        item { Text(noDataText, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
                    }
                }
            }
        }
    }

    @Composable
    fun TableText(text: String) {
        Text(text = text, fontSize = 14.sp, softWrap = false, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }

    @Composable
    fun SettingsBtn(onNavToSettings: (()->Unit)?) {
        Button(
            onClick = onNavToSettings!!,
            colors = ButtonDefaults.secondaryButtonColors()
        ) {
            Icon(painter = painterResource(id = R.drawable.settings), contentDescription = "Settings")
        }
    }

    private fun requestPermission() {
        //Log.i(tag,"requestPermission()")
        //requestPermissionLauncher.launch(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION)) // BODY_SENSORS_BACKGROUND seems unnecessary
        //requestPermissions(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION), 42)
        // Could only retry an internal solution (above) only once: deferring to Android Settings is repeatable and consistent:
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        val uri = Uri.fromParts("package", packageName, null)
        intent.setData(uri)
        startActivity(intent)
    }

    @Composable
    fun SettingsList(
        onNavToUnitsSettings: (() -> Unit)? = null,
        onNavToGoalsSettings: (() -> Unit)? = null,
        onNavToBodySettings: () -> Unit,
        onNavToPeriodSettings: () -> Unit,
        onNavToRangesSettings: () -> Unit
    ) {
        val listState = rememberScalingLazyListState()
        val bodyUnknown = viewModel.isMale == null || viewModel.dob == null || viewModel.height == null || viewModel.weight == null
        val goalsUnknown = viewModel.goals.contains(null)

        OnTrackTheme {
            Scaffold(
                positionIndicator = { PositionIndicator(scalingLazyListState = listState, modifier = Modifier) },
                vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) }
            ) {
                ScalingLazyColumn(state = listState) {
                    // https://developer.android.com/reference/kotlin/androidx/wear/compose/foundation/lazy/package-summary
                    item { Heading("Settings", 40.dp) }
                    if (!permissionsGranted) item { Chip(
                        modifier = Modifier.fillMaxWidth(),
                        label = {Text("Permission")},
                        secondaryLabel = {Text("⚠️ Not allowed")},
                        icon = {Icon(painter = painterResource(id = R.drawable.permission), contentDescription = "Permission")},
                        colors = ChipDefaults.secondaryChipColors(),
                        onClick = ::requestPermission
                    )}
                    item { Chip(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onNavToUnitsSettings!!,
                        label = {Text("Units")},
                        icon = {Icon(painter = painterResource(id = R.drawable.units), contentDescription = "Units")},
                        colors = ChipDefaults.secondaryChipColors()
                    )}
                    item { Chip(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onNavToBodySettings,
                        label = {Text("Body")},
                        secondaryLabel = {if (bodyUnknown) Text("⚠️ not set")},
                        icon = {Icon(painter = painterResource(id = R.drawable.body), contentDescription = "Body")},
                        colors = ChipDefaults.secondaryChipColors()
                    )}
                    item { Chip(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onNavToGoalsSettings!!,
                        label = {Text("Goals")},
                        secondaryLabel = {if (goalsUnknown) Text("⚠️ not set")},
                        icon = {Icon(painter = painterResource(id = R.drawable.goals), contentDescription = "Goals")},
                        colors = ChipDefaults.secondaryChipColors()
                    )}
                    item { Chip(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onNavToPeriodSettings,
                        label = {Text("Active Period")},
                        icon = {Icon(painter = painterResource(id = R.drawable.time), contentDescription = "Active Period")},
                        colors = ChipDefaults.secondaryChipColors()
                    )}
                    item { Chip(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onNavToRangesSettings,
                        label = {Text("Gauge Ranges")},
                        icon = {Icon(painter = painterResource(id = R.drawable.range), contentDescription = "Gauge Ranges")},
                        colors = ChipDefaults.secondaryChipColors()
                    )}
                }
            }
        }
    }

    @Composable
    fun UnitsSettingsList(onNavToGeneralUnits: ()->Unit, onNavToEnergyUnits: ()->Unit) {
        //val coroutineScope = rememberCoroutineScope()
        val listState = rememberScalingLazyListState()
        OnTrackTheme {
            Scaffold(
                positionIndicator = { PositionIndicator(scalingLazyListState = listState, modifier = Modifier) },
                vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) }
            ) {
                ScalingLazyColumn(state = listState) {
                    // https://developer.android.com/reference/kotlin/androidx/wear/compose/foundation/lazy/package-summary
                    item { Heading("Units", 40.dp) }
                    item { Chip(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            onNavToGeneralUnits()
                        },
                        label = {Text("General")},
                        secondaryLabel = {Text(unitsGeneralLabels[if (viewModel.isImperial) 1 else 0])},
                        icon = {Icon(painter = painterResource(id = R.drawable.units), contentDescription = "General")},
                        colors = ChipDefaults.secondaryChipColors()
                    )}
                    item { Chip(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onNavToEnergyUnits,
                        label = {Text("Energy")},
                        secondaryLabel = {Text(unitsEnergyLabels[if (viewModel.isKJ) 1 else 0])},
                        icon = {Icon(painter = painterResource(id = R.drawable.energy), contentDescription = "Energy")},
                        colors = ChipDefaults.secondaryChipColors()
                    )}
                }
            }
        }
    }

    @Composable
    fun UnitsGeneralSetting(isImperialInitial: Boolean) {
        val listState = rememberScalingLazyListState()
        val isImperialNew = remember { mutableStateOf(isImperialInitial) }

        DisposableEffect(Unit) {
            onDispose {
                viewModel.isImperial = isImperialNew.value    // causes viewModel.settings.collect to fire
            }
        }

        OnTrackTheme {
            Scaffold(
                positionIndicator = { PositionIndicator(scalingLazyListState = listState, modifier = Modifier) },
                vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) }
            ) {
                ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
                    // https://developer.android.com/reference/kotlin/androidx/wear/compose/foundation/lazy/package-summary
                    item { Heading("General Units", 40.dp)}
                    item { ToggleChip(
                        label = {Text(unitsGeneralLabels[0])},
                        modifier = Modifier.fillMaxWidth(),
                        checked = false,
                        onCheckedChange = { isImperialNew.value = false },
                        toggleControl = { RadioButton(selected = !isImperialNew.value, onClick = null) }
                    )}
                    item { ToggleChip(
                        label = {Text(unitsGeneralLabels[1])},
                        modifier = Modifier.fillMaxWidth(),
                        checked = false,
                        onCheckedChange = { isImperialNew.value = true },
                        toggleControl = { RadioButton(selected = isImperialNew.value, onClick = null) }
                    )}
                }
            }
        }
    }

    @Composable
    fun UnitsEnergySetting(isKJInitial: Boolean) {
        val listState = rememberScalingLazyListState()
        val isKJNew = remember { mutableStateOf(isKJInitial) }

        DisposableEffect(Unit) {
            onDispose {
                viewModel.isKJ = isKJNew.value    // causes viewModel.settings.collect to fire
            }
        }

        OnTrackTheme {
            Scaffold(
                positionIndicator = { PositionIndicator(scalingLazyListState = listState, modifier = Modifier) },
                vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) }
            ) {
                ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
                    item { Heading("Energy Units", 40.dp)}
                    item { ToggleChip(
                        label = {Text(unitsEnergyLabels[0])},
                        modifier = Modifier.fillMaxWidth(),
                        checked = false,
                        onCheckedChange = {isKJNew.value = false},
                        toggleControl = { RadioButton(selected = !isKJNew.value, onClick = null) }
                    ) }
                    item { ToggleChip(
                        label = {Text(unitsEnergyLabels[1])},
                        modifier = Modifier.fillMaxWidth(),
                        checked = false,
                        onCheckedChange = {isKJNew.value = true},
                        toggleControl = { RadioButton(selected = isKJNew.value, onClick = null) })}
                }
            }
        }
    }

    @Composable
    fun GoalsSettingsList(
        onNavToEnergy: (() -> Unit)? = null,
        onNavToSteps: (() -> Unit)? = null,
        onNavToDistance: (() -> Unit)? = null,
        onNavToFloors: (() -> Unit)? = null
    ) {
        val listState = rememberScalingLazyListState()
        OnTrackTheme {
            Scaffold(
                positionIndicator = { PositionIndicator(scalingLazyListState = listState, modifier = Modifier) },
                vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) }
            ) {
                ScalingLazyColumn(state = listState) {
                    item { Heading("Goals", 40.dp) }
                    item { Chip(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onNavToEnergy!!,
                        label = {Text("Energy")},
                        secondaryLabel = {Text(formatGoal(0, viewModel.goals[MetricType.ENERGY.index]))},
                        icon = {Icon(painter = painterResource(id = R.drawable.energy), contentDescription = "Energy")},
                        colors = ChipDefaults.secondaryChipColors()
                    )}
                    item { Chip(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onNavToSteps!!,
                        label = {Text("Steps")},
                        secondaryLabel = { Text(formatGoal(1, viewModel.goals[MetricType.STEPS.index])) },
                        icon = {Icon(painter = painterResource(id = R.drawable.steps), contentDescription = "Steps")},
                        colors = ChipDefaults.secondaryChipColors()
                    )}
                    item { Chip(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onNavToDistance!!,
                        label = {Text("Distance")},
                        secondaryLabel = {Text(formatGoal(2, viewModel.goals[MetricType.DISTANCE.index]))},
                        icon = {Icon(painter = painterResource(id = R.drawable.distance), contentDescription = "Distance")},
                        colors = ChipDefaults.secondaryChipColors()
                    )}
                    item { Chip(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onNavToFloors!!,
                        label = {Text("Floors")},
                        secondaryLabel = {Text(formatGoal(3, viewModel.goals[MetricType.FLOORS.index]))},
                        icon = {Icon(painter = painterResource(id = R.drawable.floors), contentDescription = "Floors")},
                        colors = ChipDefaults.secondaryChipColors()
                    )}
                }
            }
        }
    }

    private fun formatGoal(metric: Int, goal: Float?) : String {
        return if (goal != null) metrics[metric].formatNumberWithUnit(goal); else "⚠️ not set"
    }

    @Composable
    fun GoalEnergySetting(goalEnergyInitial: Float?, subtractBmrInitial: Boolean, onDone: (() -> Unit)? = null) {
        val multiplier = metrics[0].multiplier
        val goalEnergyInitialText = if (goalEnergyInitial != null) (goalEnergyInitial*multiplier).roundToInt().toString(); else ""
        var text by remember { mutableStateOf(goalEnergyInitialText) }
        val includeBMR = remember { mutableStateOf(!subtractBmrInitial) }
        val keyboardController = LocalSoftwareKeyboardController.current

        val onValueChange: (String) -> Unit = { newValue -> text = newValue }

        DisposableEffect(Unit) {
            onDispose {
                viewModel.subtractBmr = !includeBMR.value    // causes viewModel.settings.collect to fire
            }
        }

        val listState = rememberScalingLazyListState()
        OnTrackTheme {
            Scaffold(
                positionIndicator = { PositionIndicator(scalingLazyListState = listState, modifier = Modifier) },
                vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) }
            ) {
                ScalingLazyColumn(state = listState) {
                    item { Heading("Energy Goal", 40.dp) }
                    item {
                        // From Copilot 'Using Jetpack Compose on Wear OS, how do I input a number?':
                        TextField(  // Android bug: misplaced selectionHandle (can't be hidden)
                            value = text,
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Decimal),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    keyboardController?.hide()
                                    viewModel.setGoal(MetricType.ENERGY, if (text != "") text.toFloat() / multiplier; else null)
                                    if (onDone != null) onDone()    // navigate back to GoalsSettingsList
                                }
                            ),
                            onValueChange = onValueChange
                        )
                    }
                    item { ToggleChip(
                        label = {Text("Include BMR")},
                        modifier = Modifier.fillMaxWidth(),
                        checked = includeBMR.value,
                        onCheckedChange = { includeBMR.value = it; },
                        toggleControl = { Switch(checked = includeBMR.value) }
                    )}
                }
            }
        }
    }

    @Composable
    fun GoalStepsSetting(goalStepsInitial: Float?, onDone: (() -> Unit)? = null) {
        val goalStepsInitialText = goalStepsInitial?.roundToInt()?.toString() ?: ""
        var text by remember { mutableStateOf(goalStepsInitialText) }
        val keyboardController = LocalSoftwareKeyboardController.current

        val onValueChange: (String) -> Unit = { newValue -> text = newValue }

        val listState = rememberScalingLazyListState()
        OnTrackTheme {
            Scaffold(
                positionIndicator = { PositionIndicator(scalingLazyListState = listState, modifier = Modifier) },
                vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) }
            ) {
                ScalingLazyColumn(state = listState) {
                    item { Heading("Steps Goal", 40.dp) }
                    item {
                        // From Copilot 'Using Jetpack Compose on Wear OS, how do I input a number?':
                        TextField(  // Android bug: misplaced selectionHandle (can't be hidden)
                            value = text,
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Decimal),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    keyboardController?.hide()
                                    viewModel.setGoal(MetricType.STEPS, if (text != "") text.toFloat(); else null)
                                    if (onDone != null) onDone()    // navigate back to GoalsSettingsList
                                }
                            ),
                            onValueChange = onValueChange
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun GoalDistanceSetting(goalDistanceInitial: Float?, onDone: (() -> Unit)? = null) {
        val multiplier = metrics[2].multiplier
        val goalDistanceInitialText = if (goalDistanceInitial != null) metrics[2].formatNumber(goalDistanceInitial); else ""
        var text by remember { mutableStateOf(goalDistanceInitialText) }
        val keyboardController = LocalSoftwareKeyboardController.current

        val onValueChange: (String) -> Unit = { newValue -> text = newValue }

        val listState = rememberScalingLazyListState()
        OnTrackTheme {
            Scaffold(
                positionIndicator = { PositionIndicator(scalingLazyListState = listState, modifier = Modifier) },
                vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) }
            ) {
                ScalingLazyColumn(state = listState) {
                    item { Heading("Distance Goal", 40.dp) }
                    item {
                        // From Copilot 'Using Jetpack Compose on Wear OS, how do I input a number?':
                        TextField(  // Android bug: misplaced selectionHandle (can't be hidden)
                            value = text,
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Decimal),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    keyboardController?.hide()
                                    viewModel.setGoal(MetricType.DISTANCE, if (text != "") text.toFloat() / multiplier; else null)
                                    if (onDone != null) onDone()    // navigate back to GoalsSettingsList
                                }
                            ),
                            onValueChange = onValueChange
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun GoalFloorsSetting(goalFloorsInitial: Float?) {
        val state = rememberPickerState(initialNumberOfOptions = 101, initiallySelectedOption = (goalFloorsInitial?.roundToInt() ?: 0), repeatItems = false)
        val contentDescription by remember { derivedStateOf { "${state.selectedOption} floors" } }
        val listState = rememberScalingLazyListState()

        DisposableEffect(Unit) {
            onDispose {
                viewModel.setGoal(MetricType.FLOORS, state.selectedOption.toFloat())    // causes viewModel.settings.collect to fire
            }
        }

        OnTrackTheme {
            Scaffold(
                positionIndicator = { PositionIndicator(scalingLazyListState = listState, modifier = Modifier) },
                vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) }
            ) {
                Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Heading("Floors Goal", 20.dp)
                    Picker(
                        state = state,
                        modifier = Modifier.height(100.dp),
                        contentDescription = contentDescription,
                        option = { optionIndex: Int -> Text(text = optionIndex.toString(), fontSize = 30.sp) }
                    )
                }
            }
        }
    }

    @Composable
    fun BodySettingsList(
        onNavToGender: () -> Unit,
        onNavToDOB: () -> Unit,
        onNavToHeight: () -> Unit,
        onNavToWeight: () -> Unit
    ) {
        val listState = rememberScalingLazyListState()

        OnTrackTheme {
            Scaffold(
                positionIndicator = { PositionIndicator(scalingLazyListState = listState, modifier = Modifier) },
                vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) }
            ) {
                ScalingLazyColumn(state = listState) {
                    item { Heading("Body", 40.dp) }
                    item { Chip(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onNavToGender,
                        label = {Text("Gender")},
                        secondaryLabel = {Text(if (viewModel.isMale != null) genderLabels[if (viewModel.isMale!!) 0; else 1]; else "⚠️ not set")},
                        icon = {Icon(painter = painterResource(id = R.drawable.gender), contentDescription = "Gender")},
                        colors = ChipDefaults.secondaryChipColors()
                    )}
                    item { Chip(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onNavToDOB,
                        label = {Text("Date of birth")},
                        secondaryLabel = {Text(
                            if (viewModel.dob != null) LocalDate.ofEpochDay(viewModel.dob!!).format(ofLocalizedDate(FormatStyle.SHORT)); else "⚠️ not set"
                        )},
                        icon = {Icon(painter = painterResource(id = R.drawable.date), contentDescription = "Date of birth")},
                        colors = ChipDefaults.secondaryChipColors()
                    )}
                    item { Chip(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onNavToHeight,
                        label = {Text("Height")},
                        secondaryLabel = {Text(valueLabel(viewModel.height, 0.3937f, heightSteps, heightUnits))},
                        icon = {Icon(painter = painterResource(id = R.drawable.height), contentDescription = "Height")},
                        colors = ChipDefaults.secondaryChipColors()
                    )}
                    item { Chip(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onNavToWeight,
                        label = {Text("Weight")},
                        secondaryLabel = {Text(valueLabel(viewModel.weight, 2.204623f, weightSteps, weightUnits))},
                        icon = {Icon(painter = painterResource(id = R.drawable.weight), contentDescription = "Weight")},
                        colors = ChipDefaults.secondaryChipColors()
                    )}
                }
            }
        }
    }

    private fun valueLabel(value: Float?, multiplier: Float, steps: FloatArray, units: Array<String>): String {
        // Rounds a value to nearest step and appends unit.
        // multiplier: to convert from imperial to metric, if required.
        // steps: difference between successive values [0=metric, 1=imperial]
        // units: measurement units [0=metric, 1=imperial]
        if (value == null) return "⚠️ unknown"
        var displayValue : Float = value
        if (viewModel.isImperial) displayValue *= multiplier
        val step = steps[if (viewModel.isImperial) 1 else 0]
        displayValue = (displayValue / step).roundToInt() * step
        val unit = units[if (viewModel.isImperial) 1 else 0]
        return "$displayValue $unit"
    }

    @Composable
    fun BodyGenderSetting(isMaleInitial: Boolean?) {
        val listState = rememberScalingLazyListState()
        val isMale = remember { mutableStateOf(isMaleInitial) }

        DisposableEffect(Unit) {
            onDispose {
                viewModel.isMale = isMale.value    // causes viewModel.settings.collect to fire
            }
        }

        OnTrackTheme {
            Scaffold(
                positionIndicator = { PositionIndicator(scalingLazyListState = listState, modifier = Modifier) },
                vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) }
            ) {
                ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
                    item { Heading("Gender", 40.dp) }
                    item { ToggleChip(
                        label = {Text(genderLabels[0])},
                        modifier = Modifier.fillMaxWidth(),
                        checked = false,
                        onCheckedChange = { isMale.value = true},
                        toggleControl = { RadioButton(selected = isMale.value == true, onClick = null) }
                    )}
                    item { ToggleChip(
                        label = {Text(genderLabels[1])},
                        modifier = Modifier.fillMaxWidth(),
                        checked = false,
                        onCheckedChange = { isMale.value = false},
                        toggleControl = { RadioButton(selected = isMale.value == false, onClick = null) }
                    )}
                }
            }
        }
    }

    @Composable
    fun BodyDOBSetting(initialDob: Long?, onDone: () -> Unit) {
        val fromDate = remember { LocalDate.of(1900,1,1) }
        val toDate = remember { LocalDate.now()}
        val initialDate = if (initialDob != null) LocalDate.ofEpochDay(initialDob); else LocalDate.of(2000,1,1)

        DatePicker(
            fromDate = fromDate,
            toDate = toDate,
            date = initialDate,
            onDateConfirm = {
                viewModel.dob = it.toEpochDay()
                onDone()
            }
        )
    }

    @Composable
    fun BodyHeightSetting(initial: Float?, isImperial: Boolean) {
        val minHeight = remember {if (isImperial) 50 else 135}
        val maxHeight = remember {if (isImperial) 85 else 210}
        val imperialIndex = if (isImperial) 1 else 0
        val step = remember {heightSteps[imperialIndex]}
        val unit = remember {heightUnits[imperialIndex]}
        val numberOfOptions = remember {round((maxHeight - minHeight) / step + 1).toInt()}
        val multiplier = remember {if (isImperial) 0.3937f; else 1.0f}  // convert inches to cm if necessary
        val initiallySelectedOption = valueToIndex(initial, minHeight, step, numberOfOptions, multiplier)
        val listState = rememberScalingLazyListState()
        val state = rememberPickerState(initialNumberOfOptions = numberOfOptions, initiallySelectedOption = initiallySelectedOption, repeatItems = false)
        val contentDescription by remember { derivedStateOf { indexToDisplayValue(minHeight, step, unit, state.selectedOption) } }

        DisposableEffect(Unit) {
            onDispose {
                viewModel.height = indexToValue(state.selectedOption, minHeight, step, multiplier)    // causes viewModel.settings.collect to fire
            }
        }

        OnTrackTheme {
            Scaffold(
                positionIndicator = { PositionIndicator(scalingLazyListState = listState, modifier = Modifier) },
                vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) }
            ) {
                Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Heading("Height", 35.dp)
                    Picker(
                        state = state,
                        modifier = Modifier.height(100.dp),
                        contentDescription = contentDescription,
                        option = { optionIndex: Int -> Text(
                            text = indexToDisplayValue(minHeight, step, unit, optionIndex),
                            fontSize = 30.sp
                        ) }
                    )
                }
            }
        }
    }

    @Composable
    fun BodyWeightSetting(initial: Float?, isImperial: Boolean) {
        val minWeight = remember {if (isImperial) 85 else 40}
        val maxWeight = remember {if (isImperial) 270 else 125}
        val imperialIndex = if (isImperial) 1 else 0
        val step = remember {weightSteps[imperialIndex]}
        val unit = remember {weightUnits[imperialIndex]}
        val multiplier = remember {if (isImperial) 2.204623f; else 1.0f}
        val numberOfOptions = round((maxWeight - minWeight) / step + 1).toInt()
        val initiallySelectedOption = valueToIndex(initial, minWeight, step, numberOfOptions, multiplier)
        val listState = rememberScalingLazyListState()
        val state = rememberPickerState(initialNumberOfOptions = numberOfOptions, initiallySelectedOption = initiallySelectedOption, repeatItems = false)
        val contentDescription by remember { derivedStateOf { indexToDisplayValue(minWeight, step, unit, state.selectedOption) } }

        DisposableEffect(Unit) {
            onDispose {
                viewModel.weight = indexToValue(state.selectedOption, minWeight, step, multiplier)    // causes viewModel.settings.collect to fire
            }
        }

        OnTrackTheme {
            Scaffold(
                positionIndicator = { PositionIndicator(scalingLazyListState = listState, modifier = Modifier) },
                vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) }
            ) {
                Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Heading("Weight", 35.dp)
                    Picker(
                        state = state,
                        modifier = Modifier.height(100.dp),
                        contentDescription = contentDescription,
                        option = { optionIndex: Int -> Text(text = indexToDisplayValue(minWeight, step, unit, optionIndex), fontSize = 30.sp) }
                    )
                }
            }
        }
    }

    private fun valueToIndex(value: Float?, minValue: Int, step: Float, numberOfOptions: Int, multiplier: Float): Int {
        return if (value == null) numberOfOptions / 2; else ((value * multiplier - minValue) / step).roundToInt()
    }

    private fun indexToValue(index: Int, minValue: Int, step: Float, multiplier: Float=1f): Float {
        return (index * step + minValue) / multiplier
    }

    private fun indexToDisplayValue(minValue: Int, step: Float, unit: String, index: Int): String {
        return "${indexToValue(index, minValue, step)} $unit"
    }

    @Composable
    fun PeriodSettingsList(onNavToStart: () -> Unit, onNavToEnd: () -> Unit) {
        val listState = rememberScalingLazyListState()
        OnTrackTheme {
            Scaffold(
                positionIndicator = { PositionIndicator(scalingLazyListState = listState, modifier = Modifier) },
                vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) }
            ) {
                ScalingLazyColumn(state = listState) {
                    item { Heading("Active Period", 40.dp) }
                    item { Chip(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onNavToStart,
                        label = {Text("Start Time")},
                        secondaryLabel = {Text(periodSecondsToTime(viewModel.actStart, 5))},
                        icon = {Icon(painter = painterResource(id = R.drawable.start), contentDescription = "Start")},
                        colors = ChipDefaults.secondaryChipColors()
                    )}
                    item { Chip(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onNavToEnd,
                        label = {Text("End Time")},
                        secondaryLabel = {Text(periodSecondsToTime(viewModel.actEnd, 17))},
                        icon = {Icon(painter = painterResource(id = R.drawable.end), contentDescription = "End")},
                        colors = ChipDefaults.secondaryChipColors()
                    )}
                }
            }
        }
    }

    @Composable
    fun PeriodStartSetting(initial: Int) {
        // initial: seconds into non-DST day.
        val baseHour = 5
        val state = rememberPickerState(
            initialNumberOfOptions = 29,
            initiallySelectedOption = periodSecondsToIndex(initial, baseHour),
            repeatItems = false
        )
        val contentDescription by remember { derivedStateOf { periodIndexToTime(baseHour, state.selectedOption) } }
        val listState = rememberScalingLazyListState()

        DisposableEffect(Unit) {
            onDispose {
                viewModel.actStart = periodIndexToSeconds(state.selectedOption, baseHour)    // causes viewModel.settings.collect to fire
            }
        }

        OnTrackTheme {
            Scaffold(
                positionIndicator = { PositionIndicator(scalingLazyListState = listState, modifier = Modifier) },
                vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) }
            ) {
                Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Heading("Active Period Start", 35.dp)
                    Picker(
                        state = state,
                        modifier = Modifier.height(100.dp),
                        contentDescription = contentDescription,
                        option = { optionIndex: Int -> Text(text = periodIndexToTime(baseHour, optionIndex), fontSize = 30.sp) }
                    )
                }
            }
        }
    }

    @Composable
    fun PeriodEndSetting(initial: Int) {
        // initial: seconds into non-DST day.
        val baseHour = 17
        val state = rememberPickerState(
            initialNumberOfOptions = 29,
            initiallySelectedOption = periodSecondsToIndex(initial, baseHour),
            repeatItems = false
        )
        val contentDescription by remember { derivedStateOf { periodIndexToTime(baseHour, state.selectedOption) } }
        val listState = rememberScalingLazyListState()

        DisposableEffect(Unit) {
            onDispose {
                viewModel.actEnd = periodIndexToSeconds(state.selectedOption, baseHour)    // causes viewModel.settings.collect to fire
            }
        }

        OnTrackTheme {
            Scaffold(
                positionIndicator = { PositionIndicator(scalingLazyListState = listState, modifier = Modifier) },
                vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) }
            ) {
                Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Heading("Active Period End", 35.dp)
                    Picker(
                        state = state,
                        modifier = Modifier.height(100.dp),
                        contentDescription = contentDescription,
                        option = { optionIndex: Int -> Text(text = periodIndexToTime(baseHour, optionIndex), fontSize = 30.sp) }
                    )
                }
            }
        }
    }

    private fun periodSecondsToIndex(seconds: Int, baseHour: Int) : Int {
        return seconds / 900 - baseHour * 4
    }

    private fun periodIndexToSeconds(index: Int, baseHour: Int) : Int {
        return index * 900 + baseHour * 3600
    }

    private fun periodIndexToTime(baseHour: Int, index: Int): String {
        val initialTime = LocalTime.of(baseHour,0)
        val selectedTime = initialTime.plusMinutes((index * 15).toLong())
        return selectedTime.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
    }

    private fun periodSecondsToTime(seconds: Int, baseHour: Int): String {
        return periodIndexToTime(baseHour, periodSecondsToIndex(seconds, baseHour))
    }

    @Composable
    fun RangesSettingsList(onNavToEnergyRange: () -> Unit, onNavToOtherRange: () -> Unit) {
        val listState = rememberScalingLazyListState()
        OnTrackTheme {
            Scaffold(
                positionIndicator = { PositionIndicator(scalingLazyListState = listState, modifier = Modifier) },
                vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) }
            ) {
                ScalingLazyColumn(state = listState) {
                    item { Heading("Gauge Ranges", 40.dp) }
                    item { Chip(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onNavToEnergyRange,
                        label = {Text("Energy")},
                        secondaryLabel = {Text(gaugeRangeText(viewModel.rangeEnergy))},
                        icon = {Icon(painter = painterResource(id = R.drawable.energy), contentDescription = "Energy")},
                        colors = ChipDefaults.secondaryChipColors()
                    )}
                    item { Chip(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onNavToOtherRange,
                        label = {Text("Others")},
                        secondaryLabel = {Text(gaugeRangeText(viewModel.rangeOther))},
                        icon = {Icon(painter = painterResource(id = R.drawable.other), contentDescription = "Others")},
                        colors = ChipDefaults.secondaryChipColors()
                    )}
                }
            }
        }
    }

    @Composable
    fun RangeEnergySetting(initial: Int) {
        val state = rememberPickerState(gaugeRanges.size, initiallySelectedOption = initial, repeatItems = false)
        val contentDescription by remember { derivedStateOf { gaugeRangeText(state.selectedOption) } }
        val listState = rememberScalingLazyListState()

        DisposableEffect(Unit) {
            onDispose {
                viewModel.rangeEnergy = state.selectedOption    // causes viewModel.settings.collect to fire
            }
        }

        OnTrackTheme {
            Scaffold(
                positionIndicator = { PositionIndicator(scalingLazyListState = listState, modifier = Modifier) },
                vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) }
            ) {
                Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Heading("Energy Gauge Range", 40.dp)
                    Picker(
                        state = state,
                        modifier = Modifier.height(100.dp),
                        contentDescription = contentDescription,
                    ) { Text(gaugeRangeText(it), fontSize = 30.sp) }
                }
            }
        }
    }

    private fun gaugeRangeText(index: Int): String {
        return "±${gaugeRanges[index]}%"
    }

    @Composable
    fun RangeOtherSetting(initial: Int) {
        val state = rememberPickerState(gaugeRanges.size, initiallySelectedOption = initial, repeatItems = false)
        val contentDescription by remember { derivedStateOf { gaugeRangeText(state.selectedOption) } }
        val listState = rememberScalingLazyListState()

        DisposableEffect(Unit) {
            onDispose {
                viewModel.rangeOther = state.selectedOption    // causes viewModel.settings.collect to fire
            }
        }

        OnTrackTheme {
            Scaffold(
                positionIndicator = { PositionIndicator(scalingLazyListState = listState, modifier = Modifier) },
                vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) }
            ) {
                Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Heading("Other Gauge Range", 40.dp)
                    Picker(
                        state = state,
                        modifier = Modifier.height(100.dp),
                        contentDescription = contentDescription,
                    ) { Text(gaugeRangeText(it), fontSize = 30.sp) }
                }
            }
        }
    }

    @Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true)
    @Composable
    fun DefaultPreview() {
        MetricCardList()
    }
}
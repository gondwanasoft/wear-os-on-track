package au.gondwanasoftware.ontrack.complication

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.health.services.client.HealthServices
import androidx.health.services.client.PassiveListenerCallback
import androidx.health.services.client.PassiveMonitoringClient
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DeltaDataType
import androidx.health.services.client.data.IntervalDataPoint
import androidx.health.services.client.data.PassiveListenerConfig
import au.gondwanasoftware.ontrack.presentation.tag
import java.time.Instant

val bootInstant: Instant = Instant.ofEpochMilli(System.currentTimeMillis() - SystemClock.elapsedRealtime())

enum class MetricReaderResponseType {
    NONE,       // timeout or no reading; don't changed complic display
    OK,         // normal reading; dataPoint is valid
    E_PERM      // error: no permission; complic should display 'SEE APP'
}

data class MetricRequestResponse (
    val type: MetricReaderResponseType,
    val dataPoint: IntervalDataPoint<Any>? = null
)

class MetricReaderRequest(  // a request for a reading of a specific metric type
    val context: Context,
    val dataType: DeltaDataType<Any, IntervalDataPoint<Any>>,
    val onMetricDataReceived: (MetricRequestResponse) -> Unit
) {
    private var isCancelled = false

    private val timeoutMonitor: Runnable = Runnable {
        Log.w(tag, "MetricReaderRequest '${dataType.name}' timeoutMonitor run()")
        onMetricDataReceived(MetricRequestResponse(MetricReaderResponseType.NONE))
        cancel()
    }

    fun process() {
        // Process this reading request; ie, try to get a metric reading.
        //Log.i(tag, "MetricReaderRequest '${dataType.name}' process() isCancelled=$isCancelled")

        if (isCancelled) {
            MetricReader.isProcessing = false
            MetricReader.processQueue(context)  // process next request in queue, if any
            return
        }

        val passiveListenerConfig = PassiveListenerConfig.builder().setDataTypes(setOf(dataType)).build()

        val passiveListenerCallback: PassiveListenerCallback = object : PassiveListenerCallback {
            fun respond(type: MetricReaderResponseType, dataPoint: IntervalDataPoint<Any>? = null) {
                stopListening()
                onMetricDataReceived(MetricRequestResponse(type, dataPoint))
                MetricReader.isProcessing = false
                MetricReader.processQueue(context)  // process next request in queue, if any
            }

            override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
                //Log.i(tag, "MetricReaderRequest '${dataType.name}' onNewDataPointsReceived()")
                //stopListening()       // comment out for testing ongoing request
                val metricDataPoints = dataPoints.getData(dataType)
                if (metricDataPoints.isEmpty()) return
                var mostRecentDataPoint = metricDataPoints[0]
                metricDataPoints.forEach {
                    if (it.endDurationFromBoot >= mostRecentDataPoint.endDurationFromBoot) mostRecentDataPoint = it
                    //Log.i(tag, "ComplicationService ${metric.name} endDur=${mostRecentDataPoint.endDurationFromBoot} value=${it.value}")
                }
                //return  // for testing ongoing request
                respond(MetricReaderResponseType.OK, mostRecentDataPoint)
            }

            override fun onRegistrationFailed(throwable: Throwable) {
                Log.w(tag, "MetricReaderRequest '${dataType.name}' onRegistrationFailed(): no permission?")
                super.onRegistrationFailed(throwable)
                respond(MetricReaderResponseType.E_PERM)
            }
        }

        MetricReader.handler.postDelayed(timeoutMonitor, 5000)   // or use CountDownTimer
        MetricReader.passiveMonitoringClient.setPassiveListenerCallback(passiveListenerConfig, passiveListenerCallback)
        // Seems unnec to restore registrations after boot coz setPassiveListenerCallback() will be called for every MetricReaderRequest.process().
        // ^ https://developer.android.com/health-and-fitness/guides/health-services/monitor-background#restore-reg
        MetricReader.isPassiveMonitoringClientListening = true
    }

    fun cancel() {  // called when complic is deactivated and when timeoutMonitor expires
        //Log.i(tag, "MetricReaderRequest '${dataType.name}' cancel()")
        isCancelled = true
        stopListening()
        MetricReader.isProcessing = false
        MetricReader.processQueue(context)  // process next request in queue, if any
    }

    private fun stopListening() {
        //Log.i(tag, "MetricReaderRequest '${dataType.name}' stopListening() isPassiveMonitoringClientListening=${MetricReader.isPassiveMonitoringClientListening}")
        MetricReader.handler.removeCallbacks(timeoutMonitor)
        if (MetricReader.isPassiveMonitoringClientListening) {  // calling clearPassiveListenerCallbackAsync() when there wasn't a listener ends the process
            MetricReader.passiveMonitoringClient.clearPassiveListenerCallbackAsync()     // remove listener (asynchronously!!)
            MetricReader.isPassiveMonitoringClientListening = false
        } else Log.e(tag, "MetricReader stopListening() called when isPassiveMonitoringClientListening==false")
    }
}

object MetricReader {   // manages a queue of MetricReaderRequests
    private val requestQueue: ArrayDeque<MetricReaderRequest> = ArrayDeque(1)
    lateinit var passiveMonitoringClient: PassiveMonitoringClient   // initialised in processQueue()
    val handler = Handler(Looper.getMainLooper())
    var isProcessing = false                        // a request is currently being processed
    var isPassiveMonitoringClientListening = false  // listening for metric updates

    fun request(
        context: Context,
        dataType: DeltaDataType<Any, IntervalDataPoint<Any>>,
        onMetricDataReceived: (MetricRequestResponse)->Unit
    ): MetricReaderRequest {
        // Create a new MetricReaderRequest, queue it for processing, and pump the queue.
        // Returns the new MetricReaderRequest.
        // newRequest might already have been processed by the time caller gets the return value,
        // in which case onMetricDataReceived() will have nulled the value.
        // Caller only uses it in onComplicationDeactivated().
        val newRequest = MetricReaderRequest(context, dataType, onMetricDataReceived)
        requestQueue.addLast(newRequest)
        if (!isProcessing) processQueue(context)
        return newRequest
    }

    fun processQueue(context: Context) {    // process a single entry from the queue (if not empty)
        //Log.i(tag, "MetricReader processQueue() len=${requestQueue.size}")
        if (isProcessing) Log.e(tag, "MetricReader processQueue() isProcessing==true")
        if (requestQueue.isEmpty() || isProcessing) return  // check for isProcessing shouldn't be nec, but just in case

        if (!::passiveMonitoringClient.isInitialized) {
            //Log.i(tag, "MetricReader processQueue() passiveMonitoringClient not initialised: reinitialising")
            passiveMonitoringClient = HealthServices.getClient(context).passiveMonitoringClient
        }

        isProcessing = true
        val firstRequest = requestQueue.removeFirst()
        firstRequest.process()
    }

    /*fun test() {    // del testing
        request<Any>(DataType.STEPS_DAILY as DeltaDataType<Any, IntervalDataPoint<Any>>, onMetricDataReceived)
        request<Any>(DataType.DISTANCE_DAILY as DeltaDataType<Any, IntervalDataPoint<Any>>, onMetricDataReceived)
    }*/
}
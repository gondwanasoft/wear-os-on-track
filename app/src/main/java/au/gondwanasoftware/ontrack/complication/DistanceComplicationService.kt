package au.gondwanasoftware.ontrack.complication

import androidx.health.services.client.data.DataType
import au.gondwanasoftware.ontrack.Metric
import au.gondwanasoftware.ontrack.R

private object DistanceComplicationServiceData {
    /*init {
        //Log.i(tagSettings, "DistanceComplicationServiceData init()")
    }*/

    val metric: Metric = Metric(2, "Distance", R.drawable.distance, precision = 2)
}

class DistanceComplicationService : ComplicationService<Double>(DistanceComplicationServiceData.metric, DataType.DISTANCE_DAILY, R.drawable.distance) {
    /*init {
        //Log.i(tag, "DistanceComplicationService init icon=${R.drawable.distance}")
    }*/

    override fun dataPointToFloat(reading: Double): Float {
        //Log.i(tag, "DistanceComplicationService dataPointToFloat($reading)")
        return reading.toFloat()
    }
}
package au.gondwanasoftware.ontrack.complication

import androidx.health.services.client.data.DataType
import au.gondwanasoftware.ontrack.Metric
import au.gondwanasoftware.ontrack.R

private object FloorsComplicationServiceData {
    /*init {
        //Log.i(tagSettings, "stepsComplicationServiceData init()")
    }*/

    val metric: Metric = Metric(3, "Floors", R.drawable.floors, precision = 1)
}

class FloorsComplicationService : ComplicationService<Double>(FloorsComplicationServiceData.metric, DataType.FLOORS_DAILY, R.drawable.floors) {
    /*init {
        //Log.i(tag, "FloorsComplicationService init icon=${R.drawable.floors}")
    }*/

    override fun dataPointToFloat(reading: Double): Float {
        //Log.i(tag, "FloorsComplicationService dataPointToFloat($reading)")
        return reading.toFloat()
    }
}
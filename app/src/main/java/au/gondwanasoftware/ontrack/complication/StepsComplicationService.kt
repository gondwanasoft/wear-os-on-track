package au.gondwanasoftware.ontrack.complication

import androidx.health.services.client.data.DataType
import au.gondwanasoftware.ontrack.Metric
import au.gondwanasoftware.ontrack.R

private object StepsComplicationServiceData {
    /*init {
        //Log.i(tagSettings, "stepsComplicationServiceData init()")
    }*/

    val metric: Metric = Metric(1, "Steps", R.drawable.steps, precision = 0)
}

class StepsComplicationService : ComplicationService<Long>(StepsComplicationServiceData.metric, DataType.STEPS_DAILY, R.drawable.steps) {
    /*init {
        //Log.i(tagSettings, "StepsComplicationService init icon=${R.drawable.steps}")
    }*/

    override fun dataPointToFloat(reading: Long): Float {
        //Log.i(tag, "StepsComplicationService dataPointToFloat($reading)")
        return reading.toFloat()
    }
}

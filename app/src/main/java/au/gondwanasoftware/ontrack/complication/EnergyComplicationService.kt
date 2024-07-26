package au.gondwanasoftware.ontrack.complication

import androidx.health.services.client.data.DataType
import au.gondwanasoftware.ontrack.Metric
import au.gondwanasoftware.ontrack.R

private object EnergyComplicationServiceData {
    /*init {
        //Log.i(tagSettings, "EnergyComplicationServiceData init()")
    }*/

    val metric: Metric = Metric(0, "Energy", R.drawable.energy, precision = 0)
}

class EnergyComplicationService : ComplicationService<Double>(EnergyComplicationServiceData.metric, DataType.CALORIES_DAILY, R.drawable.energy) {
    /*init {
        //Log.i(tag, "EnergyComplicationService init icon=${R.drawable.energy}")
    }*/

    override fun dataPointToFloat(reading: Double): Float {
        //Log.i(tag, "EnergyComplicationService dataPointToFloat($reading)")
        return reading.toFloat()
    }
}
// LUXURY 0 Energy complic may not be added on real watch initially. Race condition re DataStore?? Timeout??
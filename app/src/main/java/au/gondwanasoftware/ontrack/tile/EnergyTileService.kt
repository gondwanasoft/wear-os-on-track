package au.gondwanasoftware.ontrack.tile

import androidx.health.services.client.data.DataType
import au.gondwanasoftware.ontrack.Metric
import au.gondwanasoftware.ontrack.MetricReading
import au.gondwanasoftware.ontrack.R

private object EnergyTileServiceData : TileServiceData {
    override val metric = Metric(0, "Energy", R.drawable.energy, precision = 0)
    override var isListening = false
    override var lastReading: MetricReading? = null
}

class EnergyTileService : OnTrackTileService<Double>(EnergyTileServiceData, DataType.CALORIES_DAILY)
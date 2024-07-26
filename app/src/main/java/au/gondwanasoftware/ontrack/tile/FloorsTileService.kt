package au.gondwanasoftware.ontrack.tile

import androidx.health.services.client.data.DataType
import au.gondwanasoftware.ontrack.Metric
import au.gondwanasoftware.ontrack.MetricReading
import au.gondwanasoftware.ontrack.R

private object FloorsTileServiceData : TileServiceData {
    override val metric = Metric(3, "Floors", R.drawable.floors, precision = 1)
    override var isListening = false
    override var lastReading: MetricReading? = null
}

class FloorsTileService : OnTrackTileService<Double>(FloorsTileServiceData, DataType.FLOORS_DAILY)
package au.gondwanasoftware.ontrack.tile

import androidx.health.services.client.data.DataType
import au.gondwanasoftware.ontrack.Metric
import au.gondwanasoftware.ontrack.MetricReading
import au.gondwanasoftware.ontrack.R

private object DistanceTileServiceData : TileServiceData {
    override val metric = Metric(2, "Distance", R.drawable.distance, precision = 2)
    override var isListening = false
    override var lastReading: MetricReading? = null
}

class DistanceTileService : OnTrackTileService<Double>(DistanceTileServiceData, DataType.DISTANCE_DAILY)
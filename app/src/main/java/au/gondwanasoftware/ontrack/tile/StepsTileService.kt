package au.gondwanasoftware.ontrack.tile

import androidx.health.services.client.data.DataType
import au.gondwanasoftware.ontrack.Metric
import au.gondwanasoftware.ontrack.MetricReading
import au.gondwanasoftware.ontrack.R

private object StepsTileServiceData : TileServiceData {
    override val metric = Metric(1, "Steps", R.drawable.steps, precision = 0)
    override var isListening = false
    override var lastReading: MetricReading? = null
}

class StepsTileService : OnTrackTileService<Long>(StepsTileServiceData, DataType.STEPS_DAILY)
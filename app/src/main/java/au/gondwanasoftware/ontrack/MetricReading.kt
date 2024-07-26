package au.gondwanasoftware.ontrack

import java.time.Instant

data class MetricReading(var value: Float = 0f, var timestamp: Instant? = null)
package com.example.haptok.fallback

/**
 * Dynamic adaptive threshold for per-band audio energy analysis.
 *
 * Uses an exponential moving average (EMA) to track the "ambient" energy
 * level of each frequency band. A transient is detected when the
 * current energy exceeds `average × [multiplier]`.
 *
 * Separate attack / release rates allow fast reaction to sudden peaks
 * while slowly decaying during quiet passages, preventing both constant
 * buzzing in loud scenes and silence in quiet ones.
 *
 * @param multiplier How many times above the running average the energy
 *   must be to trigger a detection (default 2.0).
 * @param attackRate EMA coefficient when energy is ABOVE the average (0–1, higher = faster).
 * @param releaseRate EMA coefficient when energy is BELOW the average (0–1, lower = slower decay).
 */
class AdaptiveThreshold(
    private val multiplier: Float = 2.0f,
    private val attackRate: Float = 0.3f,
    private val releaseRate: Float = 0.05f,
) {

    /** Per-band running average, keyed by a caller-chosen band name. */
    private val averages = mutableMapOf<String, Float>()

    /**
     * Feed a new energy sample for [band] and return `true` if it
     * exceeds the adaptive threshold (i.e. a transient is detected).
     */
    fun isAboveThreshold(band: String, energy: Float): Boolean {
        val avg = averages[band]
        if (avg == null) {
            // Bootstrap: first sample becomes the average.
            averages[band] = energy
            return false
        }

        // Choose coefficient: fast attack, slow release.
        val alpha = if (energy > avg) attackRate else releaseRate
        val newAvg = avg + alpha * (energy - avg)
        averages[band] = newAvg

        return energy > newAvg * multiplier
    }

    /**
     * Returns the current adaptive threshold value for [band],
     * i.e. `average × multiplier`. Returns 0 if the band has no data yet.
     */
    fun getThreshold(band: String): Float {
        return (averages[band] ?: 0f) * multiplier
    }

    /**
     * Returns the current running average for [band].
     */
    fun getAverage(band: String): Float {
        return averages[band] ?: 0f
    }

    /** Reset all running averages. */
    fun reset() {
        averages.clear()
    }
}

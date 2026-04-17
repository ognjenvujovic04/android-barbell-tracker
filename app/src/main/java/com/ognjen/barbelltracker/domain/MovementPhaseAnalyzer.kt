package com.ognjen.barbelltracker.domain

import kotlin.math.abs
import kotlin.math.max

enum class PhaseDirection { UP, DOWN, STATIONARY }

/**
 * Phase segment along the bar path from vertical velocity ([BarVelocitySample.vyCmPerS]).
 * Frame: +y down; upward bar motion is negative vy (concentric for bench/squat when camera is upright).
 */
data class PhaseSegment(
    val startMs: Long,
    val endMs: Long,
    val direction: PhaseDirection,
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}

object MovementPhaseAnalyzer {

    /**
     * Builds contiguous UP / DOWN / STATIONARY segments from velocity samples.
     *
     * - Near-zero |vy| (below [deadband]) is labeled STATIONARY instead of inheriting the neighbour's
     *   direction, so brief stops and bbox jitter do not bleed into fake up/down motion.
     * - UP/DOWN runs shorter than [MIN_MOVING_SEGMENT_MS] are reclassified as STATIONARY (noise filter).
     * - Adjacent runs of the same direction are merged.
     */
    fun buildSegments(samples: List<BarVelocitySample>): List<PhaseSegment> {
        if (samples.isEmpty()) return emptyList()
        val sorted = samples.sortedBy { it.timestampMs }
        val maxAbsVy = sorted.maxOf { abs(it.vyCmPerS) }.coerceAtLeast(1e-6f)
        val deadband = max(MIN_DEADBAND_CM_PER_S, DEADBAND_FRACTION_OF_PEAK * maxAbsVy)

        val n = sorted.size
        val labels = Array(n) { i ->
            val vy = sorted[i].vyCmPerS
            when {
                vy < -deadband -> PhaseDirection.UP
                vy > deadband -> PhaseDirection.DOWN
                else -> PhaseDirection.STATIONARY
            }
        }

        val raw = mutableListOf<PhaseSegment>()
        var runStart = 0
        for (i in 1..n) {
            val endRun = i == n || labels[i] != labels[runStart]
            if (endRun) {
                raw.add(
                    PhaseSegment(
                        startMs = sorted[runStart].timestampMs,
                        endMs = sorted[i - 1].timestampMs,
                        direction = labels[runStart],
                    )
                )
                runStart = i
            }
        }

        val cleaned = raw.map { seg ->
            if (seg.direction != PhaseDirection.STATIONARY && seg.durationMs < MIN_MOVING_SEGMENT_MS) {
                seg.copy(direction = PhaseDirection.STATIONARY)
            } else seg
        }

        return mergeAdjacent(cleaned)
    }

    /**
     * Counts eccentric→concentric transitions. Stationary segments are skipped, so a DOWN...HOLD...UP
     * pattern still counts as one rep. Consecutive same-direction runs (from merges across a hold) are
     * collapsed to a single entry before counting.
     */
    fun estimateRepCount(segments: List<PhaseSegment>): Int {
        val sequence = mutableListOf<PhaseDirection>()
        for (s in segments) {
            if (s.direction == PhaseDirection.STATIONARY) continue
            if (sequence.isNotEmpty() && sequence.last() == s.direction) continue
            sequence.add(s.direction)
        }
        var reps = 0
        for (i in 0 until sequence.size - 1) {
            if (sequence[i] == PhaseDirection.DOWN && sequence[i + 1] == PhaseDirection.UP) reps++
        }
        return reps
    }

    fun totalPhaseDurationMs(segments: List<PhaseSegment>, direction: PhaseDirection): Long {
        var sum = 0L
        for (s in segments) if (s.direction == direction) sum += s.durationMs
        return sum
    }

    private fun mergeAdjacent(segments: List<PhaseSegment>): List<PhaseSegment> {
        if (segments.isEmpty()) return segments
        val out = mutableListOf<PhaseSegment>()
        var current = segments[0]
        for (i in 1 until segments.size) {
            val next = segments[i]
            current = if (next.direction == current.direction) {
                current.copy(endMs = next.endMs)
            } else {
                out.add(current)
                next
            }
        }
        out.add(current)
        return out
    }

    /** Hard floor for the deadband so low-speed videos still discriminate real motion from noise. */
    private const val MIN_DEADBAND_CM_PER_S = 10f

    /**
     * Fraction of peak |vy| counted as "stationary". 0.15 = anything under 15% of the fastest
     * vertical speed in the clip is a hold; tune up if reps are still over-counted.
     */
    private const val DEADBAND_FRACTION_OF_PEAK = 0.15f

    /** Moving segments (UP/DOWN) shorter than this are treated as noise and become STATIONARY. */
    private const val MIN_MOVING_SEGMENT_MS = 150L
}

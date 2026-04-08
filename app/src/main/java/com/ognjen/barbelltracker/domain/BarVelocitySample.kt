package com.ognjen.barbelltracker.domain

/**
 * Finite-difference velocity between two consecutive detections of the bar center.
 * Axes: +x right, +y down in frame space; [speedCmPerS] is path speed |dr/dt|.
 */
data class BarVelocitySample(
    val timestampMs: Long,
    val dtSeconds: Float,
    val vxCmPerS: Float,
    val vyCmPerS: Float,
    val speedCmPerS: Float,
    val segmentPathCm: Float,
)

package com.ognjen.barbelltracker.domain

data class BoundingBox(
    val x1: Float,  // Top-left x coordinate
    val y1: Float,  // Top-left y coordinate
    val x2: Float,  // Bottom-right x coordinate
    val y2: Float,  // Bottom-right y coordinate
    val cx: Float,  // Center x coordinate
    val cy: Float,  // Center y coordinate
    val w: Float,   // Width
    val h: Float,   // Height
    val cnf: Float,  // Confidence score
    val cls: Int,    // Class index
    val clsName: String,  // Class name
    val id: Int = -1  // Unique ID or -1 if not set
)
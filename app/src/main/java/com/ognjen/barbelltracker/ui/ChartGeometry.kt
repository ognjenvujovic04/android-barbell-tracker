package com.ognjen.barbelltracker.ui

import android.content.res.Resources

/** Shared horizontal inset for speed chart and phase strip so time maps to the same x range. */
internal object ChartGeometry {
    fun padLeftPx(resources: Resources): Float = 42f * resources.displayMetrics.density
    fun padRightPx(resources: Resources): Float = 12f * resources.displayMetrics.density
    fun padTopPx(resources: Resources): Float = 14f * resources.displayMetrics.density
    fun padBottomPx(resources: Resources): Float = 28f * resources.displayMetrics.density
}

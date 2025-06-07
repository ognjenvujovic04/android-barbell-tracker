package com.ognjen.barbelltracker.FastVideoFrameExtractor

/**
 * Created by Duc Ky Ngo on 9/13/2021.
 * duckyngo1705@gmail.com
 */
interface IVideoFrameExtractor {
    fun onCurrentFrameExtracted(currentFrame: Frame, presentationTimeUs: Long)
    fun onAllFrameExtracted(processedFrameCount: Int, processedTimeMs: Long)
}
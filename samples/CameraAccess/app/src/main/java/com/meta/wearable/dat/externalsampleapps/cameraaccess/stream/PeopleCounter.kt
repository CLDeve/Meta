package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import org.tensorflow.lite.support.image.TensorImage

internal class PeopleCounter(context: Context) {
  private val detector: ObjectDetector

  init {
    val baseOptions =
        BaseOptions.builder()
            .setNumThreads(2)
            .useNnapi()
            .build()

    val options =
        ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(baseOptions)
            .setMaxResults(15)
            .setScoreThreshold(0.35f)
            .build()

    detector = ObjectDetector.createFromFileAndOptions(context, MODEL_ASSET_NAME, options)
  }

  fun countPeopleInFront(bitmap: Bitmap): Int {
    if (bitmap.width <= 0 || bitmap.height <= 0) return 0
    val image = TensorImage.fromBitmap(bitmap)
    val results = detector.detect(image)
    if (results.isEmpty()) return 0

    val w = bitmap.width.toFloat()
    val h = bitmap.height.toFloat()
    val centerMinX = w * 0.25f
    val centerMaxX = w * 0.75f
    val centerMinY = h * 0.20f
    val centerMaxY = h * 0.90f

    var count = 0
    for (det in results) {
      val categories = det.categories
      val isPerson =
          categories.any { category ->
            val label = category.label?.lowercase().orEmpty()
            label == "person" || label.contains("person")
          }
      if (!isPerson) continue

      val box = det.boundingBox
      val cx = (box.left + box.right) / 2f
      val cy = (box.top + box.bottom) / 2f
      if (cx < centerMinX || cx > centerMaxX || cy < centerMinY || cy > centerMaxY) continue

      val area = (box.width() * box.height()).toFloat() / (w * h)
      if (area < 0.02f) continue

      count++
    }
    return count
  }

  private companion object {
    private const val MODEL_ASSET_NAME = "efficientdet-lite0.tflite"
  }
}


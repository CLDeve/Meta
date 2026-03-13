package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import org.tensorflow.lite.support.image.TensorImage

internal class PeopleCounter(context: Context) {
  data class PersonDetection(
      val boundingBox: RectF,
      val score: Float? = null,
  )

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

  fun detectPeopleInFront(bitmap: Bitmap): List<PersonDetection> {
    if (bitmap.width <= 0 || bitmap.height <= 0) return emptyList()
    val image = TensorImage.fromBitmap(bitmap)
    val results = detector.detect(image)
    if (results.isEmpty()) return emptyList()

    val w = bitmap.width.toFloat()
    val h = bitmap.height.toFloat()
    val centerMinX = w * 0.25f
    val centerMaxX = w * 0.75f
    val centerMinY = h * 0.20f
    val centerMaxY = h * 0.90f

    val detections = ArrayList<PersonDetection>(results.size)
    for (det in results) {
      val categories = det.categories
      var bestScore: Float? = null
      val isPerson =
          categories.any { category ->
            val label = category.label?.lowercase().orEmpty()
            val matches = label == "person" || label.contains("person")
            if (matches) {
              val score = category.score
              bestScore = if (bestScore == null) score else maxOf(bestScore ?: score, score)
            }
            matches
          }
      if (!isPerson) continue

      val box = det.boundingBox
      val cx = (box.left + box.right) / 2f
      val cy = (box.top + box.bottom) / 2f
      if (cx < centerMinX || cx > centerMaxX || cy < centerMinY || cy > centerMaxY) continue

      val area = (box.width() * box.height()).toFloat() / (w * h)
      if (area < 0.02f) continue

      detections.add(PersonDetection(RectF(box), bestScore))
    }
    return detections
  }

  fun countPeopleInFront(bitmap: Bitmap): Int = detectPeopleInFront(bitmap).size

  private companion object {
    private const val MODEL_ASSET_NAME = "efficientdet-lite0.tflite"
  }
}

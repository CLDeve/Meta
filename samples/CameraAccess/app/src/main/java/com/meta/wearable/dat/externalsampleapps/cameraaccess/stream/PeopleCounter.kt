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

  fun detectPeople(bitmap: Bitmap): List<PersonDetection> {
    if (bitmap.width <= 0 || bitmap.height <= 0) return emptyList()
    val image = TensorImage.fromBitmap(bitmap)
    val results = detector.detect(image)
    if (results.isEmpty()) return emptyList()

    val detections = ArrayList<PersonDetection>(results.size)
    for (det in results) {
      val categories = det.categories
      var bestScore: Float? = null
      val isPerson =
          categories.any { category ->
            val label = category.label?.lowercase().orEmpty()
            // Some models ship without labels; for COCO models, class index 0 is commonly "person".
            val matches = label == "person" || label.contains("person") || (label.isBlank() && category.index == 0)
            if (matches) {
              val score = category.score
              bestScore = if (bestScore == null) score else maxOf(bestScore ?: score, score)
            }
            matches
          }
      if (!isPerson) continue

      detections.add(PersonDetection(RectF(det.boundingBox), bestScore))
    }
    return detections
  }

  fun detectPeopleInFront(bitmap: Bitmap): List<PersonDetection> {
    val people = detectPeople(bitmap)
    if (people.isEmpty()) return emptyList()

    val w = bitmap.width.toFloat()
    val h = bitmap.height.toFloat()
    val centerMinX = w * 0.25f
    val centerMaxX = w * 0.75f
    val centerMinY = h * 0.20f
    val centerMaxY = h * 0.90f

    return people.filter { det ->
      val box = det.boundingBox
      val cx = (box.left + box.right) / 2f
      val cy = (box.top + box.bottom) / 2f
      if (cx < centerMinX || cx > centerMaxX || cy < centerMinY || cy > centerMaxY) return@filter false

      val area = (box.width() * box.height()) / (w * h)
      area >= 0.015f
    }
  }

  fun countPeopleInFront(bitmap: Bitmap): Int = detectPeopleInFront(bitmap).size

  private companion object {
    private const val MODEL_ASSET_NAME = "efficientdet-lite0.tflite"
  }
}

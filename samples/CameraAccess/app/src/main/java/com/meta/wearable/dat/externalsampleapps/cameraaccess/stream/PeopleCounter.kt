package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import org.tensorflow.lite.support.image.TensorImage

internal class PeopleCounter(context: Context) {
  data class ObjectDetection(
      val boundingBox: RectF,
      val label: String? = null,
      val index: Int? = null,
      val score: Float? = null,
  )

  data class PersonDetection(
      val boundingBox: RectF,
      val score: Float? = null,
  )

  val preferredInputMaxDimPx: Int

  private val yolo: YoloTfliteDetector?
  private val detector: ObjectDetector?

  init {
    yolo =
        runCatching {
              YoloTfliteDetector(
                  context = context,
                  assetName = YOLO_MODEL_ASSET_NAME,
              )
            }
            .getOrNull()
    if (yolo != null) {
      Log.i(TAG, "YOLO enabled: model=$YOLO_MODEL_ASSET_NAME input=${yolo.inputWidth}x${yolo.inputHeight} backend=${yoloBackendName()}")
    } else {
      Log.i(TAG, "YOLO not available; falling back to EfficientDet: model=$EFFICIENTDET_MODEL_ASSET_NAME")
    }

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

    detector =
        if (yolo == null) {
          ObjectDetector.createFromFileAndOptions(context, EFFICIENTDET_MODEL_ASSET_NAME, options)
        } else {
          null
        }

    preferredInputMaxDimPx = (yolo?.preferredInputMaxDimPx ?: 320).coerceAtLeast(1)
  }

  fun detectObjects(bitmap: Bitmap): List<ObjectDetection> {
    if (bitmap.width <= 0 || bitmap.height <= 0) return emptyList()
    val yoloDetector = yolo
    if (yoloDetector != null) {
      val detections = yoloDetector.detect(bitmap)
      if (detections.isEmpty()) return emptyList()
      val out = ArrayList<ObjectDetection>(detections.size)
      for (det in detections) {
        val label = COCO_LABELS.getOrNull(det.classIndex)
        out.add(
            ObjectDetection(
                boundingBox =
                    RectF(
                        det.left * bitmap.width.toFloat(),
                        det.top * bitmap.height.toFloat(),
                        det.right * bitmap.width.toFloat(),
                        det.bottom * bitmap.height.toFloat(),
                    ),
                label = label ?: "cls${det.classIndex}",
                index = det.classIndex,
                score = det.score,
            )
        )
      }
      return out
    }

    val eff = detector ?: return emptyList()
    val image = TensorImage.fromBitmap(bitmap)
    val results = eff.detect(image)
    if (results.isEmpty()) return emptyList()

    val out = ArrayList<ObjectDetection>(results.size)
    for (det in results) {
      val bestCategory = det.categories.maxByOrNull { it.score }
      out.add(
          ObjectDetection(
              boundingBox = RectF(det.boundingBox),
              label = bestCategory?.label,
              index = bestCategory?.index,
              score = bestCategory?.score,
          )
      )
    }
    return out
  }

  fun detectPeople(bitmap: Bitmap): List<PersonDetection> {
    val detections = detectObjects(bitmap)
    if (detections.isEmpty()) return emptyList()

    val people = ArrayList<PersonDetection>(detections.size)
    for (det in detections) {
      val label = det.label?.lowercase().orEmpty()
      // Some models ship without labels; for COCO models, class index 0 is commonly "person".
      val isPerson = label == "person" || label.contains("person") || (label.isBlank() && det.index == 0)
      if (!isPerson) continue
      people.add(PersonDetection(RectF(det.boundingBox), det.score))
    }
    return people
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
    private const val TAG = "PeopleCounter"
    private const val YOLO_MODEL_ASSET_NAME = "yolo11n_float16.tflite"
    private const val EFFICIENTDET_MODEL_ASSET_NAME = "efficientdet-lite0.tflite"

    // Standard COCO 80 classes (Ultralytics defaults).
    // Used when the model doesn't carry label metadata.
    private val COCO_LABELS =
        listOf(
            "person",
            "bicycle",
            "car",
            "motorcycle",
            "airplane",
            "bus",
            "train",
            "truck",
            "boat",
            "traffic light",
            "fire hydrant",
            "stop sign",
            "parking meter",
            "bench",
            "bird",
            "cat",
            "dog",
            "horse",
            "sheep",
            "cow",
            "elephant",
            "bear",
            "zebra",
            "giraffe",
            "backpack",
            "umbrella",
            "handbag",
            "tie",
            "suitcase",
            "frisbee",
            "skis",
            "snowboard",
            "sports ball",
            "kite",
            "baseball bat",
            "baseball glove",
            "skateboard",
            "surfboard",
            "tennis racket",
            "bottle",
            "wine glass",
            "cup",
            "fork",
            "knife",
            "spoon",
            "bowl",
            "banana",
            "apple",
            "sandwich",
            "orange",
            "broccoli",
            "carrot",
            "hot dog",
            "pizza",
            "donut",
            "cake",
            "chair",
            "couch",
            "potted plant",
            "bed",
            "dining table",
            "toilet",
            "tv",
            "laptop",
            "mouse",
            "remote",
            "keyboard",
            "cell phone",
            "microwave",
            "oven",
            "toaster",
            "sink",
            "refrigerator",
            "book",
            "clock",
            "vase",
            "scissors",
            "teddy bear",
            "hair drier",
            "toothbrush",
        )

    private fun yoloBackendName(): String = "tflite-interpreter"
  }
}

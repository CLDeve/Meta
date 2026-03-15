package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min

internal class YoloTfliteDetector(
    context: Context,
    assetName: String,
    private val scoreThreshold: Float = 0.35f,
    private val iouThreshold: Float = 0.45f,
    private val maxResults: Int = 15,
) {
  data class Detection(
      val left: Float,
      val top: Float,
      val right: Float,
      val bottom: Float,
      val classIndex: Int,
      val score: Float,
  )

  val inputWidth: Int
  val inputHeight: Int
  val preferredInputMaxDimPx: Int

  private val interpreter: Interpreter
  private val inputType: DataType
  private val inputIsNhwc: Boolean
  private val outputType: DataType
  private val outputShape: IntArray
  private val outputElementCount: Int

  private var inputBitmap: Bitmap? = null
  private var inputCanvas: Canvas? = null
  private val srcRect = Rect()
  private val dstRect = Rect()

  init {
    interpreter =
        Interpreter(
            loadModel(context, assetName),
            Interpreter.Options().apply {
              // NNAPI can help on many devices; if unsupported it will fall back.
              setUseNNAPI(true)
              setNumThreads(4)
            },
        )

    val in0 = interpreter.getInputTensor(0)
    val inShape = in0.shape()
    inputType = in0.dataType()

    // Most TFLite vision models are NHWC [1, H, W, 3].
    inputIsNhwc = (inShape.size == 4 && inShape[0] == 1 && inShape[3] == 3)
    if (inputIsNhwc) {
      inputHeight = inShape[1]
      inputWidth = inShape[2]
    } else if (inShape.size == 4 && inShape[0] == 1 && inShape[1] == 3) {
      // NCHW [1, 3, H, W] (rare on Android, but handle it).
      inputHeight = inShape[2]
      inputWidth = inShape[3]
    } else {
      throw IllegalStateException("Unsupported YOLO input tensor shape: ${inShape.joinToString()}")
    }
    preferredInputMaxDimPx = max(inputWidth, inputHeight).coerceAtLeast(1)

    val out0 = interpreter.getOutputTensor(0)
    outputType = out0.dataType()
    outputShape = out0.shape()
    outputElementCount = outputShape.fold(1) { acc, v -> acc * v.coerceAtLeast(1) }
  }

  fun detect(bitmap: Bitmap): List<Detection> {
    if (bitmap.width <= 0 || bitmap.height <= 0) return emptyList()

    val resized = ensureInputBitmap()
    val canvas = inputCanvas ?: Canvas(resized).also { inputCanvas = it }
    srcRect.set(0, 0, bitmap.width, bitmap.height)
    dstRect.set(0, 0, inputWidth, inputHeight)
    canvas.drawBitmap(bitmap, srcRect, dstRect, null)

    val input = makeInputBuffer(resized)
    val out = ByteBuffer.allocateDirect(outputElementCount * bytesPerElement(outputType)).order(ByteOrder.nativeOrder())
    interpreter.run(input, out)
    out.rewind()

    val detections = decodeOutput(out)
    if (detections.isEmpty()) return emptyList()
    return nms(detections)
  }

  private fun ensureInputBitmap(): Bitmap {
    val existing = inputBitmap
    if (existing != null && existing.width == inputWidth && existing.height == inputHeight) return existing
    existing?.recycle()
    return Bitmap.createBitmap(inputWidth, inputHeight, Bitmap.Config.ARGB_8888).also { inputBitmap = it }
  }

  private fun makeInputBuffer(bitmap: Bitmap): ByteBuffer {
    val bytesPerElement = bytesPerElement(inputType)
    val buffer =
        ByteBuffer.allocateDirect(1 * inputWidth * inputHeight * 3 * bytesPerElement).order(ByteOrder.nativeOrder())

    val pixels = IntArray(inputWidth * inputHeight)
    bitmap.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)

    if (inputType == DataType.UINT8) {
      if (inputIsNhwc) {
        for (p in pixels) {
          buffer.put(((p shr 16) and 0xFF).toByte())
          buffer.put(((p shr 8) and 0xFF).toByte())
          buffer.put((p and 0xFF).toByte())
        }
      } else {
        // NCHW uint8
        val planeSize = inputWidth * inputHeight
        val r = ByteArray(planeSize)
        val g = ByteArray(planeSize)
        val b = ByteArray(planeSize)
        for (i in pixels.indices) {
          val p = pixels[i]
          r[i] = ((p shr 16) and 0xFF).toByte()
          g[i] = ((p shr 8) and 0xFF).toByte()
          b[i] = (p and 0xFF).toByte()
        }
        buffer.put(r)
        buffer.put(g)
        buffer.put(b)
      }
      buffer.rewind()
      return buffer
    }

    // Float inputs: normalize to 0..1 (common for Ultralytics exports).
    if (inputIsNhwc) {
      for (p in pixels) {
        buffer.putFloat(((p shr 16) and 0xFF) / 255f)
        buffer.putFloat(((p shr 8) and 0xFF) / 255f)
        buffer.putFloat((p and 0xFF) / 255f)
      }
    } else {
      // NCHW float
      val planeSize = inputWidth * inputHeight
      val rf = FloatArray(planeSize)
      val gf = FloatArray(planeSize)
      val bf = FloatArray(planeSize)
      for (i in pixels.indices) {
        val p = pixels[i]
        rf[i] = ((p shr 16) and 0xFF) / 255f
        gf[i] = ((p shr 8) and 0xFF) / 255f
        bf[i] = (p and 0xFF) / 255f
      }
      for (v in rf) buffer.putFloat(v)
      for (v in gf) buffer.putFloat(v)
      for (v in bf) buffer.putFloat(v)
    }

    buffer.rewind()
    return buffer
  }

  private fun decodeOutput(out: ByteBuffer): List<Detection> {
    val shape = outputShape
    if (shape.isEmpty()) return emptyList()

    // Supported common cases:
    // - [1, N, D] where D is 4 + classes or 5 + classes
    // - [1, D, N] same but transposed
    // - [N, D]
    val (nBoxes, stride, transposed) =
        when {
          shape.size == 3 && shape[0] == 1 && shape[2] >= 6 -> Triple(shape[1], shape[2], false)
          shape.size == 3 && shape[0] == 1 && shape[1] >= 6 -> Triple(shape[2], shape[1], true)
          shape.size == 2 && shape[1] >= 6 -> Triple(shape[0], shape[1], false)
          else -> return emptyList()
        }

    val floats = readAllFloats(out, outputType, outputElementCount)
    if (floats.isEmpty()) return emptyList()

    val hasObjectness = ((stride - 5) in 1..1000)
    val classStart = if (hasObjectness) 5 else 4
    val classCount = (stride - classStart).coerceAtLeast(1)

    var maxCoord = 0f
    val candidates = ArrayList<Detection>(min(nBoxes, maxResults * 8))
    for (i in 0 until nBoxes) {
      fun at(k: Int): Float =
          if (!transposed) {
            // [N, D]
            floats[(i * stride) + k]
          } else {
            // [D, N]
            floats[(k * nBoxes) + i]
          }

      val x = at(0)
      val y = at(1)
      val w = at(2)
      val h = at(3)
      maxCoord = max(maxCoord, max(max(x, y), max(w, h)))

      val obj = if (hasObjectness) at(4) else 1f
      var bestScore = 0f
      var bestClass = 0
      for (c in 0 until classCount) {
        val s = at(classStart + c) * obj
        if (s > bestScore) {
          bestScore = s
          bestClass = c
        }
      }
      if (bestScore < scoreThreshold) continue

      val left = x - (w / 2f)
      val top = y - (h / 2f)
      val right = x + (w / 2f)
      val bottom = y + (h / 2f)
      candidates.add(
          Detection(
              left = left,
              top = top,
              right = right,
              bottom = bottom,
              classIndex = bestClass,
              score = bestScore,
          )
      )
    }

    if (candidates.isEmpty()) return emptyList()

    // Heuristic: if coordinates look like pixels, normalize by input dims.
    val coordsArePixels = maxCoord > 1.5f
    if (!coordsArePixels) {
      return candidates.map { it.clamp01() }
    }
    val sx = 1f / inputWidth.toFloat().coerceAtLeast(1f)
    val sy = 1f / inputHeight.toFloat().coerceAtLeast(1f)
    return candidates.map { det ->
      Detection(
          left = (det.left * sx),
          top = (det.top * sy),
          right = (det.right * sx),
          bottom = (det.bottom * sy),
          classIndex = det.classIndex,
          score = det.score,
      ).clamp01()
    }
  }

  private fun Detection.clamp01(): Detection =
      Detection(
          left = left.coerceIn(0f, 1f),
          top = top.coerceIn(0f, 1f),
          right = right.coerceIn(0f, 1f),
          bottom = bottom.coerceIn(0f, 1f),
          classIndex = classIndex,
          score = score,
      )

  private fun nms(detections: List<Detection>): List<Detection> {
    val sorted = detections.sortedByDescending { it.score }
    val picked = ArrayList<Detection>(min(maxResults, sorted.size))
    val suppressed = BooleanArray(sorted.size)

    for (i in sorted.indices) {
      if (suppressed[i]) continue
      val a = sorted[i]
      picked.add(a)
      if (picked.size >= maxResults) break
      for (j in i + 1 until sorted.size) {
        if (suppressed[j]) continue
        val b = sorted[j]
        if (a.classIndex != b.classIndex) continue
        if (iou(a, b) > iouThreshold) suppressed[j] = true
      }
    }
    return picked
  }

  private fun iou(a: Detection, b: Detection): Float {
    val ax1 = min(a.left, a.right)
    val ay1 = min(a.top, a.bottom)
    val ax2 = max(a.left, a.right)
    val ay2 = max(a.top, a.bottom)
    val bx1 = min(b.left, b.right)
    val by1 = min(b.top, b.bottom)
    val bx2 = max(b.left, b.right)
    val by2 = max(b.top, b.bottom)

    val interLeft = max(ax1, bx1)
    val interTop = max(ay1, by1)
    val interRight = min(ax2, bx2)
    val interBottom = min(ay2, by2)
    val interW = (interRight - interLeft).coerceAtLeast(0f)
    val interH = (interBottom - interTop).coerceAtLeast(0f)
    val interArea = interW * interH
    if (interArea <= 0f) return 0f
    val aArea = (ax2 - ax1).coerceAtLeast(0f) * (ay2 - ay1).coerceAtLeast(0f)
    val bArea = (bx2 - bx1).coerceAtLeast(0f) * (by2 - by1).coerceAtLeast(0f)
    val denom = (aArea + bArea - interArea).coerceAtLeast(1e-6f)
    return interArea / denom
  }

  private fun readAllFloats(buf: ByteBuffer, type: DataType, count: Int): FloatArray {
    val out = FloatArray(count)
    when {
      type == DataType.FLOAT32 -> {
        for (i in 0 until count) out[i] = buf.float
      }
      // Some builds of TFLite's DataType enum may not expose FLOAT16 directly; detect via name.
      type.toString().equals("FLOAT16", ignoreCase = true) -> {
        for (i in 0 until count) out[i] = halfToFloat(buf.short)
      }
      else -> return FloatArray(0)
    }
    return out
  }

  private fun bytesPerElement(type: DataType): Int =
      when {
        type == DataType.UINT8 -> 1
        type == DataType.FLOAT32 -> 4
        type.toString().equals("FLOAT16", ignoreCase = true) -> 2
        else -> 4
      }

  private fun halfToFloat(h: Short): Float {
    val bits = h.toInt() and 0xFFFF
    val sign = (bits ushr 15) and 0x1
    val exp = (bits ushr 10) and 0x1F
    val mant = bits and 0x3FF

    val fBits =
        when (exp) {
          0 -> {
            if (mant == 0) {
              sign shl 31
            } else {
              // Subnormal
              var e = -1
              var m = mant
              while ((m and 0x400) == 0) {
                m = m shl 1
                e--
              }
              m = m and 0x3FF
              val exp32 = (127 - 15 + 1 + e) shl 23
              val mant32 = m shl 13
              (sign shl 31) or exp32 or mant32
            }
          }
          31 -> {
            // Inf/NaN
            (sign shl 31) or 0x7F800000 or (mant shl 13)
          }
          else -> {
            val exp32 = (exp + (127 - 15)) shl 23
            val mant32 = mant shl 13
            (sign shl 31) or exp32 or mant32
          }
        }
    return Float.fromBits(fBits)
  }

  private fun loadModel(context: Context, assetName: String): MappedByteBuffer {
    val fd = context.assets.openFd(assetName)
    FileInputStream(fd.fileDescriptor).use { input ->
      val channel = input.channel
      return channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
    }
  }
}

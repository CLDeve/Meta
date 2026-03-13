#include <jni.h>
#include <stdint.h>

static inline int clamp8(int value) {
  if (value < 0) return 0;
  if (value > 255) return 255;
  return value;
}

extern "C" JNIEXPORT void JNICALL
Java_com_meta_wearable_dat_externalsampleapps_cameraaccess_stream_NativeYuv_i420ToArgb(
    JNIEnv* env,
    jclass /*clazz*/,
    jbyteArray i420,
    jint width,
    jint height,
    jobject outBuffer,
    jint outWidth,
    jint outHeight) {
  if (width <= 0 || height <= 0 || outWidth <= 0 || outHeight <= 0) return;

  uint8_t* dst = static_cast<uint8_t*>(env->GetDirectBufferAddress(outBuffer));
  if (!dst) return;

  const int yPlaneSize = width * height;
  const int uvWidth = width / 2;
  const int uvHeight = height / 2;
  if (uvWidth <= 0 || uvHeight <= 0) return;
  const int uvPlaneSize = uvWidth * uvHeight;
  const int expectedMinSize = yPlaneSize + uvPlaneSize + uvPlaneSize;

  const jsize srcSize = env->GetArrayLength(i420);
  if (srcSize < expectedMinSize) return;

  jboolean isCopy = JNI_FALSE;
  const uint8_t* src = reinterpret_cast<const uint8_t*>(env->GetByteArrayElements(i420, &isCopy));
  if (!src) return;

  const uint8_t* srcY = src;
  const uint8_t* srcU = src + yPlaneSize;
  const uint8_t* srcV = src + yPlaneSize + uvPlaneSize;

  const int xStep = ((width << 16) / outWidth) > 0 ? ((width << 16) / outWidth) : 1;
  const int yStep = ((height << 16) / outHeight) > 0 ? ((height << 16) / outHeight) : 1;

  int yAcc = 0;
  uint32_t* dst32 = reinterpret_cast<uint32_t*>(dst);
  for (int oy = 0; oy < outHeight; oy++) {
    const int iy = (yAcc >> 16) < height ? (yAcc >> 16) : (height - 1);
    const int uvY = (iy >> 1) < uvHeight ? (iy >> 1) : (uvHeight - 1);
    const int yRowOffset = iy * width;
    const int uvRowOffset = uvY * uvWidth;

    int xAcc = 0;
    for (int ox = 0; ox < outWidth; ox++) {
      const int ix = (xAcc >> 16) < width ? (xAcc >> 16) : (width - 1);
      const int uvX = (ix >> 1) < uvWidth ? (ix >> 1) : (uvWidth - 1);
      const int yIdx = yRowOffset + ix;
      const int uvIdx = uvRowOffset + uvX;

      const int Y = static_cast<int>(srcY[yIdx]) & 0xFF;
      const int U = static_cast<int>(srcU[uvIdx]) & 0xFF;
      const int V = static_cast<int>(srcV[uvIdx]) & 0xFF;

      const int C = Y - 16;
      const int D = U - 128;
      const int E = V - 128;

      const int r = clamp8((298 * C + 409 * E + 128) >> 8);
      const int g = clamp8((298 * C - 100 * D - 208 * E + 128) >> 8);
      const int b = clamp8((298 * C + 516 * D + 128) >> 8);

      // Android's Bitmap.copyPixelsFromBuffer() for ARGB_8888 expects the buffer in native byte
      // order. On little-endian devices, the byte order is RGBA, so we pack ABGR into the 32-bit
      // word so memory becomes R,G,B,A.
      dst32[oy * outWidth + ox] = 0xFF000000u | (static_cast<uint32_t>(b) << 16) |
          (static_cast<uint32_t>(g) << 8) | static_cast<uint32_t>(r);

      xAcc += xStep;
    }
    yAcc += yStep;
  }

  env->ReleaseByteArrayElements(i420, reinterpret_cast<jbyte*>(const_cast<uint8_t*>(src)),
                                JNI_ABORT);
}

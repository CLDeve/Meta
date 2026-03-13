package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import java.nio.ByteBuffer

internal object NativeYuv {
  init {
    System.loadLibrary("cameraaccess_yuv")
  }

  @JvmStatic external fun i420ToArgb(
      i420: ByteArray,
      width: Int,
      height: Int,
      outBuffer: ByteBuffer,
      outWidth: Int,
      outHeight: Int,
  )
}


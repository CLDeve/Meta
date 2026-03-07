package com.meta.wearable.dat.externalsampleapps.cameraaccess.webrtc

import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

open class SdpObserverAdapter : SdpObserver {
  override fun onCreateSuccess(sessionDescription: SessionDescription?) {}

  override fun onSetSuccess() {}

  override fun onCreateFailure(message: String?) {}

  override fun onSetFailure(message: String?) {}
}

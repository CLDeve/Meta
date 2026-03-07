package com.meta.wearable.dat.externalsampleapps.cameraaccess.webrtc

import android.content.Context
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SessionDescription

class WebRtcClient(
    context: Context,
    private val signalingUrl: String,
    private val room: String,
    private val listener: Listener,
) : SignalingClient.Listener {

  interface Listener {
    fun onStatusChanged(message: String)

    fun onError(message: String)
  }

  private val appContext = context.applicationContext
  private val signalingClient =
      SignalingClient(
          serverUrl = signalingUrl,
          room = room,
          role = "broadcaster",
          listener = this,
      )
  private val peerConnectionFactory: PeerConnectionFactory
  private val peerConnection: PeerConnection
  private var dataChannel: DataChannel? = null
  private var lastFrameSentAtMs: Long = 0L

  init {
    PeerConnectionFactory.initialize(
        PeerConnectionFactory.InitializationOptions.builder(appContext).createInitializationOptions()
    )
    peerConnectionFactory =
        PeerConnectionFactory.builder().createPeerConnectionFactory()
    peerConnection =
        checkNotNull(
            peerConnectionFactory.createPeerConnection(
                fetchIceServers(),
                object : PeerConnection.Observer {
                  override fun onSignalingChange(state: PeerConnection.SignalingState) {}

                  override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                    listener.onStatusChanged("Live POV ${state.name.lowercase()}")
                  }

                  override fun onIceConnectionReceivingChange(receiving: Boolean) {}

                  override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}

                  override fun onIceCandidate(candidate: IceCandidate) {
                    signalingClient.sendIceCandidate(
                        candidate.sdpMid,
                        candidate.sdpMLineIndex,
                        candidate.sdp,
                    )
                  }

                  override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}

                  override fun onAddStream(stream: org.webrtc.MediaStream) {}

                  override fun onRemoveStream(stream: org.webrtc.MediaStream) {}

                  override fun onDataChannel(channel: DataChannel) {}

                  override fun onRenegotiationNeeded() {}

                  override fun onAddTrack(
                      receiver: org.webrtc.RtpReceiver,
                      mediaStreams: Array<out org.webrtc.MediaStream>,
                  ) {}
                },
            )
        )
  }

  fun start() {
    signalingClient.connect()
  }

  fun stop() {
    dataChannel?.close()
    dataChannel = null
    peerConnection.close()
    signalingClient.close()
  }

  fun sendFrame(bitmap: Bitmap) {
    val channel = dataChannel ?: return
    if (channel.state() != DataChannel.State.OPEN) {
      return
    }
    val now = System.currentTimeMillis()
    if (now - lastFrameSentAtMs < 100L) {
      return
    }
    lastFrameSentAtMs = now
    val scaled = scaleBitmap(bitmap, 960)
    val bytes =
        ByteArrayOutputStream().use { stream ->
          scaled.compress(Bitmap.CompressFormat.JPEG, 60, stream)
          stream.toByteArray()
        }
    channel.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), true))
    if (scaled !== bitmap) {
      scaled.recycle()
    }
  }

  override fun onConnected() {
    listener.onStatusChanged("Live POV signaling connected")
  }

  override fun onViewerReady() {
    if (dataChannel == null) {
      dataChannel =
          peerConnection.createDataChannel(
              "pov",
              DataChannel.Init().apply {
                ordered = true
              },
          )
    }
    peerConnection.createOffer(
        object : SdpObserverAdapter() {
          override fun onCreateSuccess(sessionDescription: SessionDescription?) {
            if (sessionDescription == null) return
            peerConnection.setLocalDescription(SdpObserverAdapter(), sessionDescription)
            signalingClient.sendOffer(sessionDescription.description)
          }

          override fun onCreateFailure(message: String?) {
            listener.onError(message ?: "Failed to create live POV offer")
          }
        },
        org.webrtc.MediaConstraints(),
    )
  }

  override fun onAnswer(sdp: String) {
    if (sdp.isBlank()) return
    peerConnection.setRemoteDescription(
        SdpObserverAdapter(),
        SessionDescription(SessionDescription.Type.ANSWER, sdp),
    )
  }

  override fun onIceCandidate(
      sdpMid: String?,
      sdpMLineIndex: Int,
      candidate: String,
  ) {
    if (candidate.isBlank()) return
    peerConnection.addIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, candidate))
  }

  override fun onPeerLeft() {
    listener.onStatusChanged("Viewer disconnected")
  }

  override fun onError(message: String) {
    listener.onError(message)
  }

  private fun fetchIceServers(): List<PeerConnection.IceServer> {
    val fallback =
        listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
        )
    val baseHttpUrl =
        signalingUrl.replaceFirst("ws://", "http://").replaceFirst("wss://", "https://")
            .substringBefore("/ws")
    if (baseHttpUrl.isBlank()) {
      return fallback
    }
    return runCatching {
          val request = Request.Builder().url("$baseHttpUrl/api/turn").build()
          val response = OkHttpClient().newCall(request).execute()
          if (!response.isSuccessful) return fallback
          val body = response.body?.string().orEmpty()
          val root = JSONObject(body)
          val servers = root.optJSONArray("iceServers") ?: JSONArray()
          buildList {
            for (index in 0 until servers.length()) {
              val item = servers.optJSONObject(index) ?: continue
              val urlsJson = item.opt("urls")
              val urls =
                  when (urlsJson) {
                    is JSONArray -> buildList {
                      for (urlIndex in 0 until urlsJson.length()) {
                        val url = urlsJson.optString(urlIndex)
                        if (url.isNotBlank()) add(url)
                      }
                    }
                    is String -> listOf(urlsJson)
                    else -> emptyList()
                  }
              if (urls.isEmpty()) continue
              val builder = PeerConnection.IceServer.builder(urls)
              item.optString("username").takeIf { it.isNotBlank() }?.let { builder.setUsername(it) }
              item.optString("credential").takeIf { it.isNotBlank() }?.let { builder.setPassword(it) }
              add(builder.createIceServer())
            }
          }.ifEmpty { fallback }
        }
        .getOrElse { fallback }
  }

  private fun scaleBitmap(bitmap: Bitmap, maxWidth: Int): Bitmap {
    if (bitmap.width <= maxWidth) return bitmap
    val scale = maxWidth.toFloat() / bitmap.width.toFloat()
    val targetHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(bitmap, maxWidth, targetHeight, true)
  }
}

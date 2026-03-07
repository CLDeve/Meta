package com.meta.wearable.dat.externalsampleapps.cameraaccess.webrtc

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

class SignalingClient(
    private val serverUrl: String,
    private val room: String,
    private val role: String,
    private val listener: Listener,
) {

  interface Listener {
    fun onConnected()

    fun onViewerReady()

    fun onAnswer(sdp: String)

    fun onIceCandidate(
        sdpMid: String?,
        sdpMLineIndex: Int,
        candidate: String,
    )

    fun onPeerLeft()

    fun onError(message: String)
  }

  private val httpClient = OkHttpClient()
  private var webSocket: WebSocket? = null

  fun connect() {
    if (serverUrl.isBlank()) {
      listener.onError("LIVE_POV_SIGNALING_URL is missing.")
      return
    }
    val request = Request.Builder().url(serverUrl).build()
    webSocket =
        httpClient.newWebSocket(
            request,
            object : WebSocketListener() {
              override fun onOpen(webSocket: WebSocket, response: Response) {
                sendJson(
                    JSONObject()
                        .put("type", "join")
                        .put("room", room)
                        .put("role", role)
                )
                listener.onConnected()
              }

              override fun onMessage(webSocket: WebSocket, text: String) {
                val root = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (root.optString("type")) {
                  "viewer-ready" -> listener.onViewerReady()
                  "answer" -> listener.onAnswer(root.optString("sdp"))
                  "ice" ->
                      listener.onIceCandidate(
                          root.optString("sdpMid").ifBlank { null },
                          root.optInt("sdpMLineIndex", 0),
                          root.optString("candidate"),
                      )
                  "peer-left" -> listener.onPeerLeft()
                  "error" -> listener.onError(root.optString("message").ifBlank { "Signaling error" })
                }
              }

              override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                listener.onError(t.message ?: "Signaling connection failed")
              }
            },
        )
  }

  fun sendOffer(sdp: String) {
    sendJson(JSONObject().put("type", "offer").put("room", room).put("sdp", sdp))
  }

  fun sendAnswer(sdp: String) {
    sendJson(JSONObject().put("type", "answer").put("room", room).put("sdp", sdp))
  }

  fun sendIceCandidate(
      sdpMid: String?,
      sdpMLineIndex: Int,
      candidate: String,
  ) {
    sendJson(
        JSONObject()
            .put("type", "ice")
            .put("room", room)
            .put("sdpMid", sdpMid)
            .put("sdpMLineIndex", sdpMLineIndex)
            .put("candidate", candidate)
    )
  }

  fun close() {
    webSocket?.close(1000, "done")
    webSocket = null
  }

  private fun sendJson(payload: JSONObject) {
    webSocket?.send(payload.toString())
  }
}

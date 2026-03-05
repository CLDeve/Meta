/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// StreamUiState - DAT Camera Streaming UI State
//
// This data class manages UI state for camera streaming operations using the DAT API.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import android.graphics.Bitmap
import com.meta.wearable.dat.camera.types.StreamSessionState

enum class ChatRole {
  USER,
  ASSISTANT,
}

data class ChatMessage(
    val role: ChatRole,
    val text: String,
)

data class StreamUiState(
    val streamSessionState: StreamSessionState = StreamSessionState.STOPPED,
    val videoFrame: Bitmap? = null,
    val capturedPhoto: Bitmap? = null,
    val isShareDialogVisible: Boolean = false,
    val timerMode: TimerMode = TimerMode.UNLIMITED,
    val remainingTimeSeconds: Long? = null,
    val isDescribeLoading: Boolean = false,
    val describeResult: String? = null,
    val describeError: String? = null,
    val commandCenterStatus: String? = null,
    val commandCenterError: String? = null,
    val isListening: Boolean = false,
    val isHandsFreeModeEnabled: Boolean = false,
    val voiceHeardText: String? = null,
    val chatMessages: List<ChatMessage> = emptyList(),
)

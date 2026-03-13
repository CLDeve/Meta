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

data class NormalizedBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val score: Float? = null,
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
    val isPatrolModeEnabled: Boolean = false,
    val isLivePovSharingEnabled: Boolean = false,
    val isPeopleCountingEnabled: Boolean = false,
    val peopleCount: Int? = null,
    val isHeyCasEnabled: Boolean = false,
    val isPeopleCountPageVisible: Boolean = false,
    val peopleCountSnapshot: Bitmap? = null,
    val peopleCountSnapshotCount: Int? = null,
    val peopleCountSnapshotBoxes: List<NormalizedBox> = emptyList(),
    val voiceHeardText: String? = null,
    val chatMessages: List<ChatMessage> = emptyList(),
)

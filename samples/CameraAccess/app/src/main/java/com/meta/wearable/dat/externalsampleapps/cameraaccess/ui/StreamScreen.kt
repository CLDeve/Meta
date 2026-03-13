/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// StreamScreen - DAT Camera Streaming UI
//
// This composable demonstrates the main streaming UI for DAT camera functionality. It shows how to
// display live video from wearable devices and handle photo capture.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.Icon
import androidx.compose.foundation.BorderStroke
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meta.wearable.dat.camera.types.StreamSessionState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.R
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.ChatMessage
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.ChatRole
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.StreamViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.StreamUiState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ui.typography.AppTypography

@Composable
fun StreamScreen(
    wearablesViewModel: WearablesViewModel,
    autoStartVoiceNonce: Long = 0L,
    onAutoStartVoiceConsumed: (Long) -> Unit = {},
    modifier: Modifier = Modifier,
    streamViewModel: StreamViewModel =
        viewModel(
            factory =
                StreamViewModel.Factory(
                    application = (LocalActivity.current as ComponentActivity).application,
                    wearablesViewModel = wearablesViewModel,
                ),
        ),
) {
  val streamUiState by streamViewModel.uiState.collectAsStateWithLifecycle()
  val context = LocalContext.current
  var isChatExpanded by rememberSaveable { mutableStateOf(false) }
  val videoFrame = streamUiState.videoFrame
  val chatMessages = streamUiState.chatMessages
  val isDescribeLoading = streamUiState.isDescribeLoading

  LaunchedEffect(Unit) { streamViewModel.startStream() }
  LaunchedEffect(autoStartVoiceNonce) {
    if (autoStartVoiceNonce > 0L) {
      streamViewModel.startVoiceDescribe(context)
      onAutoStartVoiceConsumed(autoStartVoiceNonce)
    }
  }

  val backgroundBrush =
      Brush.linearGradient(listOf(Color(0xFF02060C), Color(0xFF07101F), Color(0xFF0A1830)))

  Box(modifier = modifier.fillMaxSize().background(brush = backgroundBrush)) {
    val previewShape = RoundedCornerShape(0.dp)
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
      videoFrame?.let { currentFrame ->
        Image(
            bitmap = currentFrame.asImageBitmap(),
            contentDescription = stringResource(R.string.live_stream),
            modifier =
                Modifier.fillMaxSize()
                    .clip(previewShape),
            contentScale = ContentScale.Crop,
        )
      } ?: Surface(
          modifier =
              Modifier.fillMaxSize()
                  .clip(previewShape),
          color = Color(0xFF0E1D36),
      ) {
        Box(contentAlignment = Alignment.Center) {
          Text(
              text = stringResource(id = R.string.live_stream),
              style = AppTypography.Body,
              color = Color.White.copy(alpha = 0.7f),
          )
        }
      }
      // Reduce harsh glare from bright scenes and improve overlay readability.
      Box(
          modifier =
              Modifier.fillMaxSize()
                  .clip(previewShape)
                  .background(
                      Brush.verticalGradient(
                          listOf(
                              Color.Black.copy(alpha = 0.48f),
                              Color.Transparent,
                              Color.Black.copy(alpha = 0.44f),
                          )
                      )
                  )
      )
    }
    if (streamUiState.streamSessionState == StreamSessionState.STARTING) {
      CircularProgressIndicator(
          modifier = Modifier.align(Alignment.Center),
      )
    }

    StatusOverlay(
        isListening = streamUiState.isListening,
        isDescribeLoading = streamUiState.isDescribeLoading,
        isHandsFreeModeEnabled = streamUiState.isHandsFreeModeEnabled,
        isPatrolModeEnabled = streamUiState.isPatrolModeEnabled,
        isLivePovSharingEnabled = streamUiState.isLivePovSharingEnabled,
        isPeopleCountingEnabled = streamUiState.isPeopleCountingEnabled,
        peopleCount = streamUiState.peopleCount,
        describeResult = streamUiState.describeResult,
        describeError = streamUiState.describeError,
        commandCenterStatus = streamUiState.commandCenterStatus,
        commandCenterError = streamUiState.commandCenterError,
        voiceHeardText = streamUiState.voiceHeardText,
        modifier =
            Modifier.align(Alignment.TopStart)
                .padding(horizontal = 14.dp, vertical = 16.dp),
    )

    ChatOverlay(
        streamUiState = streamUiState,
        isDescribeLoading = isDescribeLoading,
        chatMessages = chatMessages,
        isExpanded = isChatExpanded,
        onToggleExpanded = { isChatExpanded = !isChatExpanded },
        onSendQuestion = { streamViewModel.describeCurrentFrame(it) },
        onStartVoiceDescribe = { streamViewModel.startVoiceDescribe(context) },
        onCapturePhoto = { streamViewModel.capturePhoto() },
        onTogglePeopleCounting = { streamViewModel.togglePeopleCounting() },
        onToggleLivePov = { streamViewModel.toggleLivePovSharing() },
        onTogglePatrol = { streamViewModel.togglePatrolMode() },
        onStopStream = {
          streamViewModel.stopStream()
          wearablesViewModel.navigateToDeviceSelection()
        },
        modifier =
            Modifier.align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .fillMaxWidth()
                .widthIn(max = 560.dp),
    )

    // Countdown timer display
    streamUiState.remainingTimeSeconds?.let { seconds ->
      val minutes = seconds / 60
      val remainingSeconds = seconds % 60
      Text(
          text = stringResource(id = R.string.time_remaining, minutes, remainingSeconds),
          color = Color.White,
          modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
          textAlign = TextAlign.Center,
          style = AppTypography.Body,
      )
    }
  }

  streamUiState.capturedPhoto?.let { photo ->
    if (streamUiState.isShareDialogVisible) {
      SharePhotoDialog(
          photo = photo,
          onDismiss = { streamViewModel.hideShareDialog() },
          onShare = { bitmap ->
            streamViewModel.sharePhoto(bitmap)
            streamViewModel.hideShareDialog()
          },
      )
    }
  }
}

@Composable
private fun ChatOverlay(
    streamUiState: StreamUiState,
    isDescribeLoading: Boolean,
    chatMessages: List<ChatMessage>,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    onSendQuestion: (String) -> Unit,
    onStartVoiceDescribe: () -> Unit,
    onCapturePhoto: () -> Unit,
    onTogglePeopleCounting: () -> Unit,
    onToggleLivePov: () -> Unit,
    onTogglePatrol: () -> Unit,
    onStopStream: () -> Unit,
    modifier: Modifier = Modifier,
) {
  var draft by rememberSaveable { mutableStateOf("") }
  var isMenuOpen by rememberSaveable { mutableStateOf(false) }
  val canSend = draft.isNotBlank() && !isDescribeLoading
  val listState = rememberLazyListState()
  val composerFocusRequester = remember { FocusRequester() }

  fun submitDraft() {
    val normalized = draft.trim()
    if (normalized.isEmpty() || isDescribeLoading) return
    onSendQuestion(normalized)
    draft = ""
  }

  LaunchedEffect(chatMessages.size, isExpanded) {
    if (isExpanded && chatMessages.isNotEmpty()) {
      listState.scrollToItem(index = maxOf(0, chatMessages.size - 1))
    }
  }

  Surface(
      modifier = modifier,
      color = Color(0xFF0A0B0E).copy(alpha = 0.88f),
      shape = RoundedCornerShape(26.dp),
      border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
      shadowElevation = 18.dp,
  ) {
    Column(modifier = Modifier.fillMaxWidth()) {
      if (isExpanded) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Surface(
              color = Color.White.copy(alpha = 0.08f),
              shape = RoundedCornerShape(999.dp),
              border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
          ) {
            Text(
                text = "Chat",
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                color = Color.White,
                style = AppTypography.Body.copy(fontWeight = FontWeight.SemiBold),
            )
          }
          Text(
              text = chatMessages.lastOrNull()?.text ?: "No messages yet",
              modifier = Modifier.weight(1f),
              maxLines = 1,
              color = Color.White.copy(alpha = 0.75f),
              style = AppTypography.Body.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize),
          )
          TextButton(onClick = onToggleExpanded) {
            Text(text = stringResource(id = R.string.chat_hide_button), style = AppTypography.Button)
          }
        }

        HorizontalDivider(color = Color.White.copy(alpha = 0.10f))

        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            state = listState,
        ) {
          items(chatMessages.takeLast(18)) { message ->
            val isUser = message.role == ChatRole.USER
            val bubbleColor = if (isUser) Color(0xFF1B2A42) else Color.White.copy(alpha = 0.07f)
            val bubbleBorder = Color.White.copy(alpha = 0.10f)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
              Surface(
                  color = bubbleColor,
                  shape = RoundedCornerShape(18.dp),
                  border = BorderStroke(1.dp, bubbleBorder),
              ) {
                Text(
                    text = message.text,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    color = Color.White,
                    style = AppTypography.Body.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize),
                )
              }
            }
          }
        }

        HorizontalDivider(color = Color.White.copy(alpha = 0.10f))
      }

      Box(modifier = Modifier.fillMaxWidth()) {
        if (isMenuOpen) {
          Surface(
              modifier =
                  Modifier.align(Alignment.TopStart)
                      .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 72.dp)
                      .fillMaxWidth()
                      .widthIn(max = 520.dp),
              color = Color(0xFF14161A).copy(alpha = 0.92f),
              shape = RoundedCornerShape(22.dp),
              border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
              shadowElevation = 22.dp,
          ) {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
              ActionMenuItem(icon = Icons.Filled.PhotoCamera, label = "Camera") {
                isMenuOpen = false
                onCapturePhoto()
              }
              ActionMenuItem(
                  icon = Icons.Filled.People,
                  label = if (streamUiState.isPeopleCountingEnabled) "People counting: ON" else "People counting: OFF",
              ) {
                onTogglePeopleCounting()
              }
              ActionMenuItem(
                  icon = Icons.Filled.Public,
                  label = if (streamUiState.isLivePovSharingEnabled) "Live POV: Stop sharing" else "Live POV: Start sharing",
              ) {
                onToggleLivePov()
              }
              ActionMenuItem(
                  icon = Icons.Filled.Security,
                  label = if (streamUiState.isPatrolModeEnabled) "Patrol mode: Stop" else "Patrol mode: Start",
              ) {
                onTogglePatrol()
              }
              HorizontalDivider(color = Color.White.copy(alpha = 0.10f), modifier = Modifier.padding(vertical = 6.dp))
              ActionMenuItem(icon = Icons.Filled.StopCircle, label = "Stop stream") {
                isMenuOpen = false
                onStopStream()
              }
            }
          }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          IconButton(
              onClick = { isMenuOpen = !isMenuOpen },
              enabled = !isDescribeLoading,
          ) {
            Icon(imageVector = Icons.Filled.Add, contentDescription = "Menu", tint = Color.White)
          }

          TextField(
              value = draft,
              onValueChange = { draft = it },
              modifier =
                  Modifier.weight(1f)
                      .heightIn(min = 54.dp, max = 132.dp)
                      .wrapContentHeight(Alignment.CenterVertically)
                      .focusRequester(composerFocusRequester)
                      .onFocusChanged { state ->
                        if (state.isFocused && !isExpanded) {
                          onToggleExpanded()
                        }
                      },
              placeholder = {
                Text(
                    text = "Message",
                    color = Color.White.copy(alpha = 0.45f),
                    style = AppTypography.Body,
                )
              },
              enabled = !isDescribeLoading,
              keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
              keyboardActions = KeyboardActions(onSend = { submitDraft() }),
              singleLine = false,
              minLines = 1,
              maxLines = 4,
              textStyle = AppTypography.Body.copy(color = Color.White),
              shape = RoundedCornerShape(18.dp),
              colors =
                  TextFieldDefaults.colors(
                      focusedContainerColor = Color.White.copy(alpha = 0.06f),
                      unfocusedContainerColor = Color.White.copy(alpha = 0.05f),
                      disabledContainerColor = Color.White.copy(alpha = 0.03f),
                      focusedIndicatorColor = Color.Transparent,
                      unfocusedIndicatorColor = Color.Transparent,
                      disabledIndicatorColor = Color.Transparent,
                      cursorColor = Color.White,
                  ),
          )

          IconButton(
              onClick = {
                isMenuOpen = false
                onStartVoiceDescribe()
              },
              enabled = !isDescribeLoading,
          ) {
            Icon(imageVector = Icons.Filled.GraphicEq, contentDescription = "Voice", tint = Color(0xFF9ED3FF))
          }

          IconButton(
              onClick = {
                isMenuOpen = false
                if (!isExpanded) onToggleExpanded()
                submitDraft()
              },
              enabled = canSend,
          ) {
            Icon(imageVector = Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = Color.White)
          }

          IconButton(
              onClick = {
                isMenuOpen = false
                onToggleExpanded()
              },
          ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Chat,
                contentDescription = "Chat",
                tint = Color(0xFFFFD97C),
            )
          }
        }
      }
    }
  }
}

@Composable
private fun ActionMenuItem(icon: ImageVector, label: String, onClick: () -> Unit) {
  TextButton(
      onClick = onClick,
      modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
  ) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Surface(
          color = Color.White.copy(alpha = 0.08f),
          shape = RoundedCornerShape(14.dp),
          border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
      ) {
        Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
          Icon(imageVector = icon, contentDescription = null, tint = Color.White)
        }
      }
      Text(
          text = label,
          color = Color.White,
          style = AppTypography.Body.copy(fontWeight = FontWeight.Medium),
          modifier = Modifier.weight(1f),
          textAlign = TextAlign.Start,
      )
    }
  }
}

@Composable
private fun StatusOverlay(
    isListening: Boolean,
    isDescribeLoading: Boolean,
    isHandsFreeModeEnabled: Boolean,
    isPatrolModeEnabled: Boolean,
    isLivePovSharingEnabled: Boolean,
    isPeopleCountingEnabled: Boolean,
    peopleCount: Int?,
    describeResult: String?,
    describeError: String?,
    commandCenterStatus: String?,
    commandCenterError: String?,
    voiceHeardText: String?,
    modifier: Modifier = Modifier,
) {
  if (
      !isDescribeLoading &&
          !isListening &&
          !isHandsFreeModeEnabled &&
          !isPatrolModeEnabled &&
          !isLivePovSharingEnabled &&
          !isPeopleCountingEnabled &&
          describeResult.isNullOrEmpty() &&
          describeError.isNullOrEmpty() &&
          commandCenterStatus.isNullOrEmpty() &&
          commandCenterError.isNullOrEmpty() &&
          voiceHeardText.isNullOrEmpty()
  ) {
    return
  }

  Surface(
      modifier = modifier.fillMaxWidth().widthIn(max = 420.dp),
      color = Color(0xFF071424).copy(alpha = 0.74f),
      shape = RoundedCornerShape(22.dp),
      border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
      shadowElevation = 14.dp,
  ) {
    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      val primaryStatus =
          when {
            isListening -> stringResource(id = R.string.voice_listening)
            isDescribeLoading -> stringResource(id = R.string.describe_loading)
            !describeError.isNullOrEmpty() ->
                "${stringResource(id = R.string.describe_error_prefix)} $describeError"
            !describeResult.isNullOrEmpty() -> describeResult
            else -> null
          }

      primaryStatus?.let { text ->
        Text(
            text = text,
            color = Color.White,
            style = AppTypography.Body.copy(fontSize = MaterialTheme.typography.bodyMedium.fontSize, fontWeight = FontWeight.SemiBold),
            maxLines = 3,
        )
      }

      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (isHandsFreeModeEnabled) {
          StatusChip(
              label = stringResource(id = R.string.hands_free_mode_active),
              tint = Color(0xFFB9F6CA),
          )
        }
        if (isPatrolModeEnabled) {
          StatusChip(
              label = "Patrol mode active",
              tint = Color(0xFFFFE082),
          )
        }
        if (isLivePovSharingEnabled) {
          StatusChip(
              label = "Live POV sharing",
              tint = Color(0xFF9ED3FF),
          )
        }
        if (isPeopleCountingEnabled) {
          StatusChip(
              label = "People: ${peopleCount ?: "…"}",
              tint = Color(0xFFB9F6CA),
          )
        }
      }

      voiceHeardText?.let { heard ->
        Text(
            text = "${stringResource(id = R.string.voice_heard_label)} $heard",
            color = Color.LightGray,
            style = AppTypography.Body.copy(color = Color.LightGray, fontSize = MaterialTheme.typography.bodySmall.fontSize),
        )
      }

      commandCenterStatus?.let { status ->
        Text(
            text = status,
            color = Color(0xFFB9F6CA),
            style = AppTypography.Body.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize),
        )
      }

      commandCenterError?.let { error ->
        Text(
            text = "${stringResource(id = R.string.command_center_error_prefix)} $error",
            color = Color(0xFFFFB4A9),
            style = AppTypography.Body.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize),
        )
      }
    }
  }
}

@Composable
private fun StatusChip(label: String, tint: Color) {
  Surface(
      color = tint.copy(alpha = 0.12f),
      shape = RoundedCornerShape(999.dp),
      border = BorderStroke(1.dp, tint.copy(alpha = 0.28f)),
  ) {
    Text(
        text = label,
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        color = tint,
        style = AppTypography.Body.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize),
    )
  }
}

@Composable
private fun MiniActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = Color.White,
) {
  Button(
      onClick = onClick,
      enabled = enabled,
      modifier = modifier.height(56.dp),
      colors =
          androidx.compose.material3.ButtonDefaults.buttonColors(
              containerColor = Color.White.copy(alpha = 0.08f),
              contentColor = Color.White,
              disabledContainerColor = Color.White.copy(alpha = 0.04f),
              disabledContentColor = Color.White.copy(alpha = 0.3f),
          ),
      shape = RoundedCornerShape(18.dp),
      border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
  ) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
      Icon(
          imageVector = icon,
          contentDescription = label,
          tint = tint,
          modifier = Modifier.size(18.dp),
      )
      Spacer(modifier = Modifier.height(4.dp))
      Text(
          text = label,
          style = AppTypography.Button.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
          color = Color.White,
          maxLines = 1,
      )
    }
  }
}

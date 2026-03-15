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

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SentimentSatisfiedAlt
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.Icon
import androidx.compose.foundation.BorderStroke
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meta.wearable.dat.camera.types.StreamSessionState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.R
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.ChatMessage
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.ChatRole
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.NormalizedBox
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
	        if (streamUiState.isLiveBoxesEnabled && streamUiState.livePeopleBoxes.isNotEmpty()) {
	          Canvas(modifier = Modifier.fillMaxSize().clip(previewShape)) {
            val dstW = size.width
            val dstH = size.height
            val srcW = currentFrame.width.toFloat().coerceAtLeast(1f)
            val srcH = currentFrame.height.toFloat().coerceAtLeast(1f)
            val scale = maxOf(dstW / srcW, dstH / srcH)
            val dispW = srcW * scale
            val dispH = srcH * scale
            val dx = (dstW - dispW) / 2f
            val dy = (dstH - dispH) / 2f

	            val stroke = Stroke(width = 3.dp.toPx())
	            val labelTextSizePx = 14.dp.toPx()
	            val labelPaddingPx = 6.dp.toPx()
	            val labelBgHeightPx = labelTextSizePx + (labelPaddingPx * 2f)
	            streamUiState.livePeopleBoxes.forEach { b ->
	              val left = dx + (b.left * dispW)
	              val top = dy + (b.top * dispH)
	              val right = dx + (b.right * dispW)
	              val bottom = dy + (b.bottom * dispH)
	              val boxColor = Color(0xFFFFD000)
	              drawRect(
	                  color = boxColor,
	                  topLeft = androidx.compose.ui.geometry.Offset(left, top),
	                  size =
	                      androidx.compose.ui.geometry.Size(
	                          (right - left).coerceAtLeast(0f),
	                          (bottom - top).coerceAtLeast(0f),
	                      ),
	                  style = stroke,
	              )
	              val label = b.label?.takeIf { it.isNotBlank() } ?: return@forEach
	              val clampedLeft = left.coerceIn(0f, (dstW - 1f).coerceAtLeast(0f))
	              val clampedTop = top.coerceIn(0f, (dstH - 1f).coerceAtLeast(0f))
	              drawRect(
	                  color = Color.Black.copy(alpha = 0.62f),
	                  topLeft = androidx.compose.ui.geometry.Offset(clampedLeft, clampedTop),
	                  size =
	                      androidx.compose.ui.geometry.Size(
	                          (right - left).coerceAtLeast(1f).coerceAtMost(dstW - clampedLeft),
	                          labelBgHeightPx.coerceAtMost(dstH - clampedTop),
	                      ),
	              )
	              drawIntoCanvas { canvas: androidx.compose.ui.graphics.Canvas ->
	                val paint =
	                    android.graphics.Paint().apply {
	                      isAntiAlias = true
	                      textSize = labelTextSizePx
	                      color = android.graphics.Color.argb(240, 255, 208, 0)
	                      typeface = android.graphics.Typeface.MONOSPACE
	                    }
	                val textX = clampedLeft + labelPaddingPx
	                val textY = clampedTop + labelPaddingPx + labelTextSizePx
	                canvas.nativeCanvas.drawText(label, textX, textY, paint)
	              }
	            }
	          }
	        }
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
        onOpenPeopleCountPage = { streamViewModel.showPeopleCountPage() },
        onToggleHeyCas = { streamViewModel.toggleHeyCas(context) },
        onToggleLiveBoxes = { streamViewModel.toggleLiveBoxes() },
        onToggleLivePov = { streamViewModel.toggleLivePovSharing() },
        onTogglePatrol = { streamViewModel.togglePatrolMode() },
        onStopStream = {
          streamViewModel.stopStream()
          wearablesViewModel.navigateToDeviceSelection()
        },
        modifier =
            Modifier.align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                .padding(horizontal = 10.dp)
                .widthIn(max = 560.dp),
    )

    if (streamUiState.isPeopleCountPageVisible) {
      PeopleCountPage(
          liveFrame = streamUiState.videoFrame,
          snapshot = streamUiState.peopleCountSnapshot,
          snapshotBoxes = streamUiState.peopleCountSnapshotBoxes,
          snapshotCount = streamUiState.peopleCountSnapshotCount,
          isAutoCountingEnabled = streamUiState.isPeopleCountingEnabled,
          liveCount = streamUiState.peopleCount,
          onToggleAutoCounting = { streamViewModel.togglePeopleCounting() },
          onCaptureAndDetect = { streamViewModel.capturePeopleCountSnapshot() },
          onClose = { streamViewModel.hidePeopleCountPage() },
          modifier = Modifier.fillMaxSize(),
      )
    }

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
    onOpenPeopleCountPage: () -> Unit,
    onToggleHeyCas: () -> Unit,
    onToggleLiveBoxes: () -> Unit,
    onToggleLivePov: () -> Unit,
    onTogglePatrol: () -> Unit,
    onStopStream: () -> Unit,
    modifier: Modifier = Modifier,
) {
  var draft by rememberSaveable { mutableStateOf("") }
  var isMenuOpen by rememberSaveable { mutableStateOf(false) }
  val canSend = draft.isNotBlank()
  val listState = rememberLazyListState()
  val composerFocusRequester = remember { FocusRequester() }

  fun submitDraft() {
    val normalized = draft.trim()
    if (normalized.isEmpty()) return
    onSendQuestion(normalized)
    draft = ""
  }

  LaunchedEffect(chatMessages.size, isExpanded) {
    if (isExpanded && chatMessages.isNotEmpty()) {
      listState.scrollToItem(index = maxOf(0, chatMessages.size - 1))
    }
  }

  Column(modifier = modifier.fillMaxWidth()) {
    if (isExpanded) {
      Surface(
          modifier = Modifier.fillMaxWidth(),
          color = Color(0xFF0A0B0E).copy(alpha = 0.92f),
          border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
      ) {
        Column(modifier = Modifier.fillMaxWidth()) {
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
              modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).padding(horizontal = 12.dp, vertical = 10.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp),
              state = listState,
          ) {
            items(chatMessages.takeLast(18)) { message ->
              val isUser = message.role == ChatRole.USER
              val bubbleColor = if (isUser) Color(0xFF1B2A42) else Color.White.copy(alpha = 0.07f)
              val bubbleBorder = Color.White.copy(alpha = 0.10f)
              Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
              ) {
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
        }
      }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF0A0B0E).copy(alpha = 0.98f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
    ) {
      Column(modifier = Modifier.fillMaxWidth()) {
        if (isMenuOpen) {
          LazyRow(
              modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
              horizontalArrangement = Arrangement.spacedBy(10.dp),
              verticalAlignment = Alignment.CenterVertically,
          ) {
            item {
              QuickActionIcon(
                  icon = Icons.Filled.PhotoCamera,
                  contentDescription = "Camera",
                  onClick = {
                    isMenuOpen = false
                    onCapturePhoto()
                  },
              )
            }
            item {
              QuickActionIcon(
                  icon = Icons.Filled.People,
                  contentDescription = "People count",
                  isSelected = streamUiState.isPeopleCountPageVisible,
                  onClick = {
                    isMenuOpen = false
                    onOpenPeopleCountPage()
                  },
              )
            }
            item {
              QuickActionIcon(
                  icon = Icons.Filled.RecordVoiceOver,
                  contentDescription = "Hey CAS",
                  isSelected = streamUiState.isHeyCasEnabled,
                  onClick = {
                    isMenuOpen = false
                    onToggleHeyCas()
                  },
              )
            }
            item {
              QuickActionIcon(
                  icon = Icons.Filled.CropFree,
                  contentDescription = "Live boxes",
                  isSelected = streamUiState.isLiveBoxesEnabled,
                  onClick = {
                    isMenuOpen = false
                    onToggleLiveBoxes()
                  },
              )
            }
            item {
              QuickActionIcon(
                  icon = Icons.Filled.Public,
                  contentDescription = "Live POV",
                  isSelected = streamUiState.isLivePovSharingEnabled,
                  onClick = { onToggleLivePov() },
              )
            }
            item {
              QuickActionIcon(
                  icon = Icons.Filled.Security,
                  contentDescription = "Patrol mode",
                  isSelected = streamUiState.isPatrolModeEnabled,
                  onClick = { onTogglePatrol() },
              )
            }
            item {
              QuickActionIcon(
                  icon = Icons.Filled.StopCircle,
                  contentDescription = "Stop stream",
                  onClick = {
                    isMenuOpen = false
                    onStopStream()
                  },
              )
            }
          }
          HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          IconButton(
              onClick = { isMenuOpen = !isMenuOpen },
              enabled = true,
          ) {
            Icon(
                imageVector = if (isMenuOpen) Icons.Filled.ChevronRight else Icons.Filled.Add,
                contentDescription = "Attachments",
                tint = Color.White,
            )
          }

          TextField(
              value = draft,
              onValueChange = { draft = it },
              modifier =
                  Modifier.weight(1f)
                      .heightIn(min = 48.dp, max = 120.dp)
                      .wrapContentHeight(Alignment.CenterVertically)
                      .focusRequester(composerFocusRequester)
                      .onFocusChanged { state ->
                        if (state.isFocused && !isExpanded) {
                          onToggleExpanded()
                        }
                      },
              placeholder = {
                Text(
                    text = "Enter message",
                    color = Color.White.copy(alpha = 0.40f),
                    style = AppTypography.Body,
                )
              },
              enabled = true,
              keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
              keyboardActions = KeyboardActions(onSend = { submitDraft() }),
              singleLine = false,
              minLines = 1,
              maxLines = 4,
              textStyle = AppTypography.Body.copy(color = Color.White),
              shape = RoundedCornerShape(999.dp),
              trailingIcon = {
                IconButton(
                    onClick = { /* TODO: emoji picker */ },
                    enabled = true,
                ) {
                  Icon(
                      imageVector = Icons.Filled.SentimentSatisfiedAlt,
                      contentDescription = "Emoji",
                      tint = Color.White.copy(alpha = 0.85f),
                  )
                }
              },
              colors =
                  TextFieldDefaults.colors(
                      focusedContainerColor = Color.White.copy(alpha = 0.07f),
                      unfocusedContainerColor = Color.White.copy(alpha = 0.06f),
                      disabledContainerColor = Color.White.copy(alpha = 0.03f),
                      focusedIndicatorColor = Color.Transparent,
                      unfocusedIndicatorColor = Color.Transparent,
                      disabledIndicatorColor = Color.Transparent,
                      cursorColor = Color.White,
                  ),
          )

          if (draft.isBlank()) {
            IconButton(
                onClick = {
                  isMenuOpen = false
                  onStartVoiceDescribe()
                },
                enabled = !isDescribeLoading,
            ) {
              Icon(
                  imageVector = Icons.Filled.GraphicEq,
                  contentDescription = "Voice",
                  tint = Color.White,
              )
            }
          } else {
            IconButton(
                onClick = {
                  isMenuOpen = false
                  if (!isExpanded) onToggleExpanded()
                  submitDraft()
                },
                enabled = canSend,
            ) {
              Icon(
                  imageVector = Icons.AutoMirrored.Filled.Send,
                  contentDescription = "Send",
                  tint = Color.White,
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun QuickActionIcon(
    icon: ImageVector,
    contentDescription: String,
    isSelected: Boolean = false,
    onClick: () -> Unit,
) {
  val background = if (isSelected) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.08f)
  Surface(
      onClick = onClick,
      color = background,
      shape = RoundedCornerShape(999.dp),
      border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
  ) {
    Box(modifier = Modifier.size(44.dp), contentAlignment = Alignment.Center) {
      Icon(imageVector = icon, contentDescription = contentDescription, tint = Color.White)
    }
  }
}

@Composable
private fun PeopleCountPage(
    liveFrame: Bitmap?,
    snapshot: Bitmap?,
    snapshotBoxes: List<NormalizedBox>,
    snapshotCount: Int?,
    isAutoCountingEnabled: Boolean,
    liveCount: Int?,
    onToggleAutoCounting: () -> Unit,
    onCaptureAndDetect: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val displayBitmap = snapshot ?: liveFrame
  val showBoxes = snapshot != null && snapshotBoxes.isNotEmpty()

  Surface(modifier = modifier, color = Color(0xFF05070B)) {
    Column(modifier = Modifier.fillMaxSize()) {
      Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        IconButton(onClick = onClose) {
          Icon(
              imageVector = Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = "Back",
              tint = Color.White,
          )
        }
        Text(
            text = "Human Counting",
            color = Color.White,
            style = AppTypography.Body.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.weight(1f),
        )
        Button(onClick = onCaptureAndDetect, enabled = displayBitmap != null) {
          Text(text = "Capture", style = AppTypography.Button)
        }
      }

      Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
            text = if (snapshot != null) "Detected: ${snapshotCount ?: 0}" else "Live: ${liveCount ?: 0}",
            color = Color.White.copy(alpha = 0.85f),
            style = AppTypography.Body,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "Auto",
            color = Color.White.copy(alpha = 0.75f),
            style = AppTypography.Body,
        )
        Spacer(modifier = Modifier.width(10.dp))
        Switch(
            checked = isAutoCountingEnabled,
            onCheckedChange = { onToggleAutoCounting() },
            colors =
                SwitchDefaults.colors(
                    checkedThumbColor = Color(0xFF0A0B0E),
                    checkedTrackColor = Color(0xFFFFD97C),
                    uncheckedThumbColor = Color.White.copy(alpha = 0.80f),
                    uncheckedTrackColor = Color.White.copy(alpha = 0.20f),
                ),
        )
      }

      HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

      Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (displayBitmap == null) {
          Text(
              text = "No frame yet",
              color = Color.White.copy(alpha = 0.70f),
              style = AppTypography.Body,
          )
        } else {
          BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            val aspect = displayBitmap.width.toFloat() / displayBitmap.height.toFloat()
            val mediaModifier = Modifier.fillMaxWidth().aspectRatio(aspect).heightIn(min = 200.dp)
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
              Image(
                  bitmap = displayBitmap.asImageBitmap(),
                  contentDescription = "People snapshot",
                  modifier = mediaModifier,
                  contentScale = ContentScale.Fit,
              )
              if (showBoxes) {
                Canvas(modifier = mediaModifier) {
                  val stroke = Stroke(width = 3.dp.toPx())
                  val w = size.width
                  val h = size.height
                  snapshotBoxes.forEach { b ->
                    val left = b.left * w
                    val top = b.top * h
                    val right = b.right * w
                    val bottom = b.bottom * h
                    drawRect(
                        color = Color(0xFFFFD000),
                        topLeft = androidx.compose.ui.geometry.Offset(left, top),
                        size =
                            androidx.compose.ui.geometry.Size(
                                (right - left).coerceAtLeast(0f),
                                (bottom - top).coerceAtLeast(0f),
                            ),
                        style = stroke,
                    )
                  }
                }
              }
            }
          }
        }
      }
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

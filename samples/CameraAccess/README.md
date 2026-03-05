# Camera Access App

A sample Android application demonstrating integration with Meta Wearables Device Access Toolkit. This app showcases streaming video from Meta AI glasses, capturing photos, and managing connection states.

## Features

- Connect to Meta AI glasses
- Stream camera feed from the device
- Capture photos from glasses
- Timer-based streaming sessions
- Share captured photos

## Prerequisites

- Android Studio Arctic Fox (2021.3.1) or newer
- JDK 11 or newer
- Android SDK 31+ (Android 12.0+)
- Meta Wearables Device Access Toolkit (included as a dependency)
- A Meta AI glasses device for testing (optional for development)

## Building the app

### Using Android Studio

1. Clone this repository
1. Open the project in Android Studio
1. Wait for the project to sync
1. Click the "Build" menu and select "Build Bundle(s) / APK(s)" > "Build APK(s)"

## Running the app

1. Turn 'Developer Mode' on in the Meta AI app.
1. Launch the app.
1. Press the "Connect" button to complete app registration.
1. Once connected, the camera stream from the device will be displayed
1. Use the on-screen controls to:
   - Set stream time limits
   - Capture photos
   - View and save captured photos
   - Disconnect from the device

## Airport command centre flow (voice question -> answer -> dispatch)

This sample supports voice questions during streaming and can forward each question/answer to your command centre endpoint.

1. Set `COMMAND_CENTER_URL` to your backend endpoint (for example `https://your-command-centre.example.com/api/events`).
1. Set `OPENAI_BASE_URL`, `OPENAI_MODEL`, and `OPENAI_API_KEY` for visual answering.
1. Build and run the app.
1. In stream view, tap **Voice ask**, speak your question, and wait for the answer overlay.
1. On successful answer, the app POSTs JSON to `COMMAND_CENTER_URL`.
1. The app also speaks the answer aloud on the phone.

### Real laptop dashboard (live events + frame snapshots)

You can run a local web dashboard on your laptop:

```bash
cd dashboard
python3 server.py --host 0.0.0.0 --port 5055
```

Then open:

- `http://localhost:5055` on your laptop
- or `http://<your-laptop-lan-ip>:5055` from other devices

Build/install Android app with dashboard endpoint:

```bash
COMMAND_CENTER_URL="http://<your-laptop-lan-ip>:5055/api/events" \
OPENAI_BASE_URL="https://api.openai.com/v1" \
OPENAI_MODEL="gpt-4o-mini" \
OPENAI_API_KEY="<your-openai-api-key>" \
./gradlew :app:installDebug --rerun-tasks
```

Command centre payload format:

```json
{
  "timestampEpochMs": 1730905012345,
  "question": "Is gate A12 crowded?",
  "answer": "The camera view shows ...",
  "intent": "open_queue_relief_lane",
  "commandCard": {
    "title": "Queue relief at A12",
    "action": "Open support lane and reassign one officer for crowd flow control.",
    "priority": "medium"
  },
  "source": "meta-wearables-cameraaccess"
}
```

## Troubleshooting

For issues related to the Meta Wearables Device Access Toolkit, please refer to the [developer documentation](https://wearables.developer.meta.com/docs/develop/) or visit our [discussions forum](https://github.com/facebook/meta-wearables-dat-android/discussions)

If you do not get a reply after asking:

1. Confirm the stream preview is visible before asking (reply requires a current frame).
1. Confirm your phone has internet access.
1. Rebuild with a valid `OPENAI_API_KEY`.

## License

This source code is licensed under the license found in the LICENSE file in the root directory of this source tree.

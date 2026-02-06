# Clinical Transcription Demo

This project is a mobile app for capturing **clinical recordings** and turning them into documentation. Record (or import) audio, review playback and transcription, then generate a **SOAP-format summary** (Subjective, Objective, Assessment, Plan) you can copy into your workflow.

Transcription (ASR) and summarization (LLM) are powered by the [**Nexa SDK**](https://docs.nexa.ai).

## Demo Video


https://github.com/user-attachments/assets/3394306b-a7e9-4920-b45c-d4f3295c530f


**(Note that audio processing in the video is sped up to reduce length)**

## Install from APK

```
# Download: https://nexa-model-hub-bucket.s3.us-west-1.amazonaws.com/public/android-demo-release/clinical-transcription-demo.apk
adb install clinical-transcription-demo.apk
```

*Note: this is a debug build of the current project.

## Building from Source

### Prerequisites

- **Git LFS**: This repository uses Git LFS for model files (except Qwen3). Install and set up Git LFS before cloning:
  ```bash
  git lfs install
  ```
- **Android Studio** (latest stable version recommended)
- **JDK 17** or higher
- **Android SDK** with minimum API level 24 (Android 7.0) or higher
- **Qwen3 4B GGUF model file**: Due to Git repo size limitations, the Qwen3 4B GGUF file is not included in this repository. You must download and place it manually:
  1. Download `Qwen3-4B-Q4_K_M.gguf` from the [Qwen3 4B official GGUF repo](https://huggingface.co/Qwen/Qwen3-4B-GGUF/blob/main/Qwen3-4B-Q4_K_M.gguf)
  2. Place the file at: `app/src/main/assets/nexa_models/Qwen3-4B-GGUF/Qwen3-4B-Q4_K_M.gguf`

### Build Instructions

#### Option 1: Using Android Studio

1. Open Android Studio and select **Open an existing project**
2. Navigate to the `clinical-transcription-demo` directory and open it
3. Wait for Gradle sync to complete
4. Then choose one of the following:

   **To run directly on a device/emulator:**
   - Connect an Android device or start an emulator
   - Click the **Run** button (▶)

   **To build an APK:**
   - Select **Build > Build Bundle(s) / APK(s) > Build APK(s)** from the menu
   - Once complete, the APK will be at: `app/build/outputs/apk/debug/app-debug.apk`

#### Option 2: Using Command Line

1. Make the Gradle wrapper executable (if needed):
   ```bash
   chmod +x gradlew
   ```

2. Build the debug APK:
   ```bash
   ./gradlew assembleDebug
   ```

3. The APK will be generated at: `app/build/outputs/apk/debug/app-debug.apk`

## Core workflow

- Capture a recording (or import one) and save it as a note
- Let the app transcribe the audio into text
- Generate a SOAP note from the transcript
- Review results alongside audio playback, then copy what you need

## Outputs

- **Audio note**: playable recording with waveform + scrubbing
- **Transcript**: readable text version of the session
- **SOAP summary**: a structured note in the standard SOAP format

## Privacy

The project is designed to process audio **on-device** (as reflected in the UI), without needing to send recordings to a remote server.

## Current capabilities

- **Record or import audio** to create a note
- **Transcribe** the recording into text
- **Generate a SOAP summary** from the transcript
- **Play back** audio with waveform + scrubbing, and **copy** transcript/summary text

## Notes

- **Export**: the UI includes an export action, but it is not supported yet.
- **Disclaimer**: this is a demo app and is not medical advice or a medical device.

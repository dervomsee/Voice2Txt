# Voice2Txt

A privacy-focused, offline Speech-to-Text (STT) application for Android.

## Overview
Voice2Txt provides high-quality audio transcription using the Whisper AI model. The application is designed to run entirely offline, ensuring that no audio data or transcriptions ever leave the device. It is fully compatible with GrapheneOS and adheres to F-Droid inclusion standards.

## Key Features
- **Offline Transcription**: Uses local Whisper models for processing.
- **Privacy First**: No internet permission required for operation; no tracking or data collection.
- **Intent Sharing**: Transcribe audio files directly by sharing them from other apps (e.g., File Managers, Voice Recorders).
- **GPU Acceleration**: Supports Vulkan-based GPU acceleration for faster processing on compatible hardware.
- **Multilingual Support**: Supports multiple languages with selectable models (Tiny, Base, etc.).
- **Benchmarking**: Integrated performance testing to compare CPU and GPU transcription speeds.

## Use Cases
- **Journaling & Notes**: Quickly convert voice memos into text without cloud dependency.
- **Privacy-Sensitive Meetings**: Transcribe confidential recordings without risk of data leaks.
- **No-Connectivity Environments**: Reliable transcription in areas without cellular data or Wi-Fi.

## Technical Requirements
- Android 14 (API 34) or higher.
- Sufficient storage for Whisper model blobs (approx. 75MB - 150MB for standard models).
- Microphone permission for direct recording.

## Licenses
This project uses `whisper.cpp` which is licensed under the MIT License.

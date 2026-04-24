# Voice2Txt 🎙️

Voice2Txt is a modern Android application designed to convert audio files into text using cutting-edge **Generative AI (GenAI)** technology. Powered by Google's ML Kit, it provides high-accuracy, on-device transcription with a sleek, responsive user interface.

## Key Features
- ✨ **GenAI Speech Recognition:** Leverages advanced AI models for superior transcription precision.
- 📂 **Audio File Processing:** Seamlessly import and convert audio files from your device (includes built-in PCM conversion).
- ⚡ **Real-time Results:** View transcription progress with live partial results.
- 📱 **Material 3 Design:** A beautiful, modern UI built entirely with **Jetpack Compose**.
- 🔍 **Engine Diagnostics:** In-app dashboard to monitor AI engine status and device compatibility.

## Tech Stack
- **Language:** [Kotlin](https://kotlinlang.org/)
- **UI Framework:** [Jetpack Compose](https://developer.android.com/jetpack/compose)
- **Architecture:** MVVM (Model-View-ViewModel)
- **AI/ML:** [Google ML Kit GenAI Speech](https://developers.google.com/ml-kit)
- **Concurrency:** Kotlin Coroutines & Flow

## Getting Started
1. Clone this repository:
   ```bash
   git clone https://github.com/YOUR_USERNAME/Voice2Txt.git
   ```
2. Open the project in **Android Studio (Ladybug or newer)**.
3. Sync the project with Gradle files.
4. Run the app on a device or emulator with **Android 14 (API 34)** or higher.

## Project Structure
- `ui/`: Compose screens, themes, and ViewModels.
- `engine/`: Implementation of the GenAI Speech transcription logic.
- `audio/`: Utilities for audio handling and PCM conversion.

## License
This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

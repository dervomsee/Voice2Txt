# Rules for Android Studio Projects

- **Code & Comments**: Always use English for all code elements (classes, methods, variables) and comments.
- **Strings**: Never hardcode user-facing strings. Use Android's resource system (`strings.xml`).
- **Localization**: Every string must be provided in at least German (DE) and English (EN).
- **Tech Stack**: Use Kotlin as the primary language and Jetpack Compose for the UI. Use Media3 or AudioRecord for audio handling, and WorkManager for background tasks to prevent GrapheneOS from killing processes.
- **Privacy & Ecosystem**: Strictly avoid Google Play Services and other Google dependencies. The app must be fully compatible with GrapheneOS and adhere to F-Droid inclusion rules. Core functionality (STT) must run entirely offline.
- **Permissions**: Implement strict runtime permissions. Request the microphone permission only when immediately needed. Avoid internet permissions unless explicitly required for the initial model download.
- **AI Models & Binary Blobs**: Never commit large model binaries (`.bin`, `.onnx`) to the git repository. Either implement an open-source downloader for fetching models at first launch, or include only tiny models (< 100MB) directly in the `assets` folder.

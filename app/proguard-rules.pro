# Whisper JNI preservation
-keep class de.dervomsee.voice2txt.whisper.WhisperLib {
    native <methods>;
}

-keep interface de.dervomsee.voice2txt.whisper.WhisperLib$WhisperProgressCallback {
    void onProgress(int);
}

-keep class * implements de.dervomsee.voice2txt.whisper.WhisperLib$WhisperProgressCallback {
    void onProgress(int);
}

# Keep the ViewModel and its state
-keepclassmembers class de.dervomsee.voice2txt.ui.MainViewModel {
    private *** whisperContext;
}

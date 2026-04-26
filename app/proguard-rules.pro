# Whisper JNI preservation
-keep class de.dervomsee.voice2txt.whisper.WhisperLib {
    native <methods>;
}

# Keep the ViewModel and its state
-keepclassmembers class de.dervomsee.voice2txt.ui.MainViewModel {
    private *** whisperContext;
}

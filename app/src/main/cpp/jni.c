#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <stdlib.h>
#include <string.h>
#include "whisper.h"
#include "ggml.h"

#ifdef GGML_USE_VULKAN
#include "ggml-vulkan.h"
#endif

#define TAG "Voice2Txt-JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

static bool g_abort_transcription = false;

bool jni_whisper_abort_callback(void * user_data) {
    return g_abort_transcription;
}

struct whisper_callback_user_data {
    JNIEnv *env;
    jobject callback_obj;
    jmethodID on_progress_mid;
    jmethodID on_new_segment_mid;
};

void jni_whisper_progress_callback(struct whisper_context * ctx, struct whisper_state * state, int progress, void * user_data) {
    struct whisper_callback_user_data *data = (struct whisper_callback_user_data *)user_data;
    if (data && data->callback_obj && data->on_progress_mid) {
        (*data->env)->CallVoidMethod(data->env, data->callback_obj, data->on_progress_mid, progress);
    }
}

void jni_whisper_new_segment_callback(struct whisper_context * ctx, struct whisper_state * state, int n_new, void * user_data) {
    struct whisper_callback_user_data *data = (struct whisper_callback_user_data *)user_data;
    if (data && data->callback_obj && data->on_new_segment_mid) {
        int n_segments = whisper_full_n_segments_from_state(state);
        whisper_token eot_id = whisper_token_eot(ctx);

        for (int i = n_segments - n_new; i < n_segments; i++) {
            int n_tokens = whisper_full_n_tokens_from_state(state, i);

            // First count valid non-special tokens
            int n_valid_tokens = 0;
            for (int j = 0; j < n_tokens; j++) {
                if (whisper_full_get_token_id_from_state(state, i, j) < eot_id) {
                    n_valid_tokens++;
                }
            }

            jclass string_class = (*data->env)->FindClass(data->env, "java/lang/String");
            jobjectArray jtokens = (*data->env)->NewObjectArray(data->env, n_valid_tokens, string_class, NULL);

            jfloatArray jprobs = (*data->env)->NewFloatArray(data->env, n_valid_tokens);
            jfloat *probs = (*data->env)->GetFloatArrayElements(data->env, jprobs, NULL);

            int valid_idx = 0;
            for (int j = 0; j < n_tokens; j++) {
                if (whisper_full_get_token_id_from_state(state, i, j) < eot_id) {
                    const char * token_text = whisper_full_get_token_text_from_state(ctx, state, i, j);
                    jstring jtoken_text = (*data->env)->NewStringUTF(data->env, token_text);
                    (*data->env)->SetObjectArrayElement(data->env, jtokens, valid_idx, jtoken_text);
                    (*data->env)->DeleteLocalRef(data->env, jtoken_text);

                    probs[valid_idx] = whisper_full_get_token_p_from_state(state, i, j);
                    valid_idx++;
                }
            }

            (*data->env)->ReleaseFloatArrayElements(data->env, jprobs, probs, 0);
            (*data->env)->CallVoidMethod(data->env, data->callback_obj, data->on_new_segment_mid, jtokens, jprobs);

            (*data->env)->DeleteLocalRef(data->env, jtokens);
            (*data->env)->DeleteLocalRef(data->env, jprobs);
            (*data->env)->DeleteLocalRef(data->env, string_class);
        }
    }
}

JNIEXPORT jlong JNICALL
Java_de_dervomsee_voice2txt_whisper_WhisperLib_initContext(
        JNIEnv *env, jobject thiz, jstring model_path_str, jboolean use_gpu) {
    const char *model_path_chars = (*env)->GetStringUTFChars(env, model_path_str, NULL);

    struct whisper_context_params params = whisper_context_default_params();

    #ifdef NDEBUG
    LOGI("Build Type: RELEASE (Optimized)");
    #else
    LOGW("Build Type: DEBUG (Unoptimized) - Performance will be poor!");
    #endif

    LOGI("Whisper System Info: %s", whisper_print_system_info());

    if (use_gpu) {
        params.use_gpu = true;
        LOGI("Initializing with GPU (Vulkan) support requested. GGML_USE_VULKAN is defined.");
    } else {
        params.use_gpu = false;
        LOGI("Initializing with CPU only");
    }

    struct whisper_context *context = whisper_init_from_file_with_params(model_path_chars, params);

    if (context == NULL) {
        LOGE("Failed to initialize whisper context!");
    } else {
        LOGI("Whisper context initialized successfully");
    }

    (*env)->ReleaseStringUTFChars(env, model_path_str, model_path_chars);
    return (jlong) context;
}

JNIEXPORT void JNICALL
Java_de_dervomsee_voice2txt_whisper_WhisperLib_freeContext(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    whisper_free(context);
}

JNIEXPORT jint JNICALL
Java_de_dervomsee_voice2txt_whisper_WhisperLib_fullTranscribe(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint num_threads, jfloatArray audio_data, jstring lang_str, jobject callback) {
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    jfloat *audio_data_arr = (*env)->GetFloatArrayElements(env, audio_data, NULL);
    const jsize audio_data_length = (*env)->GetArrayLength(env, audio_data);
    const char *lang_chars = (*env)->GetStringUTFChars(env, lang_str, NULL);

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = num_threads;
    params.print_realtime = false;
    params.print_progress = false;
    params.print_special = false;
    params.language = lang_chars;

    g_abort_transcription = false;
    params.abort_callback = jni_whisper_abort_callback;
    params.abort_callback_user_data = NULL;

    struct whisper_callback_user_data callback_data = {0};
    if (callback != NULL) {
        jclass callback_class = (*env)->GetObjectClass(env, callback);
        callback_data.env = env;
        callback_data.callback_obj = callback;
        callback_data.on_progress_mid = (*env)->GetMethodID(env, callback_class, "onProgress", "(I)V");
        callback_data.on_new_segment_mid = (*env)->GetMethodID(env, callback_class, "onNewSegment", "([Ljava/lang/String;[F)V");

        if (callback_data.on_progress_mid == NULL) {
            LOGW("Failed to find onProgress method ID. ProGuard might have obfuscated it.");
            if ((*env)->ExceptionCheck(env)) {
                (*env)->ExceptionClear(env);
            }
        } else {
            params.progress_callback = jni_whisper_progress_callback;
            params.progress_callback_user_data = &callback_data;
        }

        if (callback_data.on_new_segment_mid == NULL) {
            LOGW("Failed to find onNewSegment method ID. ProGuard might have obfuscated it.");
            if ((*env)->ExceptionCheck(env)) {
                (*env)->ExceptionClear(env);
            }
        } else {
            params.new_segment_callback = jni_whisper_new_segment_callback;
            params.new_segment_callback_user_data = &callback_data;
        }
    }

    int result = whisper_full(context, params, audio_data_arr, audio_data_length);

    (*env)->ReleaseStringUTFChars(env, lang_str, lang_chars);
    (*env)->ReleaseFloatArrayElements(env, audio_data, audio_data_arr, JNI_ABORT);
    return result;
}

JNIEXPORT void JNICALL
Java_de_dervomsee_voice2txt_whisper_WhisperLib_abortTranscription(
        JNIEnv *env, jobject thiz) {
    g_abort_transcription = true;
}

JNIEXPORT jint JNICALL
Java_de_dervomsee_voice2txt_whisper_WhisperLib_getTextSegmentCount(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    return whisper_full_n_segments(context);
}

JNIEXPORT jstring JNICALL
Java_de_dervomsee_voice2txt_whisper_WhisperLib_getTextSegment(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    int n_tokens = whisper_full_n_tokens(context, index);
    whisper_token eot_id = whisper_token_eot(context);

    // Calculate total length for filtered text
    size_t total_len = 0;
    for (int i = 0; i < n_tokens; i++) {
        if (whisper_full_get_token_id(context, index, i) < eot_id) {
            total_len += strlen(whisper_full_get_token_text(context, index, i));
        }
    }

    char *filtered_text = (char *)malloc(total_len + 1);
    if (filtered_text == NULL) {
        return (*env)->NewStringUTF(env, "");
    }
    filtered_text[0] = '\0';
    for (int i = 0; i < n_tokens; i++) {
        if (whisper_full_get_token_id(context, index, i) < eot_id) {
            strcat(filtered_text, whisper_full_get_token_text(context, index, i));
        }
    }

    jstring result = (*env)->NewStringUTF(env, filtered_text);
    free(filtered_text);
    return result;
}

JNIEXPORT jstring JNICALL
Java_de_dervomsee_voice2txt_whisper_WhisperLib_getSystemInfo(
        JNIEnv *env, jobject thiz
) {
    return (*env)->NewStringUTF(env, whisper_print_system_info());
}

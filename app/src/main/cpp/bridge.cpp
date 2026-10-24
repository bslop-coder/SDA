// JNI bridge to stable-diffusion.cpp. This is the ONLY file that touches the sd.cpp C API,
// so API drift in a newer sd.cpp commit means editing this file and nothing else.
#include <jni.h>
#include <android/log.h>
#include <sched.h>
#include <cstdlib>
#include <string>
#include <vector>
#include "stable-diffusion.h"

#define TAG "AnimaBridge"

static JavaVM* g_vm = nullptr;
static jobject g_listener = nullptr;
static jmethodID g_mProgress = nullptr, g_mLog = nullptr, g_mImage = nullptr;

struct Env {
    JNIEnv* env = nullptr;
    bool attached = false;
    Env() {
        if (g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
            g_vm->AttachCurrentThread(&env, nullptr);
            attached = true;
        }
    }
    ~Env() { if (attached) g_vm->DetachCurrentThread(); }
};

static void log_cb(enum sd_log_level_t level, const char* text, void*) {
    __android_log_print(ANDROID_LOG_INFO, TAG, "%s", text);
    if (!g_listener) return;
    Env e;
    jstring s = e.env->NewStringUTF(text);
    e.env->CallVoidMethod(g_listener, g_mLog, (jint)level, s);
    e.env->DeleteLocalRef(s);
}

static void progress_cb(int step, int steps, float time, void*) {
    if (!g_listener) return;
    Env e;
    e.env->CallVoidMethod(g_listener, g_mProgress, (jint)step, (jint)steps, (jfloat)time);
}

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

static std::string S(JNIEnv* env, jstring s) {
    if (!s) return "";
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string r(c);
    env->ReleaseStringUTFChars(s, c);
    return r;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_animalocal_engine_SdNative_generate(
        JNIEnv* env, jobject /*thiz*/,
        jstring jDiff, jstring jLlm, jstring jVae,
        jstring jPrompt, jstring jNeg, jstring jSampler,
        jint width, jint height, jint steps, jfloat cfg, jlong seed,
        jboolean vaeTiling, jboolean flashAttn,
        jint threads, jint affinityMask, jint offloadMode,
        jobjectArray jLoraPaths, jfloatArray jLoraStrengths,
        jobject listener) {

    g_listener = env->NewGlobalRef(listener);
    jclass lc = env->GetObjectClass(listener);
    g_mProgress = env->GetMethodID(lc, "onProgress", "(IIF)V");
    g_mLog = env->GetMethodID(lc, "onLog", "(ILjava/lang/String;)V");
    g_mImage = env->GetMethodID(lc, "onImage", "(II[B)V");
    sd_set_log_callback(log_cb, nullptr);
    sd_set_progress_callback(progress_cb, nullptr);

    std::string diff = S(env, jDiff), llm = S(env, jLlm), vae = S(env, jVae);
    std::string prompt = S(env, jPrompt), neg = S(env, jNeg), sampler = S(env, jSampler);

    // LoRAs: collect all strings first so c_str() pointers stay valid.
    std::vector<std::string> lpaths;
    std::vector<float> lstr;
    jsize ln = jLoraPaths ? env->GetArrayLength(jLoraPaths) : 0;
    if (ln > 0) {
        jfloat* fs = env->GetFloatArrayElements(jLoraStrengths, nullptr);
        for (jsize i = 0; i < ln; i++) {
            jstring js = (jstring)env->GetObjectArrayElement(jLoraPaths, i);
            lpaths.push_back(S(env, js));
            lstr.push_back(fs[i]);
            env->DeleteLocalRef(js);
        }
        env->ReleaseFloatArrayElements(jLoraStrengths, fs, JNI_ABORT);
    }
    std::vector<sd_lora_t> loras;
    for (size_t i = 0; i < lpaths.size(); i++) {
        sd_lora_t l = {};
        l.is_directory = false;
        l.path = lpaths[i].c_str();
        l.multiplier = lstr[i];
        loras.push_back(l);
    }

    // Pin this thread (and the ggml worker threads it spawns) to chosen cores, e.g. 0xC0 = cpu6+cpu7.
    if (affinityMask != 0) {
        cpu_set_t set;
        CPU_ZERO(&set);
        for (int i = 0; i < 32; i++) if (affinityMask & (1 << i)) CPU_SET(i, &set);
        sched_setaffinity(0, sizeof(set), &set);
    }

    int rc = 0;
    sd_ctx_params_t cp;
    sd_ctx_params_init(&cp);
    cp.diffusion_model_path = diff.c_str();
    cp.llm_path = llm.c_str();
    cp.vae_path = vae.c_str();
    cp.n_threads = threads;
    cp.diffusion_flash_attn = flashAttn;
    cp.vae_decode_only = true;
    cp.enable_mmap = true;
    // offloadMode: 0 = keep everything loaded for the whole run,
    //              1 = free each stage's weights right after it finishes (text encoder -> DiT -> VAE),
    //              2 = like 1, and run the VAE on CPU.
    cp.free_params_immediately = offloadMode >= 1;
#ifdef ANIMA_VULKAN_BUILD
    cp.offload_params_to_cpu = offloadMode >= 1;
    cp.keep_vae_on_cpu = offloadMode >= 2;
#endif

    sd_ctx_t* ctx = new_sd_ctx(&cp);
    if (!ctx) {
        rc = 1;
    } else {
        sd_img_gen_params_t ip;
        sd_img_gen_params_init(&ip);
        ip.prompt = prompt.c_str();
        ip.negative_prompt = neg.c_str();
        ip.width = width;
        ip.height = height;
        ip.seed = seed;
        ip.batch_count = 1;
        ip.loras = loras.empty() ? nullptr : loras.data();
        ip.lora_count = (uint32_t)loras.size();
        ip.sample_params.sample_steps = steps;
        ip.sample_params.guidance.txt_cfg = cfg;
        enum sample_method_t m = str_to_sample_method(sampler.c_str());
        if (m != SAMPLE_METHOD_COUNT) ip.sample_params.sample_method = m;
        ip.vae_tiling_params.enabled = vaeTiling;

        sd_image_t* imgs = generate_image(ctx, &ip);
        if (!imgs || !imgs[0].data) {
            rc = 2;
        } else {
            int w = (int)imgs[0].width, h = (int)imgs[0].height, ch = (int)imgs[0].channel;
            std::vector<jbyte> buf((size_t)w * h * 3);
            for (int i = 0; i < w * h; i++)
                for (int c = 0; c < 3; c++) buf[(size_t)i * 3 + c] = (jbyte)imgs[0].data[(size_t)i * ch + c];
            jbyteArray arr = env->NewByteArray((jsize)buf.size());
            env->SetByteArrayRegion(arr, 0, (jsize)buf.size(), buf.data());
            env->CallVoidMethod(listener, g_mImage, (jint)w, (jint)h, arr);
            env->DeleteLocalRef(arr);
            free(imgs[0].data);
        }
        free(imgs);
        free_sd_ctx(ctx);
    }

    env->DeleteGlobalRef(g_listener);
    g_listener = nullptr;
    return rc;
}

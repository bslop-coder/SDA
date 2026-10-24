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

// sd_image_generation_result_t::items has changed shape between sd.cpp versions.
// Accept either an element that wraps an sd_image_t in a member called `image`, or a bare sd_image_t.
template <typename T>
static auto as_image(const T& r, int) -> decltype((const sd_image_t&)r.image) { return r.image; }
static const sd_image_t& as_image(const sd_image_t& i, long) { return i; }

extern "C" JNIEXPORT jint JNICALL
Java_com_example_animalocal_engine_SdNative_generate(
        JNIEnv* env, jobject /*thiz*/,
        jstring jDiff, jstring jLlm, jstring jVae,
        jstring jPrompt, jstring jNeg, jstring jSampler,
        jint width, jint height, jint steps, jfloat cfg, jlong seed,
        jboolean vaeTiling, jboolean flashAttn,
        jint threads, jint affinityMask, jint offloadMode, jstring jBackend,
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
    std::string backend = S(env, jBackend);

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
        l.is_high_noise = false;
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
    cp.enable_mmap = true;
    // backend: empty = sd.cpp default; otherwise e.g. "cpu", "gpu", "diffusion=gpu,te=cpu,vae=cpu".
    cp.backend = backend.empty() ? nullptr : backend.c_str();
    // offloadMode: 0 = sd.cpp default placement,
    //              1 = every module's weights reloaded from the model file on demand and released after use,
    //              2 = only the text encoder's weights are reloaded on demand (DiT and VAE stay resident).
    cp.params_backend = (offloadMode == 1) ? "disk" : (offloadMode == 2) ? "te=disk" : nullptr;

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

        sd_image_generation_result_t* res = nullptr;
        bool ok = generate_image(ctx, &ip, &res);
        if (!ok || !res || res->count < 1 || !res->items) {
            rc = 2;
        } else {
            const sd_image_t& im = as_image(res->items[0], 0);
            if (!im.data) {
                rc = 2;
            } else {
                int w = (int)im.width, h = (int)im.height, ch = (int)im.channel;
                std::vector<jbyte> buf((size_t)w * h * 3);
                for (int i = 0; i < w * h; i++)
                    for (int c = 0; c < 3; c++) buf[(size_t)i * 3 + c] = (jbyte)im.data[(size_t)i * ch + c];
                jbyteArray arr = env->NewByteArray((jsize)buf.size());
                env->SetByteArrayRegion(arr, 0, (jsize)buf.size(), buf.data());
                env->CallVoidMethod(listener, g_mImage, (jint)w, (jint)h, arr);
                env->DeleteLocalRef(arr);
            }
        }
        if (res) free_sd_image_generation_result(res);
        free_sd_ctx(ctx);
    }

    env->DeleteGlobalRef(g_listener);
    g_listener = nullptr;
    return rc;
}

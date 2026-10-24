# Architecture (paste this at the start of every vibe-coding session)

Rules that keep new features from breaking old ones:

1. **UI never touches native code.** UI -> `MainViewModel` -> `ImageEngine` (interface) -> `RemoteEngine` -> Messenger IPC -> `EngineService` (process `:engine`) -> `SdNative` (JNI) -> `bridge.cpp` -> stable-diffusion.cpp.
2. **`bridge.cpp` is the only file that includes `stable-diffusion.h`.** sd.cpp API drift = edit this file only.
3. **One request object.** `GenerationRequest` carries all per-image options. New option = new field with a default + one line in `toBundle()`/`fromBundle()` + one line in `bridge.cpp`/`SdNative`.
4. **Settings** are JSON with per-field defaults (`Prefs.kt`). Adding a setting never invalidates saved data.
5. **Models are data.** `assets/models.json` lists downloads; files imported into a role folder appear automatically. No code change to add a model/quant.
6. **Engine process is disposable.** One job per process; it kills itself afterwards (clean native heap). Cancel = kill process. A native crash/OOM shows as an error, not a dead app.
7. **sd.cpp is a pinned git submodule.** Upgrade deliberately: `cd app/src/main/cpp/third_party/stable-diffusion.cpp && git fetch && git checkout <commit>`, commit the new pointer, run the smoke test.
8. **Smoke test** (Settings -> Tools): 256x256, 1 step, seed 42, checks output is not blank. Run after every change.
9. One feature per change; commit when it works.

Offload modes (`offloadMode`) map to sd.cpp's `params_backend`: 0 = sd.cpp default placement; 1 = every module's weights are reloaded from the model file on demand and released after use (`disk`); 2 = only the text encoder is reloaded on demand (`te=disk`), DiT and VAE stay resident. The `backend` setting (blank = auto) is passed straight through, e.g. `cpu`, `gpu`, `diffusion=gpu,te=cpu,vae=cpu`. The log prints RSS/peak at stage boundaries so you can verify weights really get freed.

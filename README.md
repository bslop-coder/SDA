# Anima Local

On-device Anima (2B DiT) image generation for Android via stable-diffusion.cpp. Built by GitHub Actions; installs as a sideloaded APK.

## Setup (Termux)

```sh
pkg install git unzip
cd ~ && unzip /sdcard/Download/AnimaLocal.zip && cd AnimaLocal
git init -b main
git submodule add https://github.com/leejet/stable-diffusion.cpp app/src/main/cpp/third_party/stable-diffusion.cpp
git submodule update --init --recursive
git add -A && git commit -m "Initial skeleton"
```

Create an EMPTY repo on github.com (no README), then:

```sh
git remote add origin https://github.com/<you>/AnimaLocal.git
git push -u origin main     # username + a personal access token (repo scope) as password
```

Actions builds two APKs: `anima-cpu` and `anima-vulkan` (installable side by side). Download them from the run's Artifacts section on github.com, unzip, install.

## First run
Models -> Download the four files (diffusion Q4_0, text encoder, VAE), then Settings -> Smoke test, then Generate.

If the build fails, copy the first `error:` lines from the Actions log and send them back. Most likely culprit: `bridge.cpp` vs. the sd.cpp commit you pinned.

#!/bin/bash
# 编译 media3 FFmpeg 音频解码器 (libffmpegJNI.so, 仅 arm64-v8a)
# 参考: androidx/media libraries/decoder_ffmpeg README + jellyfin-androidx-media build.sh
set -eu

MEDIA_VER="1.11.0"
FFMPEG_BRANCH="release/6.0"
WORK="/tmp/ffmpeg_build"
NDK_PATH="${ANDROID_NDK_HOME:-${ANDROID_HOME}/ndk/26.1.10909125}"

echo "==> NDK: ${NDK_PATH}"
if [ ! -d "${NDK_PATH}/toolchains/llvm/prebuilt/linux-x86_64" ]; then
    echo "NDK toolchain not found, installing..."
    sdkmanager --install "ndk;26.1.10909125"
fi
HOST_PLATFORM="linux-x86_64"
TOOLCHAIN_PREFIX="${NDK_PATH}/toolchains/llvm/prebuilt/${HOST_PLATFORM}/bin"

# 1. 获取 media3 decoder_ffmpeg 模块
rm -rf "${WORK}"
mkdir -p "${WORK}"
cd "${WORK}"
git clone --depth 1 --branch "${MEDIA_VER}" https://github.com/androidx/media.git media
FFMPEG_MODULE_PATH="${WORK}/media/libraries/decoder_ffmpeg/src/main"

# 2. 获取 FFmpeg 源码
git clone --depth 1 --branch "${FFMPEG_BRANCH}" https://github.com/FFmpeg/FFmpeg.git "${FFMPEG_MODULE_PATH}/jni/ffmpeg"
cd "${FFMPEG_MODULE_PATH}/jni/ffmpeg"

# 3. 编译 FFmpeg 静态库 (仅 arm64-v8a)
JOBS="$(nproc)"
ENABLED_DECODERS="vorbis flac alac pcm_mulaw pcm_alaw mp3 aac ac3 eac3 dca mlp truehd"
COMMON_OPTIONS="
    --target-os=android
    --enable-static
    --disable-shared
    --disable-doc
    --disable-programs
    --disable-everything
    --disable-avdevice
    --disable-avformat
    --disable-swscale
    --disable-postproc
    --disable-avfilter
    --disable-symver
    --enable-swresample
    --extra-ldexeflags=-pie
    --disable-v4l2-m2m
    --disable-vulkan
    "
for decoder in ${ENABLED_DECODERS}; do
    COMMON_OPTIONS="${COMMON_OPTIONS} --enable-decoder=${decoder}"
done

./configure \
    --libdir=android-libs/arm64-v8a \
    --arch=aarch64 \
    --cpu=armv8-a \
    --cross-prefix="${TOOLCHAIN_PREFIX}/aarch64-linux-android21-" \
    --nm="${TOOLCHAIN_PREFIX}/llvm-nm" \
    --ar="${TOOLCHAIN_PREFIX}/llvm-ar" \
    --ranlib="${TOOLCHAIN_PREFIX}/llvm-ranlib" \
    --strip="${TOOLCHAIN_PREFIX}/llvm-strip" \
    ${COMMON_OPTIONS}
make -j"${JOBS}"
make install-libs

echo "==> FFmpeg static libs:"
ls -la android-libs/arm64-v8a/

# 4. CMake 编译 ffmpeg_jni.cc -> libffmpegJNI.so
cd "${FFMPEG_MODULE_PATH}/jni"
CMAKE_BIN="cmake"
if [ -x "${NDK_PATH}/cmake/3.22.1/bin/cmake" ]; then
    CMAKE_BIN="${NDK_PATH}/cmake/3.22.1/bin/cmake"
fi
rm -rf build
"${CMAKE_BIN}" -S . -B build \
    -DCMAKE_TOOLCHAIN_FILE="${NDK_PATH}/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-21 \
    -DCMAKE_BUILD_TYPE=Release
"${CMAKE_BIN}" --build build -j"${JOBS}"

echo "==> libffmpegJNI.so:"
ls -la build/libffmpegJNI.so

# 5. 拷贝到 app jniLibs
mkdir -p "${GITHUB_WORKSPACE}/app/src/main/jniLibs/arm64-v8a"
cp build/libffmpegJNI.so "${GITHUB_WORKSPACE}/app/src/main/jniLibs/arm64-v8a/"
echo "==> Done: app/src/main/jniLibs/arm64-v8a/libffmpegJNI.so ($(du -h build/libffmpegJNI.so | cut -f1))"

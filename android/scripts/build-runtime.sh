#!/usr/bin/env bash
# Builds the game runtime's native libraries for Android with the NDK:
#   libgl4es.so        OpenGL 2.1 over GLES 2 (ptitSeb/gl4es, MIT)
#   libmobileglues.so  OpenGL 4.x over GLES 3.2 (MobileGL-Dev/MobileGlues, LGPL-2.1) — best effort
#   libopenal.so       OpenAL Soft (LGPL-2.0)
#   liblwjgl*.so       LWJGL 3 natives (BSD) built from source, linked with libffi
#   libfreetype.so     FreeType + LWJGL's binding — best effort
# Usage: build-runtime.sh <out-dir> [abi]
set -uo pipefail

OUT=$(realpath -m "${1:?out dir}")
ABI=${2:-arm64-v8a}
API=26
LWJGL_TAG=${LWJGL_TAG:-3.3.3}
NDK=${ANDROID_NDK_HOME:-${ANDROID_NDK:-}}
[ -d "$NDK" ] || { echo "ANDROID_NDK_HOME not set"; exit 1; }
TC=$NDK/toolchains/llvm/prebuilt/linux-x86_64
case $ABI in
  arm64-v8a) TRIPLE=aarch64-linux-android ;;
  x86_64) TRIPLE=x86_64-linux-android ;;
  *) echo "unsupported ABI $ABI"; exit 1 ;;
esac
CC="$TC/bin/${TRIPLE}${API}-clang"
AR="$TC/bin/llvm-ar"
STRIP="$TC/bin/llvm-strip"
CMAKE_ANDROID=(-DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" -DANDROID_ABI="$ABI" -DANDROID_PLATFORM=android-$API -DCMAKE_BUILD_TYPE=Release)
WORK=$(realpath -m "${WORK:-build-runtime}")
mkdir -p "$OUT" "$WORK"
cd "$WORK"
STATUS=()
ok() { STATUS+=("✓ $1"); echo "::notice::built $1"; }
fail() { STATUS+=("✗ $1"); echo "::warning::$1 failed"; }

clone() { # repo dir [ref]
  [ -d "$2" ] && return 0
  if [ -n "${3:-}" ]; then git clone -q --depth 1 --branch "$3" "https://github.com/$1" "$2"
  else git clone -q --depth 1 "https://github.com/$1" "$2"; fi
}

# ---------------------------------------------------------------- gl4es
build_gl4es() {
  echo "== gl4es"
  clone ptitSeb/gl4es gl4es || return 1
  ls gl4es
  # gl4es ships an Android.mk for the NDK (what other Android launchers use)
  if [ -f gl4es/Android.mk ]; then
    ( cd gl4es && "$NDK/ndk-build" NDK_PROJECT_PATH=. APP_BUILD_SCRIPT=./Android.mk APP_ABI="$ABI" APP_PLATFORM=android-$API -j"$(nproc)" > ../gl4es.log 2>&1 ) ||
      { tail -40 gl4es.log; echo "ndk-build failed, trying CMake"; }
  fi
  local so
  so=$(find gl4es/libs gl4es/obj -name 'libGL*.so' -type f 2>/dev/null | head -1)
  if [ -z "$so" ]; then
    # Android has no librt (it is part of libc): give the linker an empty one
    mkdir -p fakelibs && printf '!<arch>\n' > fakelibs/librt.a
    cmake -S gl4es -B gl4es/build "${CMAKE_ANDROID[@]}" -DNOX11=ON -DNOEGL=OFF -DGBM=OFF -DSTATICLIB=OFF -DUSE_CLOCK=ON \
      -DCMAKE_SHARED_LINKER_FLAGS="-L$WORK/fakelibs" > gl4es-cmake.log 2>&1 &&
      cmake --build gl4es/build -j"$(nproc)" >> gl4es-cmake.log 2>&1 || { tail -60 gl4es-cmake.log; return 1; }
    so=$(find gl4es -name 'libGL.so*' -type f | head -1)
  fi
  [ -n "$so" ] || { find gl4es -name '*.so*' | head; tail -20 gl4es-cmake.log 2>/dev/null; return 1; }
  cp "$so" "$OUT/libgl4es.so"
}

# ---------------------------------------------------------------- MobileGlues
build_mobileglues() {
  clone MobileGL-Dev/MobileGlues MobileGlues || return 1
  ( cd MobileGlues && git submodule update --init --recursive --depth 1 >/dev/null 2>&1 || true )
  local dir
  dir=$(grep -ril --include=CMakeLists.txt 'mobileglues' MobileGlues | xargs -r -n1 dirname | awk '{ print length, $0 }' | sort -n | head -1 | cut -d' ' -f2-)
  [ -n "$dir" ] || return 1
  echo "MobileGlues CMake project: $dir"
  cmake -S "$dir" -B MobileGlues/build "${CMAKE_ANDROID[@]}" -DANDROID_STL=c++_shared >/dev/null || return 1
  cmake --build MobileGlues/build -j"$(nproc)" > mobileglues.log 2>&1 || { tail -40 mobileglues.log; return 1; }
  local so
  so=$(find MobileGlues/build -iname 'libmobileglues*.so' -type f | head -1)
  [ -n "$so" ] || return 1
  cp "$so" "$OUT/libmobileglues.so"
  # its C++ runtime
  [ -f "$OUT/libc++_shared.so" ] || cp "$TC/sysroot/usr/lib/$TRIPLE/libc++_shared.so" "$OUT/"
}

# ---------------------------------------------------------------- OpenAL Soft
build_openal() {
  clone kcat/openal-soft openal-soft 1.23.1 || return 1
  cmake -S openal-soft -B openal-soft/build "${CMAKE_ANDROID[@]}" -DALSOFT_UTILS=OFF -DALSOFT_EXAMPLES=OFF -DALSOFT_TESTS=OFF \
    -DALSOFT_BACKEND_OPENSL=ON -DALSOFT_REQUIRE_OPENSL=ON -DALSOFT_EMBED_HRTF_DATA=ON -DANDROID_STL=c++_static >/dev/null || return 1
  cmake --build openal-soft/build -j"$(nproc)" > openal.log 2>&1 || { tail -40 openal.log; return 1; }
  cp openal-soft/build/libopenal.so "$OUT/libopenal.so"
}

# ---------------------------------------------------------------- libffi (static, for LWJGL core)
build_libffi() {
  [ -f ffi/lib/libffi.a ] && return 0
  curl -fsSL https://github.com/libffi/libffi/releases/download/v3.4.6/libffi-3.4.6.tar.gz | tar xz || return 1
  ( cd libffi-3.4.6 && CC="$CC" AR="$AR" RANLIB="$TC/bin/llvm-ranlib" CFLAGS="-O2 -fPIC" \
      ./configure --host="$TRIPLE" --prefix="$WORK/ffi" --disable-shared --enable-static --with-pic --disable-docs >/dev/null &&
    make -j"$(nproc)" >/dev/null && make install >/dev/null ) || return 1
}

# ---------------------------------------------------------------- LWJGL natives
LW=lwjgl3/modules/lwjgl
CFLAGS_LW=(-O2 -fPIC -fvisibility=hidden -D_GNU_SOURCE -DLWJGL_LINUX -Wno-everything)

# compile every C file of a module (skipping the ones that need desktop-only headers) and link them
lwjgl_module() { # module libname [extra link args…]
  local mod=$1 lib=$2; shift 2
  local objs=() skipped=0
  mkdir -p "obj/$mod"
  local incs=(-I"$LW/core/src/main/c" -I"$LW/core/src/main/c/linux" -I"$LW/core/src/generated/c" -I"$WORK/ffi/include"
              -I"$LW/$mod/src/main/c" -I"$LW/$mod/src/generated/c" -I"$LW/$mod/src/main/c/linux")
  while IFS= read -r f; do
    local o="obj/$mod/$(echo "$f" | tr '/' '_').o"
    if "$CC" "${CFLAGS_LW[@]}" "${incs[@]}" ${EXTRA_CFLAGS:-} -c "$f" -o "$o" 2>"$o.log"; then objs+=("$o")
    else skipped=$((skipped + 1)); echo "  skip $f: $(head -c 300 "$o.log" | tr '\n' ' ')"; fi
  done < <(find "$LW/$mod/src" -name '*.c' \( -path '*/c/*' \) ! -path '*/windows/*' ! -path '*/macos/*' ! -path '*/macosx/*' ! -path '*/liburing/*' \
             ! -path '*/stb/src/main/c/*' | sort)
  echo "lwjgl $mod: ${#objs[@]} files compiled, $skipped skipped"
  [ ${#objs[@]} -gt 0 ] || return 1
  "$CC" -shared -o "$OUT/$lib" "${objs[@]}" "$@" -ldl -lm -llog -Wl,--no-undefined 2>&1 | tail -30
  [ -f "$OUT/$lib" ] || return 1
}

# LWJGL's LibFFI binding returns the ABI constants of every platform; define
# the ones this platform's libffi lacks (as -1, like LWJGL's own builds do)
ffi_compat() {
  local f="$LW/core/src/generated/c/org_lwjgl_system_libffi_LibFFI.c"
  : > "$WORK/ffi_compat.h"
  for id in $(grep -o '(jint)FFI_[A-Z0-9_]*' "$f" | sed 's/(jint)//' | sort -u); do
    printf '#include <ffi.h>\nlong x = (long)%s;\n' "$id" > "$WORK/t.c"
    "$CC" -I"$WORK/ffi/include" -c "$WORK/t.c" -o /dev/null 2>/dev/null || echo "#define $id -1" >> "$WORK/ffi_compat.h"
  done
  echo "libffi compat: $(wc -l < "$WORK/ffi_compat.h") constants"
}

build_lwjgl() {
  clone LWJGL/lwjgl3 lwjgl3 "$LWJGL_TAG" || return 1
  build_libffi || { echo "libffi failed"; return 1; }
  ffi_compat
  if [ ! -d "$LW/core/src/generated" ]; then
    echo "::warning::LWJGL $LWJGL_TAG has no committed generated sources"; ls "$LW/core/src"; return 1
  fi
  EXTRA_CFLAGS="-include $WORK/ffi_compat.h" lwjgl_module core liblwjgl.so "$WORK/ffi/lib/libffi.a" && ok liblwjgl.so || fail liblwjgl.so
  # the callback machinery must be there
  "$TC/bin/llvm-nm" -D "$OUT/liblwjgl.so" | grep -q Java_org_lwjgl_system_libffi_LibFFI_nffi_1call || { echo "::error::liblwjgl.so lacks LibFFI"; return 1; }
  lwjgl_module opengl liblwjgl_opengl.so && ok liblwjgl_opengl.so || fail liblwjgl_opengl.so
  lwjgl_module stb liblwjgl_stb.so && ok liblwjgl_stb.so || fail liblwjgl_stb.so
  lwjgl_module tinyfd liblwjgl_tinyfd.so && ok liblwjgl_tinyfd.so || fail liblwjgl_tinyfd.so
}

# LWJGL's FreeType binding calls the library through function pointers: a plain shared FreeType is enough
build_freetype() {
  clone freetype/freetype freetype VER-2-13-3 || return 1
  cmake -S freetype -B freetype/build "${CMAKE_ANDROID[@]}" -DBUILD_SHARED_LIBS=ON \
    -DFT_DISABLE_ZLIB=ON -DFT_DISABLE_BZIP2=ON -DFT_DISABLE_PNG=ON -DFT_DISABLE_HARFBUZZ=ON -DFT_DISABLE_BROTLI=ON > freetype.log 2>&1 || { tail -30 freetype.log; return 1; }
  cmake --build freetype/build -j"$(nproc)" >> freetype.log 2>&1 || { tail -30 freetype.log; return 1; }
  local so
  so=$(find freetype/build -name 'libfreetype.so*' -type f | head -1)
  [ -n "$so" ] && cp "$so" "$OUT/libfreetype.so"
}

build_gl4es && ok libgl4es.so || fail libgl4es.so
build_openal && ok libopenal.so || fail libopenal.so
build_lwjgl || fail "LWJGL natives"
build_freetype && ok libfreetype.so || fail libfreetype.so
build_mobileglues && ok libmobileglues.so || fail libmobileglues.so

for f in "$OUT"/*.so; do "$STRIP" --strip-unneeded "$f" 2>/dev/null || true; done
echo
echo "Runtime ($ABI):"
printf '  %s\n' "${STATUS[@]}"
ls -la "$OUT"
# the must-haves
for need in libgl4es.so libopenal.so liblwjgl.so liblwjgl_opengl.so liblwjgl_stb.so; do
  [ -f "$OUT/$need" ] || { echo "::error::missing $need"; exit 1; }
done

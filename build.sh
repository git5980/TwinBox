#!/usr/bin/env bash
# TwinBox V2 容器版直构链：aapt2 + ECJ + d8 + apksigner（无 Gradle）
set -euo pipefail

# TwinBox 2.1.23：脚本锚点（必须在任何 cd 之前解析——$0 是相对路径，
# 进到 $OUT 后再解析就错位到 build/ 了）
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

SDK="${ANDROID_SDK:-/home/z/android-sdk}"
BT="$SDK/build-tools/34.0.0"
PLAT="$SDK/platforms/android-34/android.jar"
ECJ="${ECJ_JAR:-/home/z/android-tools/ecj.jar}"
cd "$(dirname "$0")"

PKG="dev.twinbox.app"
OUT=build
KEYSTORE="${KEYSTORE:-$PWD/$OUT/twinbox.keystore}"
STOREPASS="${STOREPASS:-twinbox2026}"

rm -rf "$OUT/classes" "$OUT/dex" "$OUT/so"
mkdir -p "$OUT/classes" "$OUT/dex"

echo "== 1/6 合并 manifest"
python3 merge_manifest.py host-manifest-template.xml ../va2/lib/src/main/AndroidManifest.xml AndroidManifest.xml "$PKG"

mkdir -p build/aidl-java
AIDL="$BT/aidl"
VA2=../va2/lib/src/main/aidl
FRAME="$SDK/platforms/android-34/framework.aidl"
echo "parcelable android.os.RemoteException;" > build/extra.aidl
for f in $(find $VA2 -name "*.aidl"); do
  $AIDL -p"$FRAME" -pbuild/extra.aidl -I "$VA2" -o build/aidl-java "$f" 2>/dev/null || echo "AIDL-FAIL: $f"
done

echo "== 2/6 aapt2 编译资源（宿主+lib 双链）"
"$BT/aapt2" compile --dir res -o "$OUT/res-host.zip"
"$BT/aapt2" compile --dir res-lib -o "$OUT/res-lib.zip"

echo "== 3/6 aapt2 链接"
"$BT/aapt2" link -o "$OUT/base.apk" \
  -I "$PLAT" \
  --manifest AndroidManifest.xml \
  --java "$OUT/gen" \
  --auto-add-overlay \
  "$OUT/res-host.zip" "$OUT/res-lib.zip"

echo "== 4/6 ECJ 编译（third + host + gen）"
find third src-host "$OUT/gen" build/aidl-java -name "*.java" > "$OUT/sources.txt"
echo "源文件数: $(wc -l < "$OUT/sources.txt")"
java -jar "$ECJ" -1.8 -nowarn -encoding UTF-8 \
  -bootclasspath "$PLAT" \
  -classpath "$OUT/base.apk:$PWD/libs/dalvik-dx.jar" \
  -d "$OUT/classes" \
  @"$OUT/sources.txt" 2> "$OUT/ecj-errors.log" || true

echo "== 5/6 d8 出 dex"
find "$OUT/classes" -name "*.class" > "$OUT/classlist.txt"
"$BT/d8" --release --min-api 24 \
  --lib "$PLAT" \
  --output "$OUT/dex" \
  $(cat "$OUT/classlist.txt" | tr '\n' ' ')
ls -la "$OUT/dex/"

echo "== 6/6 打包签名"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
cd "$OUT"
for d in dex/*.dex; do
  zip -q -j unsigned.apk "$d"
done

# TwinBox 2.1.22：打包 native 引擎 .so（libv++_64.so / libv++.so）。
# 没有它 Android 16 上 NativeEngine.<clinit> 就 UnsatisfiedLinkError，IO 重定向和
# 引擎 native hook 全部空转（日志见 README「2.1.22 验证记录」）。
# .so 来自 ../va2 原仓库，具体子目录随仓库结构可能变，这里按 abi+文件名去 find，
# 命中就按标准 entry 名 lib/<abi>/<so> 塞进 APK；一个都没找到只告警不失败。
# TwinBox 2.1.23：探测路径加锚点解析——"../va2" 相对 $OUT（twinbox2/build）
# 指向 twinbox2/va2 并不存在，真实仓库在上两级；以 build.sh 所在目录兜底，
# 从任何 cwd 运行都能命中。
VA2_DIR=""
if [ -d "../va2" ]; then
  VA2_DIR="../va2"
elif [ -d "$SCRIPT_DIR/../va2" ]; then
  VA2_DIR="$SCRIPT_DIR/../va2"
fi
OUT_ABS="$PWD"                 # 此刻 cwd 就是 $OUT
APK_ABS="$PWD/unsigned.apk"
SO_FOUND=0
if [ -n "$VA2_DIR" ]; then
  for ABI in arm64-v8a armeabi-v7a x86_64 x86; do
    for SO in libv++_64.so libv++.so; do
      # 优先 strip 版（libs/），避开 obj/local 未 strip 中间产物（大 ~1MB/ABI）
      SRC=$(find "$VA2_DIR" -path "*/$ABI/$SO" -type f -not -path "*/obj/*" 2>/dev/null | head -1 || true)
      if [ -z "$SRC" ]; then
        SRC=$(find "$VA2_DIR" -path "*/$ABI/$SO" -type f 2>/dev/null | head -1 || true)
      fi
      if [ -n "$SRC" ] && [ -f "$SRC" ]; then
        mkdir -p "$OUT_ABS/so/lib/$ABI"
        cp -f "$SRC" "$OUT_ABS/so/lib/$ABI/$SO"
        ( cd "$OUT_ABS/so" && zip -q -X "$APK_ABS" "lib/$ABI/$SO" ) \
          || echo "ZIP-FAIL: lib/$ABI/$SO"
        echo "  + lib/$ABI/$SO  <= $SRC"
        SO_FOUND=$((SO_FOUND + 1))
      fi
    done
  done
fi
if [ "$SO_FOUND" -eq 0 ]; then
  echo "WARN: 没找到 libv++*_64.so / libv++.so（探测路径 ../va2/**/<abi>/）。"
  echo "WARN: 本次产物不带引擎 native 库：IO 重定向/沙箱 hook 不可用，"
  echo "WARN: 日志会有 UnsatisfiedLinkError + Os.stat ENOENT 噪音（见 README 2.1.22）。"
else
  echo "native .so 打包完成：$SO_FOUND 个"
fi

"$BT/zipalign" -f 4 unsigned.apk aligned.apk
if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -keystore "$KEYSTORE" -storepass "$STOREPASS" \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -alias twinbox -dname "CN=TwinBox" 2>/dev/null
fi
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass "pass:$STOREPASS" \
  --out "../TwinBox-v2.0.apk" aligned.apk
"$BT/apksigner" verify --print-certs "../TwinBox-v2.0.apk" | head -3
cd ..
"$BT/aapt" dump badging "TwinBox-v2.0.apk" | head -3
echo "BUILD-OK TwinBox-v2.0.apk"

#!/bin/sh
# Webhook 极简安卓 App 构建脚本（无需 Gradle）
# 依赖: JDK 8+ (javac/keytool)、Android build-tools 35.0.0、platforms;android-34
# 用法: ANDROID_SDK=<sdk路径> ./build.sh
set -e
cd "$(dirname "$0")"

SDK="${ANDROID_SDK:-/tmp/android-sdk}"
BT="$SDK/build-tools/35.0.0"
PLATFORM="$SDK/platforms/android-34/android.jar"
OUT=build

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

# 1. 编译资源
"$BT/aapt2" compile --dir res -o "$OUT/res.zip"
# 2. 链接资源 + 生成 R.java
"$BT/aapt2" link -o "$OUT/base.apk" -I "$PLATFORM" --manifest AndroidManifest.xml --java "$OUT/gen" "$OUT/res.zip"
# 3. 编译 Java
find src "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -source 1.8 -target 1.8 -bootclasspath "$PLATFORM" -encoding UTF-8 -Xlint:-options -nowarn -d "$OUT/classes" @"$OUT/sources.txt"
# 4. 转 DEX
"$BT/d8" --release --lib "$PLATFORM" --min-api 26 --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')
# 5. DEX 打入 APK
(cd "$OUT/dex" && zip -q "../base.apk" classes.dex)
# 6. 对齐
"$BT/zipalign" -f 4 "$OUT/base.apk" "$OUT/aligned.apk"
# 7. 签名（统一使用仓库内正式 keystore，保证所有版本同一签名可直接覆盖安装；
#    可用环境变量 KEYSTORE / KEYSTORE_PASS / KEYSTORE_ALIAS 覆盖默认值）
KS="${KEYSTORE:-$(pwd)/mibo.jks}"
KS_PASS="${KEYSTORE_PASS:-mibo}"
KS_ALIAS="${KEYSTORE_ALIAS:-mibo}"
"$BT/apksigner" sign --ks "$KS" --ks-key-alias "$KS_ALIAS" \
    --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
    --v4-signing-enabled false --out Webhook.apk "$OUT/aligned.apk"

echo "OK -> $(pwd)/Webhook.apk"

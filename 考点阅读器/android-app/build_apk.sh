#!/bin/bash
set -e

# Build script for 考点传输 Android APK
# Uses Android SDK command-line tools (downloaded to /tmp/android-sdk)
#
# 用法：在任意目录执行  bash android-app/build_apk.sh
# 环境要求：JDK 17（默认路径 /usr/lib/jvm/java-17-openjdk-amd64，可用 JAVA_HOME 覆盖）
#          Android build-tools 30.0.3 + platform android-30（默认 /tmp/android-sdk，
#          国内可从 https://mirrors.cloud.tencent.com/AndroidSDK/ 直接下载解压）
#
# APK 统一使用仓库内 keystore/keystore.jks（小米 interconnect 官方公开测试签名，
# 与手环端 sign/{debug,release} 的测试证书同源，两端签名一致）。

# 中文路径必需：强制 UTF-8 locale，否则 javac/aapt2 会把路径中的中文字符变成 ?
export LANG=${LANG:-C.UTF-8}
export LC_ALL=C.UTF-8
export JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 ${JAVA_TOOL_OPTIONS}"

# 项目根 = 本脚本所在目录（android-app/）
PROJECT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC=$PROJECT/app/src/main
BUILD=$PROJECT/build
AAR_EXTRACT=$PROJECT/aar-extract

export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}
export ANDROID_HOME=${ANDROID_HOME:-/tmp/android-sdk}

# Tools from Android SDK build-tools 30.0.3
BT=$ANDROID_HOME/build-tools/30.0.3
AAPT2=$BT/aapt2
ZIPALIGN=$BT/zipalign
APKSIGNER=$BT/apksigner
ANDROID_JAR=$ANDROID_HOME/platforms/android-30/android.jar

# d8 wrapper (properly desugars Java 8 lambdas)
D8="$BT/d8"

echo "=== 0. Check tools ==="
echo "aapt2:      $AAPT2"
echo "d8:         $D8"
echo "zipalign:   $ZIPALIGN"
echo "apksigner:  $APKSIGNER"
echo "android.jar: $ANDROID_JAR"
echo "javac:       $($JAVA_HOME/bin/javac -version 2>&1)"
for f in "$AAPT2" "$ZIPALIGN" "$APKSIGNER" "$D8" "$ANDROID_JAR"; do
  [ -e "$f" ] || { echo "缺少 $f，请先安装 Android SDK build-tools 30.0.3 / platform 30"; exit 1; }
done

echo "=== 1. Clean build dir ==="
rm -rf $BUILD
mkdir -p $BUILD/gen $BUILD/obj $BUILD/libs

echo "=== 2. Compile resources ==="
$AAPT2 compile --dir $SRC/res -o $BUILD/res_compiled.zip

echo "=== 3. Link resources ==="
$AAPT2 link \
  -I $ANDROID_JAR \
  --manifest $SRC/AndroidManifest.xml \
  -o $BUILD/base.apk \
  --java $BUILD/gen \
  -R $BUILD/res_compiled.zip \
  --auto-add-overlay \
  --min-sdk-version 26 \
  --target-sdk-version 28

echo "=== 4. Extract AAR classes.jar ==="
if [ ! -f "$AAR_EXTRACT/classes.jar" ]; then
  mkdir -p $AAR_EXTRACT
  unzip -qo $PROJECT/app/libs/xms-wearable-lib_*.aar classes.jar -d $AAR_EXTRACT
fi
cp $AAR_EXTRACT/classes.jar $BUILD/libs/wearable-sdk.jar

echo "=== 5. Compile Java sources ==="
SOURCES=$(find $SRC/java -name "*.java")
GEN_SOURCES=$(find $BUILD/gen -name "*.java" 2>/dev/null)

CLASSPATH="$BUILD/libs/wearable-sdk.jar:$ANDROID_JAR"

$JAVA_HOME/bin/javac -source 1.8 -target 1.8 \
  -classpath $CLASSPATH \
  -d $BUILD/obj \
  $SOURCES $GEN_SOURCES 2>&1

echo "=== 6. Convert to DEX (d8 - desugars lambdas) ==="
CLASS_FILES=$(find $BUILD/obj -name "*.class")

$D8 \
  --output $BUILD \
  --lib $ANDROID_JAR \
  --min-api 26 \
  $CLASS_FILES \
  $BUILD/libs/wearable-sdk.jar 2>&1

echo "=== 7. Package APK ==="
cd $BUILD
zip -j base.apk classes.dex > /dev/null 2>&1

echo "=== 8. Zipalign ==="
$ZIPALIGN -f 4 base.apk aligned.apk

echo "=== 9. Use repo keystore (same-origin test signing as the band RPK) ==="
KEYSTORE=$PROJECT/keystore/keystore.jks
KS_PASS=xmswearable
KS_ALIAS=xmswearable
if [ ! -f "$KEYSTORE" ]; then
    echo "缺少 $KEYSTORE"; exit 1
fi

echo "=== 10. Sign APK ==="
$APKSIGNER sign \
  --ks $KEYSTORE \
  --ks-pass pass:$KS_PASS \
  --ks-key-alias $KS_ALIAS \
  --v1-signing-enabled true \
  --v2-signing-enabled true \
  --out 考点传输.apk \
  aligned.apk

echo "=== 11. Verify ==="
$APKSIGNER verify 考点传输.apk

echo "=== Done ==="
ls -lh $BUILD/考点传输.apk
mkdir -p $PROJECT/release
cp $BUILD/考点传输.apk $PROJECT/release/
cp $BUILD/考点传输.apk.idsig $PROJECT/release/ 2>/dev/null || true
echo "APK copied to $PROJECT/release/考点传输.apk"

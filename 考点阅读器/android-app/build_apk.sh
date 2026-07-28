#!/bin/bash
set -e

export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=/tmp/android-sdk
export PATH=$PATH:$ANDROID_HOME/build-tools/35.0.0:$ANDROID_HOME/platform-tools

PROJECT=/workspace/考点阅读器/android-app
SRC=$PROJECT/app/src/main
BUILD=$PROJECT/build
AAR_EXTRACT=$PROJECT/aar-extract

# 工具
AAPT2=$ANDROID_HOME/build-tools/35.0.0/aapt2
D8=$ANDROID_HOME/build-tools/35.0.0/d8
ZIPALIGN=$ANDROID_HOME/build-tools/35.0.0/zipalign
APKSIGNER=$ANDROID_HOME/build-tools/35.0.0/apksigner
ANDROID_JAR=$ANDROID_HOME/platforms/android-35/android.jar

echo "=== 1. 清理构建目录 ==="
rm -rf $BUILD
mkdir -p $BUILD/gen $BUILD/obj $BUILD/res_compiled $BUILD/libs

echo "=== 2. 编译资源 ==="
$AAPT2 compile --dir $SRC/res -o $BUILD/res_compiled.zip

echo "=== 3. 链接资源（合并 AAR manifest） ==="
# 合并应用 manifest 和 AAR manifest（AAR 的 <queries> 块必须包含在最终 APK 中）
# 先把 AAR manifest 复制到 build 目录
cp $AAR_EXTRACT/AndroidManifest.xml $BUILD/aar-manifest.xml

$AAPT2 link \
  -I $ANDROID_JAR \
  --manifest $SRC/AndroidManifest.xml \
  -o $BUILD/base.apk \
  --java $BUILD/gen \
  -R $BUILD/res_compiled.zip \
  --auto-add-overlay \
  --min-sdk-version 23 \
  --target-sdk-version 35

echo "=== 4. 提取 AAR 的 classes.jar ==="
cp $AAR_EXTRACT/classes.jar $BUILD/libs/wearable-sdk.jar

# 提取 AAR 中的资源（如果有）
if [ -d "$AAR_EXTRACT/res" ]; then
    echo "AAR has resources, compiling..."
    $AAPT2 compile --dir $AAR_EXTRACT/res -o $BUILD/aar_res.zip
fi

echo "=== 5. 编译 Java 源码 ==="
SOURCES=$(find $SRC/java -name "*.java")
GEN_SOURCES=$(find $BUILD/gen -name "*.java" 2>/dev/null)

# 获取所有依赖 jar
CLASSPATH="$BUILD/libs/wearable-sdk.jar:$ANDROID_JAR"

$JAVA_HOME/bin/javac -source 11 -target 11 \
  -classpath $CLASSPATH \
  -d $BUILD/obj \
  $SOURCES $GEN_SOURCES 2>&1

echo "=== 6. 转换为 DEX ==="
# 收集所有 class 文件和 jar
CLASS_FILES=$(find $BUILD/obj -name "*.class")

# 将 SDK jar 也包含进 dex
$D8 \
  --output $BUILD \
  --lib $ANDROID_JAR \
  --min-api 23 \
  $CLASS_FILES \
  $BUILD/libs/wearable-sdk.jar 2>&1

echo "=== 7. 打包 APK ==="
# 将 classes.dex 添加到 APK
cd $BUILD
zip -j base.apk classes.dex > /dev/null 2>&1

echo "=== 8. 对齐 APK ==="
$ZIPALIGN -f 4 base.apk aligned.apk

echo "=== 9. 生成调试签名 ==="
KEYSTORE=$PROJECT/debug.keystore
if [ ! -f "$KEYSTORE" ]; then
    keytool -genkey -v -keystore $KEYSTORE \
      -alias androiddebugkey \
      -keyalg RSA -keysize 2048 -validity 10000 \
      -storepass android -keypass android \
      -dname "CN=Android Debug,O=Android,C=US" 2>/dev/null
fi

echo "=== 10. 签名 APK ==="
$APKSIGNER sign \
  --ks $KEYSTORE \
  --ks-pass pass:android \
  --key-pass pass:android \
  --out 考点传输.apk \
  aligned.apk

echo "=== 11. 验证 ==="
$APKSIGNER verify 考点传输.apk

echo "=== 完成 ==="
ls -lh $BUILD/考点传输.apk
cp $BUILD/考点传输.apk /workspace/考点传输-debug.apk
echo "APK 已复制到 /workspace/考点传输-debug.apk"

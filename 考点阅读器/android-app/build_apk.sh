#!/bin/bash
set -e

# Use system-installed Android SDK (apt: android-sdk, android-sdk-build-tools)
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=/usr/lib/android-sdk

PROJECT=/workspace/考点阅读器/android-app
SRC=$PROJECT/app/src/main
BUILD=$PROJECT/build
AAR_EXTRACT=$PROJECT/aar-extract

# Tools from Debian android-sdk-build-tools (29.0.3)
BT=$ANDROID_HOME/build-tools/debian
AAPT2=$BT/aapt2
ZIPALIGN=$BT/zipalign
APKSIGNER=$BT/apksigner
ANDROID_JAR=$ANDROID_HOME/platforms/android-23/android.jar

# d8 from r8.jar (properly desugars Java 8 lambdas)
D8="java -cp /tmp/r8.jar com.android.tools.r8.D8"

echo "=== 0. Check tools ==="
echo "aapt2:    $AAPT2"
echo "d8:       $D8"
echo "zipalign: $ZIPALIGN"
echo "apksigner: $APKSIGNER"
echo "android.jar: $ANDROID_JAR"

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
# d8 properly desugars Java 8 lambdas into anonymous inner classes
# Collect all .class files (including inner classes)
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

echo "=== 9. Generate debug keystore ==="
KEYSTORE=$PROJECT/debug.keystore
if [ ! -f "$KEYSTORE" ]; then
    keytool -genkey -v -keystore $KEYSTORE \
      -alias androiddebugkey \
      -keyalg RSA -keysize 2048 -validity 10000 \
      -storepass android -keypass android \
      -dname "CN=Android Debug,O=Android,C=US" 2>/dev/null
fi

echo "=== 10. Sign APK ==="
$APKSIGNER sign \
  --ks $KEYSTORE \
  --ks-pass pass:android \
  --key-pass pass:android \
  --out 考点传输.apk \
  aligned.apk

echo "=== 11. Verify ==="
$APKSIGNER verify 考点传输.apk

echo "=== Done ==="
ls -lh $BUILD/考点传输.apk
cp $BUILD/考点传输.apk /workspace/考点传输-debug.apk
echo "APK copied to /workspace/考点传输-debug.apk"

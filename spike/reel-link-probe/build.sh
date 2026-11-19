# Build without Gradle: bash build.sh  (needs Android SDK build-tools 36.1 + Android Studio JBR)
set -e; export PATH=/opt/android-studio/jbr/bin:$PATH; P=$(dirname "$0"); BT=$HOME/Android/Sdk/build-tools/36.1.0; JAR=$HOME/Android/Sdk/platforms/android-36/android.jar; cd $P; rm -rf build; mkdir -p build/classes build/dex
$BT/aapt2 compile --dir res -o build/res.zip
$BT/aapt2 link -o build/base.apk -I $JAR --manifest AndroidManifest.xml build/res.zip --java build/gen --min-sdk-version 28 --target-sdk-version 36 --version-code 1 --version-name 0.1
javac --release 17 -nowarn -classpath $JAR -d build/classes $(find src build/gen -name '*.java') 2>&1 | grep -v '^Note' || true
$BT/d8 --release --min-api 28 --lib $JAR --output build/dex $(find build/classes -name '*.class')
cp build/base.apk build/unsigned.apk; (cd build/dex && zip -q ../unsigned.apk classes.dex)
$BT/zipalign -f 4 build/unsigned.apk build/aligned.apk
$BT/apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android --key-pass pass:android --ks-key-alias androiddebugkey --out build/probe.apk build/aligned.apk 2>/dev/null

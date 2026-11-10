export ANDROID_HOME=/src/android-build-env/android-sdk
./gradlew assembleDebug testDebugUnitTest lintDebug
adb install -r /src/oTraiL/app/build/outputs/apk/debug/app-debug.apk
git add -A
git commit -m "Update -----><<<<<<<<<<<<<<"
git push origin main
adb shell am start -n in.otrail/.MainActivity

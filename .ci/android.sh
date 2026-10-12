#!/bin/bash -ex

export NDK_CCACHE=$(which ccache)

if [ ! -z "${ANDROID_KEYSTORE_B64}" ]; then
    export ANDROID_KEYSTORE_FILE="${GITHUB_WORKSPACE}/ks.jks"
    base64 --decode <<< "${ANDROID_KEYSTORE_B64}" > "${ANDROID_KEYSTORE_FILE}"
fi

cd src/android
chmod +x ./gradlew

if [[ "$TARGET" == "googleplay" ]]; then
    ./gradlew --parallel --build-cache assembleGooglePlayRelease bundleGooglePlayRelease
else
    ./gradlew --parallel --build-cache assembleVanillaRelease bundleVanillaRelease
fi

ccache -s -v

if [ ! -z "${ANDROID_KEYSTORE_B64}" ]; then
    rm "${ANDROID_KEYSTORE_FILE}"
fi

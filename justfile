export JAVA_HOME := "/usr/lib/jvm/java-17-openjdk-amd64"
export GRADLE_OPTS := "-Xmx1g"

gradle := "./gradlew --no-daemon -Dorg.gradle.jvmargs=-Xmx3g"
apksigner := env_var("ANDROID_HOME") / "build-tools/35.0.0/apksigner"
apks := "play-services-core/build/outputs/apk"
reseam_version := "0.28.0"

check:
    {{ gradle }} :play-services-core:assembleMapboxUserDebug

release tag:
    echo {{ tag }} | grep -Eq '^v[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+-r[1-9]$'
    {{ gradle }} :play-services-core:assembleMapboxUserRelease :play-services-core:assembleMapboxHuaweiRelease
    rm -rf dist && mkdir -p dist build
    echo "$GMSCORE_KEYSTORE_B64" | base64 -d > build/gmscore.jks
    {{ apksigner }} sign --ks build/gmscore.jks --ks-pass env:GMSCORE_KEYSTORE_PASSWORD --ks-key-alias "$GMSCORE_KEY_ALIAS" \
        --key-pass env:GMSCORE_KEY_PASSWORD --out dist/reseam-gmscore-{{ trim_start_match(tag, "v") }}.apk {{ apks }}/mapboxUser/release/*.apk
    {{ apksigner }} sign --ks build/gmscore.jks --ks-pass env:GMSCORE_KEYSTORE_PASSWORD --ks-key-alias "$GMSCORE_KEY_ALIAS" \
        --key-pass env:GMSCORE_KEY_PASSWORD --out dist/reseam-gmscore-{{ trim_start_match(tag, "v") }}-huawei.apk {{ apks }}/mapboxHuawei/release/*.apk
    cd dist && sha256sum *.apk > SHA256SUMS
    curl -fsSL --create-dirs -o build/reseam https://cdn.reseam.app/engine/v{{ reseam_version }}/reseam-linux-x64
    chmod +x build/reseam
    build/reseam release files dist --tag {{ tag }} --to s3://reseam-releases/gmscore --keep 2 --forge-release

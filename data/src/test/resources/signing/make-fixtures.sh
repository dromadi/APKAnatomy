#!/usr/bin/env bash
# Regenerates the signing fixtures. New keys are generated each run, so update the
# expected fingerprints in SigningScannerTest from `apksigner verify -v --print-certs`.
set -euo pipefail
B="$ANDROID_HOME/build-tools/36.0.0"; JAR="$ANDROID_HOME/platforms/android-37.0/android.jar"
work=$(mktemp -d); out=$(cd "$(dirname "$0")" && pwd); cd "$work"
for v in 21 28; do
  printf '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.signed">\n<uses-sdk android:minSdkVersion="%s" android:targetSdkVersion="35" />\n<application android:label="Signed" />\n</manifest>\n' "$v" > m$v.xml
  "$B/aapt2" link --manifest m$v.xml -I "$JAR" -o raw$v.apk && "$B/zipalign" -f -p 4 raw$v.apk u$v.apk
done
keytool -genkeypair -keystore k1.p12 -storetype PKCS12 -alias k1 -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=APKAnatomy Test, O=APKAnatomy, C=ID" -storepass android -keypass android
keytool -genkeypair -keystore k2.p12 -storetype PKCS12 -alias k2 -keyalg EC -groupname secp256r1 -validity 10000 -dname "CN=APKAnatomy Rotated, O=APKAnatomy, C=ID" -storepass android -keypass android
K1="--ks k1.p12 --ks-pass pass:android"; K2="--ks k2.p12 --ks-pass pass:android"
"$B/apksigner" sign $K1 --v1-signing-enabled true --v2-signing-enabled false --v3-signing-enabled false --out "$out/v1only.apk" u21.apk
"$B/apksigner" sign $K1 --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true --v4-signing-enabled true --out "$out/all.apk" u21.apk
"$B/apksigner" rotate --out lineage --old-signer $K1 --new-signer $K2
"$B/apksigner" sign $K1 --next-signer $K2 --lineage lineage --rotation-min-sdk-version 33 --v4-signing-enabled false --out "$out/rotated.apk" u28.apk
cp u28.apk "$out/unsigned.apk"
rm -f "$out"/*.apk.idsig.tmp

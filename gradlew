#!/bin/sh
# Bootstrap the official Gradle 8.13 wrapper JAR, then use its normal GradleWrapperMain.
set -eu
BASE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
JAR="$BASE/gradle/wrapper/gradle-wrapper.jar"
EXPECTED=81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f
if [ ! -f "$JAR" ]; then
  TMP="$JAR.tmp.$$"
  trap 'rm -f "$TMP"' EXIT HUP INT TERM
  URL=https://services.gradle.org/distributions/gradle-8.13-wrapper.jar
  if command -v curl >/dev/null 2>&1; then
    curl --fail --location --silent --show-error "$URL" --output "$TMP"
  elif command -v wget >/dev/null 2>&1; then
    wget --quiet -O "$TMP" "$URL"
  else
    echo 'Install curl or wget to download the Gradle wrapper.' >&2
    exit 1
  fi
  if command -v sha256sum >/dev/null 2>&1; then
    ACTUAL=$(sha256sum "$TMP" | cut -d ' ' -f 1)
  else
    ACTUAL=$(shasum -a 256 "$TMP" | cut -d ' ' -f 1)
  fi
  if [ "$ACTUAL" != "$EXPECTED" ]; then
    echo 'Downloaded Gradle wrapper checksum does not match the official 8.13 release.' >&2
    exit 1
  fi
  mv "$TMP" "$JAR"
  trap - EXIT HUP INT TERM
fi
exec java -classpath "$JAR" org.gradle.wrapper.GradleWrapperMain "$@"

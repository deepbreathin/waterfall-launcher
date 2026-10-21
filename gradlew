#!/bin/sh
# Minimal Gradle wrapper: uses an installed `gradle` if there is one, otherwise the bundled wrapper jar.

if [ -z "$GRADLE_HOME" ] && [ -x "$(command -v gradle)" ]; then
  exec gradle "$@"
fi

DIR="$(cd "$(dirname "$0")" && pwd)"
exec java -jar "$DIR/gradle/wrapper/gradle-wrapper.jar" "$@"

#!/usr/bin/env bash
# Builds a jar for every Minecraft version in versions/, or only the ones given: ./build-all.sh 1.21.11 26.3
# Jars end up in build/libs/, one per version.
set -euo pipefail
cd "$(dirname "$0")"

if [ $# -gt 0 ]; then
    versions=("$@")
else
    mapfile -t versions < <(ls versions | sed 's/\.properties$//' | sort -V)
fi

failed=()
for mc in "${versions[@]}"; do
    echo "=== Minecraft $mc"
    ./gradlew build -Pmc="$mc" || failed+=("$mc")
done

ls build/libs
if [ ${#failed[@]} -gt 0 ]; then
    echo "Failed: ${failed[*]}"
    exit 1
fi

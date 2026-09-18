#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repository_root="$(cd -- "$script_dir/../.." && pwd)"
source_aar="$repository_root/survey-ai-core/build/outputs/aar/survey-ai-core-release.aar"
destination_dir="$repository_root/user-test-app/libs"
destination_aar="$destination_dir/survey-ai-core-release.aar"

"$repository_root/gradlew" :survey-ai-core:assembleRelease
[[ -f "$source_aar" ]] || { echo "Release AAR was not produced: $source_aar" >&2; exit 1; }

mkdir -p "$destination_dir"
cp "$source_aar" "$destination_aar"

source_hash="$(shasum -a 256 "$source_aar" | awk '{print $1}')"
destination_hash="$(shasum -a 256 "$destination_aar" | awk '{print $1}')"
[[ "$source_hash" == "$destination_hash" ]] || { echo "Copied AAR checksum mismatch" >&2; exit 1; }
printf 'AAR ready: %s\nSHA256: %s\n' "$destination_aar" "$source_hash"

#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
harness_root="$(cd -- "$script_dir/.." && pwd)"
repository_root="$(cd -- "$harness_root/.." && pwd)"
source_aar="$repository_root/survey-ai-core/build/outputs/aar/survey-ai-core-release.aar"
destination_dir="$harness_root/app/libs"
destination_aar="$destination_dir/survey-ai-core-release.aar"

rm -f "$source_aar"
"$harness_root/gradlew" -p "$repository_root" :survey-ai-core:assembleRelease

if [[ ! -f "$source_aar" ]]; then
    echo "Release AAR was not produced: $source_aar" >&2
    exit 1
fi

mkdir -p "$destination_dir"
rm -f "$destination_aar"
cp "$source_aar" "$destination_aar"

source_size="$(stat -f '%z' "$source_aar")"
source_hash="$(shasum -a 256 "$source_aar" | awk '{print $1}')"
destination_hash="$(shasum -a 256 "$destination_aar" | awk '{print $1}')"

if [[ "$source_hash" != "$destination_hash" ]]; then
    echo "Copied AAR checksum mismatch" >&2
    exit 1
fi

printf 'Source path: %s\n' "$source_aar"
printf 'Destination path: %s\n' "$destination_aar"
printf 'Artifact size: %s bytes\n' "$source_size"
printf 'SHA256: %s\n' "$source_hash"

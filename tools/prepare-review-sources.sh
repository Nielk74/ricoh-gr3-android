#!/usr/bin/env bash
set -euo pipefail

repo_root="${1:?repo root required}"
output_root="${2:?output root required}"
long_edge="${3:-3000}"
srgb_profile="/System/Library/ColorSync/Profiles/sRGB Profile.icc"

if [[ ! -f "$srgb_profile" ]]; then
  echo "sRGB ColorSync profile not found: $srgb_profile" >&2
  exit 1
fi

mkdir -p "$output_root/jpeg" "$output_root/dng" "$output_root/raw"

prepare_one() {
  local source="$1"
  local kind="$2"
  local base
  local raw
  local destination
  local description

  base="$(basename "${source%.*}")"
  raw="$output_root/raw/${kind}-${base}.png"
  destination="$output_root/$kind/${base}.png"
  description="$(file "$source")"

  # ColorSync performs the same important normalization as the Android decoder: every review
  # source reaches the film engine as display-referred sRGB, including Display-P3 DNG renders.
  sips \
    --matchTo "$srgb_profile" \
    --setProperty format png \
    --resampleWidth "$long_edge" \
    "$source" \
    --out "$raw" >/dev/null

  case "$description" in
    *"orientation=upper-right"*)
      sips --rotate 90 "$raw" --out "$destination" >/dev/null
      ;;
    *"orientation=lower-left"*)
      sips --rotate -90 "$raw" --out "$destination" >/dev/null
      ;;
    *)
      cp "$raw" "$destination"
      ;;
  esac

  echo "  $kind/$base"
}

echo "Preparing oriented ${long_edge}px sRGB review masters"
for source in "$repo_root"/.references/*.JPG; do
  [[ -f "$source" ]] && prepare_one "$source" "jpeg"
done
for source in "$repo_root"/.references/*.DNG; do
  [[ -f "$source" ]] && prepare_one "$source" "dng"
done

#!/bin/sh
# The gateway's one binary dependency that is not on Maven: sherpa-onnx's Android AAR (Apache-2.0), for voice cloning
# and transcription on the gateway. Fetched from its GitHub release and checked against its SHA-256.
set -eu
mkdir -p "$(dirname "$0")/../gateway/libs"
cd "$(dirname "$0")/../gateway/libs"
f=sherpa-onnx-static-link-onnxruntime-1.13.8.aar
sum=b22c3fc1b6a45666d28892bb2f7694beeb77a8362d7ebd77c1a5431ec9435471
if [ ! -f "$f" ] || [ "$(shasum -a 256 "$f" | cut -d' ' -f1)" != "$sum" ]; then
  curl -fL -o "$f.part" "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/$f"
  [ "$(shasum -a 256 "$f.part" | cut -d' ' -f1)" = "$sum" ] || { echo "checksum mismatch for $f" >&2; rm -f "$f.part"; exit 1; }
  mv "$f.part" "$f"
fi
echo "ok $f"

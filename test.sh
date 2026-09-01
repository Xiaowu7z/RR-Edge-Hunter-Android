#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJECT_DIR"

echo "fbfea92ee11be855b5f8bbfe66bd37c5134a7472d19c0ab6699a82672df46c6a  app/src/main/jniLibs/arm64-v8a/libgojni.so" |
  sha256sum --check --strict
python3 tools/verify_reference_contract.py

exec ./gradlew --no-daemon :logic-tests:check

#!/bin/zsh
set -euo pipefail

cd "$(dirname "$0")/.."
swift build -c release

app_dir="dist/拾光剪切板.app"
mkdir -p "$app_dir/Contents/MacOS"
cp ".build/release/ez-clipboard-bridge" "$app_dir/Contents/MacOS/ez-clipboard-bridge"
chmod +x "$app_dir/Contents/MacOS/ez-clipboard-bridge"
cp "Resources/Info.plist" "$app_dir/Contents/Info.plist"
codesign --force --deep --sign - "$app_dir"
echo "$app_dir"

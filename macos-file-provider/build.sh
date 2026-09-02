#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
BUILD_DIR="${PROJECT_DIR}/build"
APP="${BUILD_DIR}/EzFiles.app"
APPEX="${APP}/Contents/PlugIns/EzRemoteFileProvider.appex"
SDK="$(xcrun --sdk macosx --show-sdk-path)"
TARGET="arm64-apple-macos13.0"
export CLANG_MODULE_CACHE_PATH="${BUILD_DIR}/ModuleCache"
export SWIFT_MODULECACHE_PATH="${BUILD_DIR}/ModuleCache"

rm -rf "${BUILD_DIR}"
mkdir -p "${APP}/Contents/MacOS" "${APP}/Contents/Resources"
mkdir -p "${APPEX}/Contents/MacOS" "${APPEX}/Contents/Resources"

SHARED=(
  "${PROJECT_DIR}/Shared/Models.swift"
  "${PROJECT_DIR}/Shared/SharedStore.swift"
  "${PROJECT_DIR}/FileProvider/ItemIdentifier.swift"
  "${PROJECT_DIR}/Remote/RemoteStorage.swift"
  "${PROJECT_DIR}/Remote/WebDAVStorage.swift"
  "${PROJECT_DIR}/Remote/S3Storage.swift"
  "${PROJECT_DIR}/Remote/CommandStorage.swift"
)

swiftc -target "${TARGET}" -sdk "${SDK}" -parse-as-library \
  -framework SwiftUI -framework AppKit -framework FileProvider \
  -framework Security -framework CryptoKit -framework LocalAuthentication \
  -framework NetFS \
  -o "${APP}/Contents/MacOS/EzFiles" \
  "${SHARED[@]}" \
  "${PROJECT_DIR}/App/AppModel.swift" \
  "${PROJECT_DIR}/App/ContentView.swift" \
  "${PROJECT_DIR}/App/EzRemoteDriveApp.swift"

swiftc -target "${TARGET}" -sdk "${SDK}" -parse-as-library \
  -Xlinker -e -Xlinker _NSExtensionMain \
  -module-name EzRemoteFileProvider \
  -framework FileProvider -framework UniformTypeIdentifiers \
  -framework Security -framework CryptoKit -framework LocalAuthentication \
  -framework NetFS \
  -o "${APPEX}/Contents/MacOS/EzRemoteFileProvider" \
  "${SHARED[@]}" \
  "${PROJECT_DIR}/FileProvider/ProviderItem.swift" \
  "${PROJECT_DIR}/FileProvider/ProviderEnumerator.swift" \
  "${PROJECT_DIR}/FileProvider/FileProviderExtension.swift"

cp "${PROJECT_DIR}/Resources/Host-Info.plist" "${APP}/Contents/Info.plist"
cp "${PROJECT_DIR}/Resources/Extension-Info.plist" "${APPEX}/Contents/Info.plist"

# Ad-hoc signatures cannot carry restricted App Group/Keychain Group entitlements.
# Keep the host unsandboxed for local protocol testing and sandbox the extension as required.
codesign --force --sign - --entitlements "${PROJECT_DIR}/Resources/EzRemoteLocalExtension.entitlements" "${APPEX}"
codesign --force --sign - "${APP}"

codesign --verify --deep --strict "${APP}"
echo "Built ${APP}"

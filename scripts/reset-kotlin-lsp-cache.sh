#!/usr/bin/env bash
set -euo pipefail

# Clears JetBrains Kotlin LSP caches when the server crash-loops with:
# RocksDBException: ... LOCK: Resource temporarily unavailable

WORKSPACE_HASH="35f128e5787b07365d2c6177f75b084c"
CURSOR_STORAGE="379580c667f285055a758bdbd2b9ac7b"

pkill -f intellij-server 2>/dev/null || true

rm -rf "${HOME}/Library/Caches/JetBrains/analyzer/workspaces/${WORKSPACE_HASH}"
rm -rf "${HOME}/Library/Application Support/Cursor/User/workspaceStorage/${CURSOR_STORAGE}/JetBrains.kotlin-server"

echo "Kotlin LSP cache cleared. Reload Cursor, then run: Kotlin: Restart LSP server"

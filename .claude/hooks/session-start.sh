#!/usr/bin/env bash
# Cloud Claude Code sessions only. Points Gradle at Google's mirror of Maven Central
# (maven-central-mirror.gradle says why). The Android SDK and Google's Maven repo are not
# reachable from those containers, so the app is compiled by GitHub Actions on the PR, not here.
set -euo pipefail
[ "${CLAUDE_CODE_REMOTE:-}" = "true" ] || exit 0
mkdir -p "$HOME/.gradle/init.d"
cp "$CLAUDE_PROJECT_DIR/.claude/hooks/maven-central-mirror.gradle" "$HOME/.gradle/init.d/"
if [ -f "$CLAUDE_PROJECT_DIR/functions/package.json" ] && [ ! -d "$CLAUDE_PROJECT_DIR/functions/node_modules" ]; then
  (cd "$CLAUDE_PROJECT_DIR/functions" && npm install --no-audit --no-fund >/dev/null 2>&1) || true
fi

#!/bin/bash
# SessionStart hook for Claude Code on the web: prepares a fresh cloud container so that
# ./gradlew can build and test the project (see README "Selbst bauen auf der Kommandozeile").
#  1. Android SDK with the packages the build needs (as in .github/workflows/build.yml, plus
#     platform-tools, which AGP would otherwise download in every new session), and local.properties.
#  2. A local caching proxy for Maven Central (maven-central-proxy.py), because Maven Central
#     answers shared cloud containers with HTTP 429; a Gradle init script routes Central through it.
#  3. The Gradle distribution and build plugins, so the container cache already holds them.
set -euo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

ROOT="${CLAUDE_PROJECT_DIR:-$(cd "$(dirname "$0")/../.." && pwd)}"
HOOKS="$ROOT/.claude/hooks"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk}}"
GRADLE_HOME_DIR="${GRADLE_USER_HOME:-$HOME/.gradle}"
PROXY_PORT=8765
PROXY_CACHE="$HOME/.cache/maven-central-proxy"

CMDLINE_TOOLS_ZIP=commandlinetools-linux-16111833_latest.zip
CMDLINE_TOOLS_SHA1=e025545c62a8e64c7559119566a569fb1dec5f60
SDK_PACKAGES=("platforms;android-37.0" "build-tools;36.0.0" "platform-tools")

log() { echo "[session-start] $*" >&2; }

# 1. Android SDK --------------------------------------------------------------------------------
sdkmanager="$SDK/cmdline-tools/latest/bin/sdkmanager"
if [ ! -x "$sdkmanager" ]; then
  log "installing Android command-line tools into $SDK"
  tmp="$(mktemp -d)"
  curl -fsSL --retry 4 -o "$tmp/clt.zip" "https://dl.google.com/android/repository/$CMDLINE_TOOLS_ZIP"
  echo "$CMDLINE_TOOLS_SHA1  $tmp/clt.zip" | sha1sum -c --quiet -
  unzip -q "$tmp/clt.zip" -d "$tmp"
  mkdir -p "$SDK/cmdline-tools"
  rm -rf "$SDK/cmdline-tools/latest"
  mv "$tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
  rm -rf "$tmp"
fi
missing=()
for pkg in "${SDK_PACKAGES[@]}"; do
  [ -d "$SDK/${pkg//;//}" ] || missing+=("$pkg")
done
if [ "${#missing[@]}" -gt 0 ]; then
  log "installing Android SDK packages: ${missing[*]}"
  { yes || :; } | "$sdkmanager" --sdk_root="$SDK" --licenses > /dev/null
  "$sdkmanager" --sdk_root="$SDK" "${missing[@]}" > /dev/null
fi
if ! grep -qs '^sdk.dir=' "$ROOT/local.properties"; then
  echo "sdk.dir=$SDK" >> "$ROOT/local.properties"
fi
if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
  echo "export ANDROID_HOME=\"$SDK\"" >> "$CLAUDE_ENV_FILE"
fi

# 2. Maven Central proxy ------------------------------------------------------------------------
proxy_up() { (exec 3<> "/dev/tcp/127.0.0.1/$PROXY_PORT") 2> /dev/null; }
if ! proxy_up; then
  log "starting Maven Central proxy on 127.0.0.1:$PROXY_PORT"
  mkdir -p "$PROXY_CACHE"
  nohup setsid python3 "$HOOKS/maven-central-proxy.py" "$PROXY_PORT" "$PROXY_CACHE" \
    > "$PROXY_CACHE.log" 2>&1 < /dev/null &
  for _ in $(seq 1 50); do proxy_up && break; sleep 0.1; done
fi
mkdir -p "$GRADLE_HOME_DIR/init.d"
cat > "$GRADLE_HOME_DIR/init.d/maven-central-proxy.init.gradle.kts" <<KTS
// Written by .claude/hooks/session-start.sh (Claude Code on the web only). Routes Maven Central
// through the local caching proxy, which retries HTTP 429. Without a running proxy, nothing changes.
val proxyUrl = "http://127.0.0.1:$PROXY_PORT/maven2/"
val proxyUp = try {
    java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", $PROXY_PORT), 500) }
    true
} catch (e: java.io.IOException) {
    false
}
fun RepositoryHandler.viaProxy() = withType(MavenArtifactRepository::class.java).configureEach {
    val u = url.toString()
    if (u.contains("repo.maven.apache.org") || u.contains("repo1.maven.org")) {
        setUrl(proxyUrl)
        isAllowInsecureProtocol = true
    }
}
if (proxyUp) {
    beforeSettings {
        pluginManagement.repositories.viaProxy()
        dependencyResolutionManagement.repositories.viaProxy()
    }
    allprojects {
        repositories.viaProxy()
        buildscript.repositories.viaProxy()
        tasks.withType(Test::class.java).configureEach {
            systemProperty("robolectric.dependency.repo.url", proxyUrl)
        }
    }
}
KTS
props="$GRADLE_HOME_DIR/gradle.properties"
if ! grep -qs 'claude-session-start' "$props"; then
  cat >> "$props" <<'PROPS'
# claude-session-start: retry transient repository errors
systemProp.org.gradle.internal.repository.max.retries=12
systemProp.org.gradle.internal.repository.initial.backoff=1500
systemProp.org.gradle.internal.http.connectionTimeout=60000
systemProp.org.gradle.internal.http.socketTimeout=120000
PROPS
fi

# 3. Gradle distribution and plugins ------------------------------------------------------------
log "resolving Gradle and build plugins"
cd "$ROOT"
ANDROID_HOME="$SDK" ./gradlew --quiet --no-daemon help > /dev/null
log "ready"

# Printed into the session's context.
echo "Build environment: Android SDK in $SDK (local.properties). Maven Central goes through a local" \
  "caching proxy on 127.0.0.1:$PROXY_PORT that retries HTTP 429 (log: $PROXY_CACHE.log); Gradle falls" \
  "back to Maven Central directly when the proxy is not running. If Gradle reports 429 or cannot reach" \
  "127.0.0.1:$PROXY_PORT, run .claude/hooks/session-start.sh again (CLAUDE_CODE_REMOTE=true)."

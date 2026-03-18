#!/bin/bash
# setup_new_app.sh — Run once when creating a new Android app from this template.
# Asks all mandatory questions and patches everything automatically.

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC_ROOT="$SCRIPT_DIR/app/src/main/java"
OLD_PKG="com.template.app"
OLD_PKG_PATH="com/template/app"

echo ""
echo "========================================="
echo "  Android App Template — Setup Wizard"
echo "========================================="
echo ""

# ── 1. Identity ───────────────────────────────────────────────────────────────

echo "--- App Identity ---"
read -r -p "App name (display name, e.g. 'My Cool App'): " APP_NAME
[[ -z "$APP_NAME" ]] && echo "Error: app name cannot be empty." && exit 1

read -r -p "App ID / package (e.g. com.mycompany.myapp): " APP_ID
[[ -z "$APP_ID" ]] && echo "Error: app ID cannot be empty." && exit 1

APP_IDENTIFIER="${APP_ID##*.}"    # last segment, used for prefs filename
NEW_PKG_PATH="$(echo "$APP_ID" | tr '.' '/')"

read -r -p "Initial version [default: 0.0.1]: " VERSION_NAME
VERSION_NAME="${VERSION_NAME:-0.0.1}"

# ── 2. GitHub repos ───────────────────────────────────────────────────────────

echo ""
echo "--- GitHub: Bug Reports / Issues ---"
read -r -p "Issues repo owner (GitHub username or org): " ISSUES_OWNER
[[ -z "$ISSUES_OWNER" ]] && echo "Error: issues repo owner cannot be empty." && exit 1
read -r -p "Issues repo name: " ISSUES_REPO
[[ -z "$ISSUES_REPO" ]] && echo "Error: issues repo name cannot be empty." && exit 1

echo ""
echo "--- GitHub: App Updates / Releases ---"
read -r -p "Releases repo owner [default: $ISSUES_OWNER]: " RELEASES_OWNER
RELEASES_OWNER="${RELEASES_OWNER:-$ISSUES_OWNER}"
read -r -p "Releases repo name [default: $ISSUES_REPO]: " RELEASES_REPO
RELEASES_REPO="${RELEASES_REPO:-$ISSUES_REPO}"

# ── 3. Git remote ─────────────────────────────────────────────────────────────

echo ""
echo "--- Git Remote ---"
CURRENT_REMOTE="$(git -C "$SCRIPT_DIR" remote get-url origin 2>/dev/null || echo "(none)")"
echo "  Current origin: $CURRENT_REMOTE"
read -r -p "New git remote URL for this app [leave blank to keep current]: " NEW_GIT_REMOTE

# ── 4. Signing / keystore ─────────────────────────────────────────────────────

echo ""
echo "--- Android Signing (required for the /release command) ---"
read -r -p "Keystore file path (relative to project root) [default: keystore.jks]: " KEYSTORE_PATH
KEYSTORE_PATH="${KEYSTORE_PATH:-keystore.jks}"
read -r -p "Key alias [default: key0]: " KEY_ALIAS
KEY_ALIAS="${KEY_ALIAS:-key0}"
read -r -s -p "Keystore password: " KEYSTORE_PASS
echo ""
read -r -s -p "Key password [default: same as keystore password]: " KEY_PASS
echo ""
KEY_PASS="${KEY_PASS:-$KEYSTORE_PASS}"

# ── 5. Optional: autofix cron ─────────────────────────────────────────────────

echo ""
echo "--- Autofix Cron (optional) ---"
echo "  The autofix agent polls GitHub for issues labelled 'autofix', fixes them"
echo "  autonomously with Claude, and triggers a release when done."
read -r -p "Set up autofix cron job now? [y/N]: " SETUP_CRON

# ── Summary + confirm ─────────────────────────────────────────────────────────

echo ""
echo "-----------------------------------------"
echo "  Summary"
echo "-----------------------------------------"
echo "  App Name      : $APP_NAME"
echo "  App ID        : $APP_ID"
echo "  Version       : $VERSION_NAME"
echo "  Issues Repo   : $ISSUES_OWNER/$ISSUES_REPO"
echo "  Releases Repo : $RELEASES_OWNER/$RELEASES_REPO"
if [[ -n "$NEW_GIT_REMOTE" ]]; then
    echo "  Git Remote    : $NEW_GIT_REMOTE"
else
    echo "  Git Remote    : (unchanged — $CURRENT_REMOTE)"
fi
echo "  Keystore      : $KEYSTORE_PATH  alias=$KEY_ALIAS"
if [[ "$SETUP_CRON" == "y" || "$SETUP_CRON" == "Y" ]]; then
    echo "  Autofix cron  : yes (every 30 min)"
else
    echo "  Autofix cron  : no"
fi
echo "-----------------------------------------"
read -r -p "Looks good? Apply all changes? [y/N]: " CONFIRM
[[ "$CONFIRM" != "y" && "$CONFIRM" != "Y" ]] && echo "Aborted." && exit 0

echo ""
echo "Applying changes..."

# ── Patch AppConfig.kt ────────────────────────────────────────────────────────
APPCONFIG="$SRC_ROOT/$OLD_PKG_PATH/AppConfig.kt"
sed -i '' \
  -e "s|const val GITHUB_ISSUES_REPO_OWNER = \"owner\".*|const val GITHUB_ISSUES_REPO_OWNER = \"$ISSUES_OWNER\"|" \
  -e "s|const val GITHUB_ISSUES_REPO_NAME = \"repo\".*|const val GITHUB_ISSUES_REPO_NAME = \"$ISSUES_REPO\"|" \
  -e "s|const val GITHUB_RELEASES_REPO_OWNER = \"owner\".*|const val GITHUB_RELEASES_REPO_OWNER = \"$RELEASES_OWNER\"|" \
  -e "s|const val GITHUB_RELEASES_REPO_NAME = \"repo\".*|const val GITHUB_RELEASES_REPO_NAME = \"$RELEASES_REPO\"|" \
  -e "s|const val APP_NAME = \"TemplateApp\".*|const val APP_NAME = \"$APP_NAME\"|" \
  -e "s|const val SECURE_PREFS_FILENAME = \"template_secure_keys\".*|const val SECURE_PREFS_FILENAME = \"${APP_IDENTIFIER}_secure_keys\"|" \
  "$APPCONFIG"
echo "  [OK] AppConfig.kt"

# ── Patch app/build.gradle.kts ────────────────────────────────────────────────
BUILD_GRADLE="$SCRIPT_DIR/app/build.gradle.kts"
sed -i '' \
  -e "s|namespace = \"com\.template\.app\"|namespace = \"$APP_ID\"|" \
  -e "s|applicationId = \"com\.template\.app\"|applicationId = \"$APP_ID\"|" \
  -e "s|versionName = \".*\"|versionName = \"$VERSION_NAME\"|" \
  "$BUILD_GRADLE"
echo "  [OK] app/build.gradle.kts"

# ── Patch strings.xml ─────────────────────────────────────────────────────────
STRINGS_XML="$SCRIPT_DIR/app/src/main/res/values/strings.xml"
sed -i '' \
  -e "s|<string name=\"app_name\">.*</string>|<string name=\"app_name\">$APP_NAME</string>|" \
  "$STRINGS_XML"
echo "  [OK] res/values/strings.xml"

# ── Patch themes.xml ──────────────────────────────────────────────────────────
THEME_NAME="$(echo "$APP_NAME" | tr -d ' ')"
THEMES_XML="$SCRIPT_DIR/app/src/main/res/values/themes.xml"
sed -i '' -e "s|Theme\.TemplateApp|Theme.$THEME_NAME|g" "$THEMES_XML"
echo "  [OK] res/values/themes.xml"

# ── Patch AndroidManifest.xml ─────────────────────────────────────────────────
MANIFEST="$SCRIPT_DIR/app/src/main/AndroidManifest.xml"
sed -i '' -e "s|android:theme=\"@style/Theme\.TemplateApp\"|android:theme=\"@style/Theme.$THEME_NAME\"|g" "$MANIFEST"
echo "  [OK] AndroidManifest.xml"

# ── Patch settings.gradle.kts ─────────────────────────────────────────────────
SETTINGS_GRADLE="$SCRIPT_DIR/settings.gradle.kts"
sed -i '' -e "s|rootProject\.name = \".*\"|rootProject.name = \"$APP_NAME\"|" "$SETTINGS_GRADLE"
echo "  [OK] settings.gradle.kts"

# ── Rename package folder + update all .kt files ──────────────────────────────
if [[ "$OLD_PKG_PATH" != "$NEW_PKG_PATH" ]]; then
    NEW_SRC="$SRC_ROOT/$NEW_PKG_PATH"
    mkdir -p "$NEW_SRC"
    # Copy all files over (preserving subdirectory structure)
    cp -r "$SRC_ROOT/$OLD_PKG_PATH/." "$NEW_SRC/"
    # Rewrite package declarations and imports in every Kotlin file
    find "$NEW_SRC" -name "*.kt" | while IFS= read -r f; do
        sed -i '' \
          -e "s|package $OLD_PKG\b|package $APP_ID|g" \
          -e "s|import $OLD_PKG\.|import $APP_ID.|g" \
          "$f"
    done
    # Remove old package tree
    rm -rf "$SRC_ROOT/com/template"
    # Clean up empty com/ if nothing else lives there
    rmdir "$SRC_ROOT/com" 2>/dev/null || true
    echo "  [OK] Package renamed: $OLD_PKG → $APP_ID"
else
    echo "  [--] Package unchanged (same as template ID)"
fi

# ── Keystore: generate if missing + write keystore.properties ─────────────────
KEYSTORE_ABS="$SCRIPT_DIR/$KEYSTORE_PATH"
if [[ ! -f "$KEYSTORE_ABS" ]]; then
    JAVA_HOME_PATH="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
    if [[ -d "$JAVA_HOME_PATH" ]]; then
        "$JAVA_HOME_PATH/bin/keytool" -genkeypair \
            -keystore "$KEYSTORE_ABS" \
            -alias "$KEY_ALIAS" \
            -keyalg RSA -keysize 2048 -validity 10000 \
            -storepass "$KEYSTORE_PASS" \
            -keypass "$KEY_PASS" \
            -dname "CN=$APP_NAME, O=$APP_NAME, C=US" 2>/dev/null
        echo "  [OK] Keystore generated: $KEYSTORE_PATH"
    else
        echo "  [WARN] Android Studio JDK not found — create keystore manually with keytool."
    fi
else
    echo "  [OK] Keystore already exists: $KEYSTORE_PATH"
fi
cat > "$SCRIPT_DIR/keystore.properties" <<KSPROPS
storeFile=$KEYSTORE_PATH
storePassword=$KEYSTORE_PASS
keyAlias=$KEY_ALIAS
keyPassword=$KEY_PASS
KSPROPS
echo "  [OK] keystore.properties written"

# Ensure keystore files are gitignored
GITIGNORE="$SCRIPT_DIR/.gitignore"
grep -q "^keystore\.properties" "$GITIGNORE" 2>/dev/null || echo "keystore.properties" >> "$GITIGNORE"
# Uncomment *.jks / *.keystore lines if they were commented out
sed -i '' -e 's|^#\(.*\.jks\)|\1|' -e 's|^#\(.*\.keystore\)|\1|' "$GITIGNORE"
echo "  [OK] .gitignore updated (keystore files excluded from git)"

# ── Git remote ────────────────────────────────────────────────────────────────
if [[ -n "$NEW_GIT_REMOTE" ]]; then
    git -C "$SCRIPT_DIR" remote set-url origin "$NEW_GIT_REMOTE"
    echo "  [OK] git remote origin → $NEW_GIT_REMOTE"
fi

# ── Autofix cron ──────────────────────────────────────────────────────────────
if [[ "$SETUP_CRON" == "y" || "$SETUP_CRON" == "Y" ]]; then
    WRAPPER="$SCRIPT_DIR/.claude/skills/autofix/autofix-wrapper.sh"
    CRON_LOG="$SCRIPT_DIR/.autofix-logs/cron.log"
    chmod +x "$WRAPPER" "$SCRIPT_DIR/.claude/skills/autofix/autofix.sh" 2>/dev/null || true
    CRON_LINE="*/30 * * * * $WRAPPER >> $CRON_LOG 2>&1"
    # Remove any existing autofix cron entry for this project, then add new one
    ( crontab -l 2>/dev/null | grep -v "autofix-wrapper.sh.*$(basename "$SCRIPT_DIR")"; echo "$CRON_LINE" ) | crontab -
    echo "  [OK] Cron job installed (every 30 min)"
fi

# ── Done ──────────────────────────────────────────────────────────────────────
echo ""
echo "========================================="
echo "  Done! Remaining manual steps:"
echo ""
echo "  1. Replace the app icon:"
echo "     res/mipmap-*/ic_launcher*.png"
echo ""
echo "  2. Add your GitHub PAT (for bug reports + updates):"
echo "     Run the app → Settings → (7-tap admin mode) → GitHub Token"
echo ""
echo "  3. Sync Gradle in Android Studio"
echo "     (File → Sync Project with Gradle Files)"
echo "========================================="

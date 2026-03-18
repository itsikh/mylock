Release a new version of this app. Arguments: optional version string e.g. "1.2.0". If not provided, auto-increment the patch version.

## Environment
- JAVA_HOME: `/Applications/Android Studio.app/Contents/jbr/Contents/Home`
- Gradle: `./gradlew` (wrapper in project)
- Primary remote: `origin` → GitHub (releases + issues)
- Secondary remote: `github` → push only if this remote exists

---

## Steps

### 1. Read app configuration
Read `app/build.gradle.kts` and extract the current `versionCode` (integer) and `versionName` (string, e.g. `"1.0.0"`).

Find `AppConfig.kt` (search under `app/src/`) and extract:
- `APP_NAME` — used for the APK filename and release title
- `GITHUB_RELEASES_REPO_OWNER` — GitHub org/user that owns the releases repo
- `GITHUB_RELEASES_REPO_NAME` — GitHub repo name where releases are published

### 2. Determine new version
- If `$ARGUMENTS` is non-empty, use it as `newVersionName`.
- Otherwise auto-increment the **patch** segment of `versionName` (1.0.0 → 1.0.1).
- `newVersionCode` = current `versionCode` + 1.

### 3. Pre-flight checks — abort if any fail
Run these two checks **in parallel** (single Bash call with `&` and `wait`):
```bash
{ test -f keystore.properties && echo "✅ keystore" || { echo "❌ keystore.properties missing"; exit 1; }; } &
{ [ "$(git branch --show-current)" = "main" ] && echo "✅ branch=main" || { echo "❌ not on main"; exit 1; }; } &
wait
```

### 4. Commit any uncommitted changes
Check for uncommitted tracked changes (`git status --porcelain`).
If there are any modified tracked files (`M` lines), stage all and commit:
```bash
git add -u
git commit -m "chore: pre-release changes for v<newVersionName>

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```
Untracked files (`??`) are ignored. If the tree is already clean, skip this step.

### 5. Bump version in build.gradle.kts
Edit `app/build.gradle.kts`:
- `versionCode = <old>` → `versionCode = <newVersionCode>`
- `versionName = "<old>"` → `versionName = "<newVersionName>"`

### 6. Commit version bump
```bash
git add app/build.gradle.kts && git commit -m "chore: release v<newVersionName>"
```

### 7. Push source + start build in parallel
Push to origin; also push to `github` remote if it exists:
```bash
git push origin main &
git remote | grep -q '^github$' && git push github main &
wait
```
If origin push fails, log the warning and continue. Then immediately start the build:
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
# Detect CPU count and available RAM to maximise build speed
CPUS=$(sysctl -n hw.logicalcpu 2>/dev/null || nproc 2>/dev/null || echo 8)
MEM_GB=$(python3 -c "
import subprocess
m = int(subprocess.run(['sysctl','hw.memsize'],capture_output=True,text=True).stdout.split()[1])
print(max(4, m // 1024 // 1024 // 1024 - 2))
" 2>/dev/null || echo 6)
./gradlew assembleRelease \
  --parallel \
  --build-cache \
  --configuration-cache \
  --max-workers="$CPUS" \
  -Dorg.gradle.jvmargs="-Xmx${MEM_GB}g -XX:+HeapDumpOnOutOfMemoryError -XX:+UseG1GC" \
  -Dkotlin.incremental=true \
  -Dkotlin.daemon.jvm.options="-Xmx${MEM_GB}g"
```
If the build fails, stop and show the last 30 lines. Do NOT continue.

### 8. Copy APK, create tag, push tag — all in parallel
After the build succeeds:
```bash
cp app/build/outputs/apk/release/app-release.apk <AppName>-v<newVersionName>.apk
git tag v<newVersionName>
git push origin v<newVersionName> &
git remote | grep -q '^github$' && git push github v<newVersionName> &
wait
```
Where `<AppName>` is the `APP_NAME` value from `AppConfig.kt` (spaces replaced with hyphens).

### 9. Create GitHub release and upload APK
```bash
gh release create v<newVersionName> \
  --repo <GITHUB_RELEASES_REPO_OWNER>/<GITHUB_RELEASES_REPO_NAME> \
  --title "<AppName> v<newVersionName>" \
  --notes "## What's new
Release v<newVersionName>" \
  "<AppName>-v<newVersionName>.apk"
rm "<AppName>-v<newVersionName>.apk"
```

### 10. Print summary
```
✅ Released <AppName> v<newVersionName>
   versionCode : <newVersionCode>
   APK size    : <size of app-release.apk>
   GitHub      : https://github.com/<GITHUB_RELEASES_REPO_OWNER>/<GITHUB_RELEASES_REPO_NAME>/releases/tag/v<newVersionName>
```

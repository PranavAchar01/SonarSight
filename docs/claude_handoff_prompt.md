# Claude Code handoff / bootstrap prompt

Paste the block below into a **fresh Claude Code session on a different account** (e.g. when
you run out of usage). It is self-contained and idempotent: it detects what's already installed,
installs only what's missing, sets up the SixthSense dev environment, registers the MCP server
for the new account, verifies the Android build, and connects the phone.

Environment assumptions (the original setup): macOS on Apple Silicon, Homebrew at
`/opt/homebrew`, repo at `~/Downloads/sixthsense_project_files/sixthsense`, GitHub repo
`https://github.com/shanayg15/SixthSense-`.

---

## ⬇️ COPY FROM HERE

You are taking over SixthSense dev setup on macOS (Apple Silicon) from a previous Claude
session that ran out of usage. SixthSense is an on-device navigation copilot for blind/low-vision
users: a chest-mounted Samsung Galaxy S25 Ultra runs CameraX + ExecuTorch/QNN models, produces a
`SceneState`, and drives a BLE haptic belt, an on-device voice agent, and a visualization-only
dashboard. The GitHub repo is https://github.com/shanayg15/SixthSense- and is already fully
populated.

CRITICAL RULES (do not violate):
- Claude/MCP is a DEVELOPMENT & DEBUG command center ONLY. Never put Claude, cloud APIs, or any
  external LLM into the live assistive runtime. The demo runs fully on-device in airplane mode.
- Do NOT install the APK, send belt commands, toggle mock mode, ask the voice agent, or change
  device settings unless I explicitly ask.
- NEVER commit API keys, GitHub tokens, Qualcomm tokens, or any secret.
- Do not run destructive commands (no factory reset, no wiping the phone/data).
- The belt is a dumb actuator; the dashboard is visualization-only.

Do all of the following, idempotently (skip anything already present), and report a final status
table. Use absolute paths. ANDROID_HOME is `~/Library/Android/sdk`; Android Studio's bundled JDK
is `/Applications/Android Studio.app/Contents/jbr/Contents/Home`.

1. Locate the repo. If `~/Downloads/sixthsense_project_files/sixthsense` exists, use it. Otherwise
   `git clone https://github.com/shanayg15/SixthSense- ~/Downloads/sixthsense_project_files/sixthsense`
   (or a path I give you). Treat that folder as REPO.

2. Toolchain (install only what's missing; use Homebrew):
   - `uv`  → `curl -LsSf https://astral.sh/uv/install.sh | sh`  (lands in `~/.local/bin`)
   - SDK Platform Tools (adb) → `brew install --cask android-platform-tools`
   - Android command-line tools (sdkmanager) → `brew install --cask android-commandlinetools`
   - Android Studio → `brew install --cask android-studio` (large; skip if `/Applications/Android Studio.app` exists)
   - Gradle (only to bootstrap a wrapper) → `brew install gradle`
   - Node/npm are needed for the dashboard; check `node`/`npm`.

3. Shell env: ensure `~/.zshrc` has (idempotently, under a `# --- SixthSense Android env ---` marker):
   `export ANDROID_HOME="$HOME/Library/Android/sdk"`
   `export PATH="$ANDROID_HOME/platform-tools:$HOME/.local/bin:/opt/homebrew/bin:$PATH"`

4. Android SDK packages: the app uses compileSdk/targetSdk 35. Using sdkmanager with
   `--sdk_root="$ANDROID_HOME"` and `JAVA_HOME` set to the Android Studio jbr, accept licenses
   (`yes | sdkmanager --sdk_root="$ANDROID_HOME" --licenses`) then install
   `"platforms;android-35" "build-tools;35.0.0" "platform-tools"`.

5. Gradle wrapper: if `REPO/android/gradlew` is missing, generate one pinned to Gradle 8.9
   (AGP 8.6.0 needs 8.7–8.9). Bootstrap it in a temp dir that contains only a
   `settings.gradle.kts` with `rootProject.name = "wrappergen"`, run
   `gradle wrapper --gradle-version 8.9 --distribution-type bin`, then copy `gradlew`,
   `gradlew.bat`, and `gradle/` into `REPO/android/` and `chmod +x REPO/android/gradlew`.

6. MCP server (register for THIS account — MCP registration is per-account):
   `cd REPO && bash scripts/setup_mcp.sh`  (registers `sixthsense` at project scope), OR for
   global availability register at user scope with an absolute uv path:
   `claude mcp add --scope user sixthsense -- "$HOME/.local/bin/uv" --directory "REPO/mcp" run sixthsense_mcp.py`
   Then `claude mcp list` should show `sixthsense` Connected. Note: if it shows "pending approval",
   I must approve it once by running `claude` interactively.

7. Verify (safe checks only): `python3 -m py_compile REPO/mcp/sixthsense_mcp.py`,
   `bash REPO/scripts/verify_android_env.sh`, and a real build:
   with `JAVA_HOME` = Studio jbr and `ANDROID_HOME` set,
   `cd REPO/android && ./gradlew :app:assembleDebug --no-daemon --console=plain`.
   Confirm `app/build/outputs/apk/debug/app-debug.apk` exists.

8. Phone: run `adb devices -l`. If it shows `unauthorized`, tell me to pick "File transfer /
   Android Auto" on the phone's "Use USB for" dialog and tap "Allow" on the USB-debugging prompt,
   then re-check (restart with `adb kill-server && adb start-server` if the transport reset). The
   target is a Galaxy S25 Ultra (model SM-S938U1, Android 15 / SDK 35, SoC SM8750). Do NOT install
   the APK unless I ask.

Then stop and give me: a status table of every component (installed/verified), whether the APK
built, whether the phone is authorized, the MCP registration command, and the first 5 MCP test
prompts from `REPO/docs/mcp_test_checklist.md`.

## ⬆️ COPY TO HERE

---

## Notes for the human

- **MCP registration is per Claude account / config** (`~/.claude.json` for user scope). A new
  account must run step 6 again — that's why it's in the prompt.
- The repo on GitHub does **not** include the Gradle wrapper jar (binary) or `.mcp.json`
  (machine-specific path), so the prompt regenerates both. This is expected.
- If you're on the **same machine**, most of steps 2–5 will already be satisfied and the new
  session will breeze through to MCP registration + verification.
- Keep secrets in shell env / untracked files only. Qualcomm AI Hub: `pip install qai-hub` and
  configure the token outside git (see `model_export_plan.md`).

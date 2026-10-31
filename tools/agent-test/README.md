# Agent hand-off tests

SparkWriter lets an assistant fill in a SparkPlug and open it for review. Two
entry points share one path into the wizard:

- **AppFunctions** (Google's "Android MCP"): `listSparkPlugs`, `describeSparkPlug`,
  `prepareSparkPlugDraft` in `app/.../agent/SparkWriterAgentFunctions.kt`. The last
  one returns a PendingIntent that opens the draft.
- **`spark://draft?plug=<id>&f.<field>=<value>...`**: an explicit VIEW intent from
  another app (not BROWSABLE, so web pages cannot send one).

Either way, the draft only fills in values. It opens on the first page that still
needs input, or on Review. Password fields are refused, and nothing is written
until the user taps Write or Save.

## What runs where

| Test | Kind | Covers | Runs on |
|---|---|---|---|
| `ConfigDraftTest` | JVM | validation rules, landing step, URI encoding | `./gradlew :app:testDebugUnitTest` |
| `agent/SparkPlugAgentModelsTest` | JVM | what the assistant is shown; describe/prepare agree | same |
| `agent/AgentScenarioTest` | JVM | every scenario below is still accepted and lands as stated | same |
| `DraftHandoffTest` | device | draft intent → right wizard step; agent functions → PendingIntent → Review | any API 28+ device |
| `AppFunctionsPlatformTest` | device | the real platform path: index, bind, serialize, execute | devices that index app functions (see below) |

```
ANDROID_HOME=~/Android/Sdk ./gradlew :app:testDebugUnitTest :app:connectedDebugAndroidTest
```

### Which devices run `AppFunctionsPlatformTest`

Checked October 2026 with `androidx.appfunctions` 1.0.0-alpha08:

| Emulator image | Result |
|---|---|
| `android-35` google_apis | skipped (no AppFunctions platform) |
| `android-36` google_apis | **skipped**: functions are registered and report enabled, but the index stays empty, so the androidx client cannot find them to execute |
| `android-canary-20261007` google_apis_ps16k (API 37) | **passes** |

The skip waits 30 s once, then the rest skip immediately. Setting up the API 37
image:

```
sdkmanager "system-images;android-canary-20261007;google_apis_ps16k;x86_64"
avdmanager create avd -n test37 -k "system-images;android-canary-20261007;google_apis_ps16k;x86_64" -d pixel_6
emulator -avd test37 -no-window -no-audio -gpu swiftshader_indirect &
```

## Manual and agent-driven checks

- `send-draft.sh <plug-id> field=value ...` sends a draft over adb.
- `replay-scenario.sh scenarios/<name>.properties` sends a scenario's expected
  values and prints where the draft should land.
- `scenarios/*.properties`: requests in plain words, the values a good assistant
  should send, and the expected landing (`review` or a page title). Use them to
  check a real assistant: give it the `prompt`, then compare what it sent (the
  `Applied draft ... accepted=[...]` line in `adb logcat -s SparkWriter` lists the
  field ids) with the `expect.` lines. Add a scenario whenever an assistant gets
  something wrong.
- `journeys/*.xml`: journeys for an agent with the `android` CLI skill
  ("Evaluate tools/agent-test/journeys/draft-to-review.xml on the connected device").

## Not covered yet

- A real assistant (Gemini) choosing and calling the functions. That needs an
  Early Access Program device and app allow-listing.
- The USB write or ISO export after Review; those have their own tests.

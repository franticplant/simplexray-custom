---
name: simplexray-emulator-testing
description: Test a simplexray-custom build on an Android emulator or device. Use whenever the task says smoke test, test on the emulator/device, verify connect or config switching, check live stats, test a broken config, kill/restart libxray, or verify Bypass LAN routes.
---

# Testing simplexray-custom on a device

The app runs Xray as a child process (`libxray.so`) inside `TProxyService`. CI covers build, lint
and unit tests; these device checks cover the parts CI cannot: VPN establishment, process
supervision, config switching, route setup and live stats.

## Setup

- Use an **x86_64** AVD for the x86_64 debug APK (or an arm64 image with the arm64 APK). Keep it
  running between checks; configs live in the app's data and survive reinstalls.
- Install the debug build:

  ```sh
  adb install -r app/build/outputs/apk/debug/app-x86_64-debug.apk
  ```

- The app id is `io.github.franticplant.simplexray`; the launcher activity is
  `com.simplexray.an.activity.MainActivity`. If the AVD has a secondary display, start on the
  primary one:

  ```sh
  adb shell am start --display 0 -n io.github.franticplant.simplexray/com.simplexray.an.activity.MainActivity
  ```

- Configs are ordinary Xray JSON files in the app's `filesDir`. Push them with `adb push` to
  `/data/local/tmp`, then copy through `run-as`:

  ```sh
  adb shell "run-as io.github.franticplant.simplexray sh -c 'cat /data/local/tmp/a.json > files/A.json'"
  ```

  Restart the app so `refreshConfigFileList()` picks them up.

- Run a real core to test against. A local Xray server on the host is enough; the emulator reaches
  the host at `10.0.2.2`. Give it a VLESS inbound and a freedom outbound, and point the client
  config at that address and port. Run the server so its access log shows which inbound receives
  traffic; this is how you prove a config switch moved the connection.

- Generate traffic through the tunnel with a TCP request from the device, e.g. `nc` to a public
  IP, or to the host where routing allows it. Watch the app's stats and the server's access log.

## Scenarios and expected results

1. **Connect**
   - Select a working config and start the VPN (main switch or instance switch).
   - The config chip goes `Starting` → `Running`; a `libxray.so` process exists.
   - Generate traffic; uplink/downlink totals and speed update, uptime advances.

2. **Switch config while connected**
   - Select the other working config while the VPN is up.
   - The old core must stop (old PID gone) and the new one start; the server's access log must
     switch to the new inbound.
   - The service process must not crash; check `adb logcat -d -s AndroidRuntime:E`.

3. **Broken config**
   - Select a config with an invalid protocol or bad JSON.
   - The chip shows `Failed` and the reason from Xray (e.g. `unknown config id`), not `Stopped`.

4. **Kill the core**
   - Let a healthy instance run for more than the supervisor's stable window (5 s), then kill it:

     ```sh
     adb shell run-as io.github.franticplant.simplexray kill -9 <pid>
     ```

   - The UI shows `Restarting` (or reconnecting) and then `Running` with a new PID. Killing during
     startup should produce `Failed`, not a restart loop.

5. **Bypass LAN**
   - With the setting on, inspect the VPN network's routes:

     ```sh
     adb shell dumpsys connectivity
     ```

   - The `tun0` route list must be the complement of private/link-local/multicast space: no
     `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `169.254.0.0/16`, `224.0.0.0/3`, and no
     `0.0.0.0/0`. Public addresses must still be routed and traffic must still work.

## Driving the UI

- Compose UI is easiest to inspect with `uiautomator dump`:

  ```sh
  adb shell uiautomator dump /sdcard/ui.xml
  adb shell cat /sdcard/ui.xml
  ```

  Find the text or content description you need, then tap its bounds center with `adb shell input
  tap X Y`. Re-dump after each action; list positions change when the Activity screen adds cards.
- Use `adb logcat -c` before a scenario and `adb logcat -d -s VpnService:D AndroidRuntime:E`
  afterwards. A `FATAL EXCEPTION` in `:native` means the service process died, even if the UI
  still shows stale state.

## Cleanup

- Disconnect in the app or force-stop the package; then stop the emulator and any local test
  server. Don't leave a test VPN or a core process running between sessions.
- Never put local AVD names, SDK paths, test UUIDs or keystore details in a commit or a repo
  skill; keep them in the local lab note.

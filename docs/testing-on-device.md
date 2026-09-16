# Testing on a device

Reference device: Solana Seeker (Dimensity 7300, 8 GB). Lessons that cost real time:

* **Thermal.** Back-to-back inference throttles the big cores from 2.5 GHz to 1.2 GHz within minutes and
  makes every number meaningless (a 7x slowdown was measured). Check
  `adb shell dumpsys thermalservice | grep 'Thermal Status'` and wait for `0` or `1` before benchmarking.
* **Memory.** Run one native process at a time and watch `MemAvailable` in `/proc/meminfo`; kill the job
  below ~600 MB. Never use `ulimit -v`: it aborts every Android process.
* **Model files for tests.** Instrumented tests read GGUFs from `/data/local/tmp/inferno/models/`; the app
  reads them from its own `files/models/<id>/`. Copy with `adb shell run-as to.eyed.inferno`.
* **No network on the phone?** Run a forward proxy on the laptop, `adb reverse tcp:8080 tcp:8080`, and
  `adb shell settings put global http_proxy 127.0.0.1:8080`; reset with `settings put global http_proxy :0`.
* **Screenshots.** `adb exec-out screencap -p > shot.png`.
* **Unit tests** (`./gradlew :app:testDebugUnitTest`) cover the engine state machine, planner, chunker,
  downloader resume paths and repositories with fakes; `NativeEngineTest` and the data tests run on the
  device with `to.eyed.inferno.TestRunner`.

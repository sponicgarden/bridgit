# APK versioning

- `apk-version.properties` is the sole source of the last allocated Android
  `versionName` and `versionCode`. Gradle advances both automatically for builds.
  Never reintroduce hardcoded versions in `defaultConfig` or reset this counter.
- The user confirmed that releases had reached **1.6.0** before automatic
  allocation. **1.6.1 / versionCode 18** was subsequently built and verified from
  the actual APK manifest. Values from older Gradle files, tags, README examples,
  plans, and cached APKs are not authoritative current-version evidence.
- Before changing versioning or preparing an APK, read the counter and check
  available latest release metadata and installed-device package versions.
  Reconcile conflicting evidence before building; never silently downgrade.
- Preserve both counters, including across clean builds, branch changes, and
  checkouts. Carry forward the highest allocated values when reconciling work.
  Never lower the version guard to get a build through. Missing or stale counters
  must be recovered from verified build/release history.
- Every build may consume a version, including verification builds and failed
  builds. Gaps are intentional. Do not decrement or reuse numbers afterward.
- Commit the updated counter with changes that are committed for a build. Before
  reporting an APK version, inspect the APK manifest (for example `aapt dump
  badging`); report the actual version name and code, not an assumed value.
- Independent checkouts do not share a lock or allocator. Coordinate their
  counters before building; never claim global uniqueness across checkouts.

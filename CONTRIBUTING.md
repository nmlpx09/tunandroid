# Contributing

Use Android SDK 35, build tools 35.0.0 and JDK 17 or 21. Open this project folder in Android Studio, or set `ANDROID_HOME` for command-line builds. Use the committed Gradle wrapper.

Before submitting a change, run:

```sh
./tests/run.sh
./gradlew assembleDebug lintDebug assembleRelease lintRelease
```

Protocol tests run against the GPL-2.0 reference tables bundled in `tests/fixtures`. If the wire format changes, compare the fixture and Android codec with the matching tundriver server version and document compatibility.

For VPN lifecycle changes, test Connect → Disconnect → Connect on an Android device. Verify that disconnect closes the TUN interface, stops the foreground service and permits reconnect. Also check notification disconnect and system VPN permission revocation. Build checks do not verify live server forwarding.

Describe the behavior changed and the checks performed in the pull request. Keep generated APKs, SDK paths, keystores, passwords, device identifiers and private server settings out of commits.

Contributions are licensed under the project GPL-2.0 license. Preserve the existing copyright and license notices.

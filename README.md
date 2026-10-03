# TunAndroid

Native Java Android client for the Linux [tundriver / tnet server](https://github.com/nmlpx09/tundriver). Android 8.0+; no root, native libraries or runtime dependencies. The Linux machine runs the server; the Android phone connects as a VPN client.

The app follows the phone's system light or dark appearance, including text, controls, dialogs and system bars.

The app forwards IPv4 TUN packets as UDP datagrams using the exact byte substitution from the server's `crypt/table.h`. There is no framing, handshake, authentication or secure encryption. This is the existing tnet protocol, not WireGuard or OpenVPN. HTTPS remains necessary for sensitive traffic. IPv6 is blocked while the tunnel is active.

## Build and install

Open this `tunandroid` folder in Android Studio, install Android SDK platform 35 and build tools 35.0.0, and allow Gradle sync. Use JDK 17 or 21. Alternatively, point `ANDROID_HOME` to your SDK and run:

```sh
./gradlew assembleDebug lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On Windows use `gradlew.bat`. The debug APK is signed for local installation. Configure a private release signing key before distributing a release build.

## Start the Linux server

Set up the server on a Linux machine using the [tundriver instructions](https://github.com/nmlpx09/tundriver#build--install):

```sh
git clone https://github.com/nmlpx09/tundriver.git
cd tundriver
make server
sudo make install_module
sudo make install_server
sudo tun c
```

`server.sh` defaults to UDP port **69** and `10.0.3.1/24` on `tnet0`. It enables IPv4 forwarding and adds outbound masquerading. Permit UDP 69 in the host firewall and cloud firewall, and forward it to the server if it is behind a router. If the host's forwarding policy drops traffic, permit forwarding from `tnet0` to the outbound interface and established return traffic. The existing script does not add those FORWARD rules. Change `SRC_PORT` in the server script if you need another port, then reinstall it.

## Connect the phone

1. Enter the server's reachable **numeric IPv4 address**. Hostnames and IPv6 endpoints are not supported.
2. Set the matching UDP port (default `69`).
3. Give the phone an unused address in the server subnet (default `10.0.3.2`). Do not use the server's `.1` address, subnet `.0`, broadcast `.255`, or an address assigned to another client. The current server indexes peers by the last IPv4 octet, so that octet must also be unique across all clients.
4. Use an IPv4 DNS resolver reachable through the server (default `1.1.1.1`). Set MTU between 576 and 1472; try 1400 for a smaller underlying path MTU.
5. Tap **Connect**, acknowledge the protocol notice, and approve Android's VPN request. Notification permission is optional; granting it makes the foreground VPN notification visible in the drawer.
6. Open an HTTPS website. Check that received bytes increase and the website sees the Linux server's public IP.

The tunnel can start even when the server is unreachable: UDP connect and TUN creation do not verify the server. Status indicates whether any valid server traffic has arrived, not a continuous health check. The foreground notification or app can disconnect the VPN. Profiles are saved privately on the phone. A process restart does not reconnect automatically; always-on VPN is disabled. After switching between Wi-Fi and cellular, disconnect and reconnect if traffic stalls.

## Verification

```sh
./tests/run.sh
./gradlew assembleDebug lintDebug
```

The standalone JDK test checks all 256 encode/decode entries against the actual kernel tables, packet round trips up to the server MTU, truncated/malformed IPv4 rejection, destination filtering and invalid configuration rejection. No Android SDK is needed for this test. The default reference table is bundled in `tests/fixtures`, so no server checkout is needed. To check another server revision, run `./tests/run.sh /path/to/tundriver/crypt/table.h`. Fixture provenance is documented in [tests/fixtures/README.md](tests/fixtures/README.md).

Device checks should cover: VPN consent, Wi-Fi/cellular forwarding, DNS, IPv6 blocking, notification disconnect, reconnect, permission revocation, and unreachable-server behavior. No live server connection or emulator test is included in the build checks.

## Release builds

```sh
./gradlew assembleRelease lintRelease
```

The output is `app/build/outputs/apk/release/app-release-unsigned.apk`. Sign it with your own Android release key before installation or distribution. Keep signing keys and passwords outside Git; local keystores and signing configuration files are ignored. Updates must use the same signing certificate as the installed version.

## GitHub Actions

[Android checks](.github/workflows/android.yml) runs protocol tests, builds both variants and runs Android lint on pushes to `main`, pull requests and manual runs. It saves a debug APK and an unsigned release APK as workflow artifacts. CI does not require signing credentials.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for the development checks and device test expectations.

## License

GPL-2.0, matching the tundriver server. The substitution table is derived from `crypt/table.h`, copyright 2026 nlmpx09. See [LICENSE](LICENSE).

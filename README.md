# 📡 All Radio Tools

**Everything that transmits around you, in one Android app.**

All Radio Tools brings together tools to explore Wi-Fi, Bluetooth, the mobile network, GNSS and, with a HackRF, the radio waves themselves: aircraft, gliders, radiosondes, ships, drones, satellites, walkie-talkies, sensors and meters.

> 🌍 Available in 22 languages (it follows the phone's language). Written in Kotlin with Jetpack Compose and Material 3.
>
> 🇫🇷 [Version française](README.fr.md)

---

## 📲 Install

1. Download the latest `AllRadioTools-vX.Y.apk` from the [Releases](https://github.com/ToftMalone/All-Radio-Tools/releases) page.
2. Allow installs from this source when Android asks.
3. Later updates are offered **inside the app**: it compares its version with the latest GitHub release.

**Requirements:** Android 12 or later (API 31). The app targets Android 17.

---

## 🧰 Tools

The home screen offers five networks. Each one has its dashboard, its main tool in the floating dock and a "Tools" page.

### 📶 Wi-Fi

| Tool | What it does |
| --- | --- |
| Wi-Fi scanner | Nearby networks, channels, congestion, access points of the connected network |
| Speed test | The [speed.cloudflare.com](https://speed.cloudflare.com) test, step by step: same measurements, same calculations (90th percentile throughput, idle and loaded latency and jitter, packet loss through Cloudflare's TURN relay), network quality score and the same CSV export as the site. On fast lines it ends with 500 MB then 1 GB each way over **six parallel connections**, like Ookla's app, so speeds above 600 Mbit/s are measured (skipped on metered connections) |
| Coverage map | Walk around and the map draws itself: in **augmented reality** (ARCore, ToF sensor when available) the floor is coloured under your feet like in WiFiman; without ARCore, a step counter and the compass trace your path |
| LAN devices | Discover the devices on your network |
| Ping, Traceroute, Port scan, DNS | The network diagnostic classics |
| Whois | Who owns a domain or an IP address |
| Wi-Fi Direct | Nearby Wi-Fi Direct devices |
| Wi-Fi Aware | What the phone's Wi-Fi Aware (NAN) supports, and the nearby phones with the tool open, with their distance through Wi-Fi RTT; wave at them to test the link |
| Network audit | Encryption, WPS, PMF, router |
| Rogue access points | "Evil twins" of the connected network |
| Man in the middle | ARP spoofing and rogue DHCP |

### 🔵 Bluetooth

| Tool | What it does |
| --- | --- |
| BLE scanner | Nearby devices with filters and identification (manufacturer, model, services) |
| BLE device | GATT exploration of a device |
| Hot/Cold | Find a device by its signal |
| Unknown trackers | Trackers that might be following you (AirTag, Find My accessories, AirPods, Google tags, Galaxy SmartTag, Tile, Chipolo, Pebblebee), each with a **drawing faithful to the real object**, sorted by detection time, with "play sound". A **background guard** keeps watching with the app closed and alerts you when a tracker follows you; tags connected to their owner are ignored, as in AirGuard |
| Beacons | iBeacon, Eddystone, AltBeacon |
| Earbuds battery | AirPods and Beats: left, right, case |

### 📱 Mobile network

| Tool | What it does |
| --- | --- |
| Serving cell | Identifiers and measurements of the connected cell (LTE, NR) |
| Neighbouring cells | Cells seen by the modem |
| Handover log | Cell changes while travelling, started by hand, CSV export |
| Cell tower map | Your carrier's towers, from ANFR open data (France) |
| Fake cell detector | Watches the network, even with the app closed, for the signs of an IMSI catcher: forced 2G, test network codes, inconsistent area codes, cells without neighbours, unusually strong new cells, no matching ANFR mast nearby |

### 🛰️ GNSS

| Tool | What it does |
| --- | --- |
| GNSS sky | Sky plot and world map of the satellites, pass prediction |
| Compare positions | GNSS, network and fused position |
| Atomic clock | Satellite UTC time to the millisecond, how far ahead or behind the phone is, GPS week, leap seconds, crystal drift |
| Compass and GNSS heading | Compares the compass (corrected for declination) with the satellite heading while you walk |
| Sky mask | Where buildings or trees hide the sky, from satellites that are present but silent |
| Jamming and spoofing | 8 indicators watched continuously (signal strength, AGC, lost satellites, signals too alike, jumps, agreement with the network, time, mock location) |
| Raw measurements | Bands received (dual frequency L1/L5, E1/E5a, B1C/B2a…), pseudoranges, carrier phase, multipath, **RINEX 3.04** export for RTKLIB or CSRS-PPP |

### 📻 SDR (HackRF, receive only)

| Tool | What it does |
| --- | --- |
| Spectrum analyser | Spectrum and waterfall, 1 MHz to 6 GHz |
| ACARS | Airliner messaging on the 3 European channels, 131 MHz |
| ADS-B | Map of aircraft on 1090 MHz, with the old Mode A/C replies (squawk, altitude) |
| AIS | Map of ships on 162 MHz |
| APRS | Ham radio beacons on 144.800 MHz |
| CubeSat | AX.25 telemetry of amateur satellites and the ISS (1200 AFSK, 9600 G3RUH), next passes and automatic Doppler correction |
| DECT | Cordless phone base stations (1880–1900 MHz): identity, carriers, slots; voice is never touched |
| DSC | Maritime digital selective calls on VHF channel 70: distress alerts, safety and routine calls, positions |
| Drones | DJI (DroneID), analogue FPV and remote controls (ExpressLRS, Crossfire, FrSky, FlySky, Spektrum) |
| FLARM | Gliders and light aircraft on 868 MHz (old protocol), positions around you; aircraft asking not to be tracked are only counted |
| ISM sensors | Weather stations, thermometers, energy monitors, door sensors, remotes on 433, 868, 315, 345 and 915 MHz (about 30 families) |
| LoRaWAN / Sigfox | Connected objects on 868 MHz: LoRaWAN headers (device address, network, counter) on SF7 to SF12, Sigfox uplinks |
| Meteor-M | Weather satellite LRPT images on 137 MHz |
| PMR446 | The 16 walkie-talkie channels at once: activity, CTCSS tone, listening to analogue channels and to DMR446 / dPMR446 voice (AMBE+2 decoded), their call ids |
| POCSAG | Pager messages (e*Message France and Germany, BOS, DAPNET or any frequency): every channel of a network at once, 512, 1200 and 2400 bit/s, BCH error correction |
| Radiosondes | Vaisala RS41, Meteomodem M10 / M20 and Horus amateur balloons around 403 and 434 MHz, with landing prediction |
| TPMS | Tyre pressure sensors of nearby cars on 433.92 MHz (or 315 MHz): pressure, temperature, battery, for Renault, PSA, Toyota, Ford, Hyundai/Kia, BMW/Audi, Porsche and more |
| VDL Mode 2 | The airliners' digital datalink on 6 channels around 136.9 MHz: ACARS carried digitally, ground stations, link set-ups |
| Wireless M-Bus | Water, heat, gas and electricity meters on 868.95 MHz (T1, C1); readings when not encrypted |

Every gain has an **automatic mode**, chosen by default, which picks the LNA and VGA values from the signal level; the value in use is shown in its own colour.

The decoders work with the **HackRF alone**: neither the phone's Wi-Fi nor its Bluetooth is used. The SDR card stays greyed out until a HackRF is plugged in over USB.

### 📤 Exports

Only when you ask, to the location you choose:
- **CSV**: speed test, coverage map, aircraft, radiosondes, ships, APRS, ACARS, VDL Mode 2, POCSAG, TPMS, ISM sensors, meters, DSC calls, LoRaWAN / Sigfox, handover log;
- **RINEX**: GNSS raw measurements;
- **PNG**: Meteor-M images.

The app keeps no copy: nothing is saved without your action.

---

## 🔐 Permissions

| Permission | Why |
| --- | --- |
| Precise location | Wi-Fi scans, cells, GNSS (required by Android) |
| Nearby devices | Bluetooth scanning and connection |
| Phone | Carrier, cells, dual SIM |
| Camera | Augmented reality coverage map (asked only when it starts) |
| Notifications | Alerts from the tracker guard and the fake cell detector, which run in the background |
| Local network (Android 17) | LAN scan, ping, port scan |
| Install apps | Built-in updates |

No account, no ads, no trackers. Internet is used for updates, the speed test (Cloudflare), satellite elements (CelesTrak) and cell tower data. Augmented reality uses "Google Play Services for AR" if installed; otherwise the step counter takes over.

---

## ⚖️ Responsible use

- The SDR is used **for reception only**. Follow your country's laws on listening to radio frequencies.
- The Wi-Fi security tools (audit, rogue access points, man in the middle, port scan) are meant for **your own networks** or those you are allowed to test.
- Pager messages (POCSAG) and walkie-talkie conversations (PMR446) are **private correspondence**: in many countries, France included, you may not pass on or use what you hear.
- "Unknown trackers" and "Jamming and spoofing" are there to **protect you**, not to track or disrupt others.

---

## 🛠️ Build

```bash
./gradlew :app:assembleDebug        # debug APK
./gradlew :app:testDebugUnitTest    # tests (Robolectric) and screenshots
./gradlew :app:lintDebug            # lint
```

You need a recent JDK and the Android SDK (API 37). The `scripts/` folder fetches the fonts and generates the world map.

### 🌍 Translations

English is the default language (`app/src/main/res/values/strings.xml`); each translation lives in its own folder, such as `values-fr/`. Every text the app shows goes through these files, including plurals.

### 🚀 Publishing a release

1. Update `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Write the tag in `.github/release-version` (e.g. `v1.3`) and the notes in `.github/release-notes/v1.3.md`.
   The notes are in English (that's what GitHub shows), followed by each translation in an HTML comment: `<!-- fr`, the French notes, then `-->`. The app shows the one matching the phone's language, otherwise English.
3. Push to `main`: the GitHub Actions workflow builds the signed APK and creates the release with `AllRadioTools-v1.3.apk`.

The workflow needs the `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD` secrets. Editing a notes file later updates the published release automatically.

---

## 📜 License

All Radio Tools is free software released under the [GNU General Public License v3.0](LICENSE): you may use, study, share and modify it, provided that derived versions stay under the same license and publish their source code.

---

## 🙏 Thanks

- [@cloudflare/speedtest](https://github.com/cloudflare/speedtest), the open source engine behind speed.cloudflare.com, ported for the speed test.
- [AirGuard](https://github.com/seemoo-lab/AirGuard) (SEEMOO, TU Darmstadt) for tracker detection and the rules that tell a tag near its owner from one following you.
- [rs1729/RS](https://github.com/rs1729/RS) for the radiosonde formats.
- [rtl_433](https://github.com/merbanan/rtl_433) for the tyre pressure and ISM sensor formats.
- [OnAir](https://github.com/adswill/OnAir) (GPL-3.0), whose decoders were ported for ISM sensors, FLARM, Wireless M-Bus, DSC, VDL Mode 2, DECT, DMR / dPMR, Sigfox, Horus, Mode A/C and CubeSat telemetry.
- [gr-lora_sdr](https://github.com/tapparelj/gr-lora_sdr) (EPFL) for the LoRa receiver.
- [mbelib](https://github.com/szechyjs/mbelib) (ISC) for the AMBE+2 voice decoder. AMBE+2 is covered by patents in some countries: the code is provided for research and education.
- Google [GNSS Measurement Tools](https://github.com/google/gps-measurement-tools) for the pseudorange computation.
- Roboto Flex, Google Sans Flex and Material Symbols for the interface.

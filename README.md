# 📡 All Radio Tools

☕ **Enjoy the app?** [Support it on Ko-fi](https://ko-fi.com/toftmalone)

**Everything that transmits around you, in one Android app.**

All Radio Tools brings together tools to explore Wi-Fi, Bluetooth, the mobile network, GNSS, NFC and, with a HackRF (or an RTL-SDR, an Airspy or a PlutoSDR), the radio waves themselves: aircraft, gliders, radiosondes, ships, drones, satellites, walkie-talkies, sensors and meters. NFC tags and contactless cards too.

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

The home screen offers six networks. Each one has its dashboard, its main tool in the floating dock and a "Tools" page.

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

The Internet tools (speed test, ping, traceroute, port scan, DNS lookup, whois) are **available over Wi-Fi only**: when the phone is not connected to Wi-Fi, they are greyed out and say so.

### 🔵 Bluetooth

| Tool | What it does |
| --- | --- |
| BLE scanner | Nearby devices with filters and identification (manufacturer, model, services) |
| BLE device | GATT exploration of a device |
| Hot/Cold | Find a device by its signal |
| Unknown trackers | Trackers that might be following you (AirTag, Find My accessories, AirPods, Google tags, Galaxy SmartTag, Tile, Chipolo, Pebblebee), each with a **drawing faithful to the real object**, sorted by detection time, with "play sound". A **background guard** keeps watching with the app closed and alerts you when a tracker follows you; tags connected to their owner are ignored, as in AirGuard |
| BLE spam detector | Pairing pop-up floods (Apple AirPods and Nearby Action, the iOS 17 crash frame, Google Fast Pair, Microsoft Swift Pair, Samsung Easy Setup, Lovespouse) told from real accessories: a burst of new addresses, one address walking a list, one radio behind several addresses, or a frame no product sends. Each alert says why, a history keeps them, and the Flipper Zeros in range are listed (body colour, name or address block). Signatures from [Bluetooth-LE-Spam](https://github.com/simondankelmann/Bluetooth-LE-Spam) and [Wall of Flippers](https://github.com/K3YOMI/Wall-of-Flippers); detection ideas by Melvin Olivet |
| Smart glasses | Camera glasses nearby: Ray-Ban Meta and Oakley Meta (Luxottica 0x0D53), Snap Spectacles (0x03C2, service 0xFE45), Meta glasses or Quest headsets (0x058E, 0x01AB, services 0xFD5F, 0xFEB7, 0xFEB8), HeyCyan-based glasses (Nilox…), Vuzix, and display-only glasses (Even Realities) told apart. Each match shows its evidence, a confidence and a rough distance; an alert distance (close, room, far) and a **background watch** that alerts with the app closed. Identifiers checked against the Bluetooth SIG's assigned numbers; findings of [Nearby Glasses](https://github.com/yjeanrenaud/yj_nearbyglasses), [NoPeek](https://github.com/getnopeek/nopeek-android) and ZuckOff |
| Beacons | iBeacon, Eddystone, AltBeacon |
| Earbuds battery | AirPods and Beats: left, right, case |

### 📱 Mobile network

| Tool | What it does |
| --- | --- |
| Serving cell | Identifiers and measurements of the connected cell (LTE, NR) |
| Neighbouring cells | Cells seen by the modem |
| Handover log | Cell changes while travelling, started by hand, CSV export |
| Cell tower map | Your carrier's towers, from ANFR open data (France) |
| Speed test, Ping, Traceroute, DNS lookup, Whois | The same tools as in Wi-Fi, **available over mobile data only**: while Wi-Fi is connected, or mobile data is off, they are greyed out and say so |
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

### 📲 NFC

| Tool | What it does |
| --- | --- |
| Tag reader | Chip, memory, content and identity of any tag or card (NDEF link, text, contact, Wi-Fi, location, app…), readable or locked, and what the phone itself supports (reader, card emulation, secure element) |
| Write a tag | Link, Wi-Fi, contact, text, location, phone number, SMS, email, app: every tag held to the phone while it is armed receives the content |
| Copy a tag | The NDEF content of one tag onto another (not the identifier, nor protected memory) |
| Erase or lock | Empty a tag, or make it read-only for good (asks for confirmation: it cannot be undone) |
| Bank card | What a contactless card tells any terminal, without the PIN: network, application, expiry date, country, currency and the latest payments. The full card number is never shown nor kept |
| Memory | Every page and sector the tag lets you read; MIFARE Classic sectors are opened only with the makers' public default keys |
| Antenna and reliability | Slide a tag over the phone to find where its antenna is, and measure how reliable the link stays as it moves away |

Nothing is kept once the app is closed.

### 📻 SDR (HackRF and others)

> **Radios other than the HackRF (new, untested on real hardware):** the SDR tools also accept an **RTL-SDR** dongle (R820T / R820T2 / R828D tuner, 24 to 1766 MHz, up to about 3 MS/s), an **Airspy** (R2 or Mini, 24 to 1750 MHz, used at 2.5 or 3 MS/s and resampled) and an **ADALM-Pluto / PlutoSDR** (70 MHz to 6 GHz, over its USB cable, resampled from 2.1 MS/s or more). A tool whose frequency or sample rate is out of reach of the radio plugged in says so instead of starting: the 2.4 GHz and 5.8 GHz tools (Zigbee, ANT+, drones, FPV) and the 4 MS/s ones need a HackRF (or a Pluto for 4 MS/s). The drivers were written from the open source libraries and tested against a simulated USB bus only. SDRplay radios are not supported: their driver is closed source.

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
| FLARM | Gliders and light aircraft on 868 MHz (both the old protocol and the one since 2024), positions around you; aircraft asking not to be tracked are only counted |
| ISM sensors | Weather stations, thermometers, energy monitors, door sensors, remotes on 433, 868, 315, 345 and 915 MHz (about 40 families, Lidl Auriol, Oregon Scientific, Hideki, Ecowitt among them) |
| LoRaWAN / Sigfox | Connected objects on 868 MHz: LoRaWAN headers (device address, network named from the LoRa Alliance's NetID table, counter) on SF7 to SF12, with an option to listen to all eight EU868 uplink channels at once, and Sigfox uplinks |
| Meshtastic | The LoRa mesh network on 868 MHz (869.525 MHz and its neighbours, every European preset), 433 MHz or any free frequency from 400 to 1000 MHz with the preset of your choice (US 915 MHz band included): nodes (name, hardware, role, battery, position on a map), text messages and telemetry on the default channels, and on channels you add with their key; private messages stay encrypted |
| MeshCore | The LoRa mesh network with repeaters on 869.618 MHz (62.5 kHz, SF7 to SF12) and 869.525 MHz: signed node adverts (name, type, position on a map), the Public channel and the #hashtag channels you add, the route each flood took |
| LoRa APRS | Radio amateurs' APRS beacons over LoRa on 433.775 MHz (SF12) and 434.855 MHz (SF9), heard at once: stations, symbols, comments and weather on a map |
| 406 MHz beacons | COSPAS-SARSAT distress beacons (EPIRB, ELT, PLB), first generation, 406.0 to 406.1 MHz: identity (15 hex), country, protocol, MMSI or aircraft address, GPS position when sent, self-tests marked; checked on a real PLB recording |
| Z-Wave | Home automation on 868.40, 868.42 and 869.85 MHz (R1, R2, R3 at once): networks (home id), nodes, command classes in the clear (switch, battery, sensors…); S0 / S2 encrypted frames counted, never opened |
| SSTV | Slow-scan TV pictures over FM, the ISS on 145.800 MHz or any frequency: VIS header, Robot, Martin, Scottie, PD (PD120 / PD180 as the ISS uses) and Wraase modes, Doppler followed, slant corrected, pictures saved as PNG |
| Zigbee / 802.15.4 | Zigbee, Thread and other 802.15.4 networks at 2.4 GHz, one channel or all sixteen in turn: PAN IDs, addresses and chip vendors, beacons (joining open or closed), Zigbee network header and clusters when not encrypted |
| ANT+ | Sports sensors on 2457 MHz: heart rate and R-R interval, bike power, cadence and speed, footpods, temperature, manufacturer and battery, with the public ANT+ profiles |
| Listen | Listen to stations: the 2 MHz around a frequency shown as a spectrum, tap or drag to tune, and the channel played as **WFM** (broadcast FM), **NFM** (walkie-talkies, marine), **AM** (aviation, shortwave) or **USB / LSB**, with volume, squelch and presets |
| Meteor-M | Weather satellite LRPT images on 137.100 and 137.900 MHz, from the older satellites and from the Meteor-M2-x that transmit with offset QPSK |
| PMR446 | The 16 walkie-talkie channels at once: activity, CTCSS tone, listening to analogue channels and to DMR446 / dPMR446 voice (AMBE+2 decoded), their call ids |
| POCSAG | Pager messages (e*Message France and Germany, BOS, DAPNET or any frequency): every channel of a network at once, 512, 1200 and 2400 bit/s, BCH error correction |
| Radiosondes | Vaisala RS41 (damaged frames repaired by their Reed-Solomon code), Meteomodem M10 / M10+ / M20 and Horus amateur balloons around 403 and 434 MHz, with landing prediction |
| TPMS | Tyre pressure sensors of nearby cars on 433.92 MHz (or 315 MHz): pressure, temperature, battery, for Renault, PSA, Toyota, Ford, Hyundai/Kia, BMW/Audi, Porsche, Honda (TRW), Schrader (Opel/GM, Subaru/Nissan/Renault Koleos, BMW) and more |
| VDL Mode 2 | The airliners' digital datalink on 6 channels around 136.9 MHz: ACARS carried digitally, ground stations, link set-ups |
| Wireless M-Bus | Water, heat, gas and electricity meters on 868.95 MHz (T1, C1), even when the meter's clock is a little off; readings when not encrypted |

#### 📡 Transmitting (HackRF only)

The HackRF can also transmit, only when you ask it to and only where it is allowed:

- **Amateur bands** (all three ITU regions together), with your **call sign** and the box confirming you hold an **amateur radio licence**, both in the transmit settings of each tool:
  - **Listen**: hold **Talk** to send the microphone in NFM, AM, USB or LSB (never in WFM); reception pauses while you talk.
  - **APRS**: your own **position beacon** (AX.25, 1200 baud AFSK, FM) with SSID, symbol and comment, once or every 10 to 30 minutes.
  - **SSTV**: a **photo** from the phone, with your call sign written on it, in Robot 36, Martin M1, Scottie S1 or PD120, on 144.500 MHz FM or 14.230 / 7.171 / 3.735 MHz SSB.
- **Licence-free mesh band** (863–870 MHz, 433 MHz, 902–928 MHz):
  - **Meshtastic**: the radio joins the mesh as a **node** on the default LongFast channel: it announces itself (NodeInfo) when started and every three hours, and sends the phone's position every 15 to 60 minutes, at the precision you choose.

Every transmission is checked against these bands before it starts (and again in the radio driver), never goes out through a converter, and each transmitter was checked in tests by the app's own receivers. Transmit gain and the 14 dB amplifier are set in the transmit settings.

Every gain has an **automatic mode**, chosen by default, modelled on [OnAir](https://github.com/adswill/OnAir)'s auto-gain guide: it moves over the radio's whole range (LNA first for the best noise figure, then the VGA in 2 dB steps) to keep the receiver's noise well above the 8-bit converter's own, cuts at once on clipping and climbs back slowly, with a profile per tool (narrow signals, wide channels, ADS-B pulses, broadcast FM that spares the LNA). It was tuned against a simulated HackRF front end in the tests; the values in use are shown under the gain chips.

The decoders work with the **radio alone**: neither the phone's Wi-Fi nor its Bluetooth is used. The SDR card stays greyed out until a supported radio is plugged in over USB. No driver to install: the app talks to the radio itself through Android's USB host support (a USB-C OTG cable is enough; a powered hub helps if the phone cuts the power).

### 🚗 Wi-Fi + Bluetooth: wardriving

**Select the Wi-Fi card** (or Bluetooth): a **+** appears on the other one. Tap it: the two cards slide together into a single tile, with a dock for the pair and **Wardriving** as its featured tool. The unlink button on the tile takes them apart again.

- A recording dial with the GPS fix, counters for the run and the database, then a compact list of every Wi-Fi network and Bluetooth LE device heard, strongest first: an icon per Wi-Fi protection (open, OWE, WEP, WPA, WPA2, WPA2/3, WPA3, EAP), the device icon for Bluetooth devices identified without doubt, the kind of Bluetooth address (public, static, resolvable, anonymous) and the maker (IEEE OUI register).
- Recording runs in a **foreground service**: it goes on with the screen off and the app closed, until you stop it.
- Everything stays in a database on the phone. **Upload to WiGLE.net** is manual only, with your account's API name and token (never your password). **Export** saves the whole database as a WiGLE CSV file.
- Exclude a full MAC address or a maker prefix (OUI), one by one or by importing a list (and export it): it stays in the list, faded and marked in red, but is never recorded nor sent, and is erased from the database. The list is applied before the first scan of a capture.
- **Hidden cameras**: Wi-Fi networks and Bluetooth devices from camera makers or with camera names, a warm / cold signal to walk to one, and the video ports (RTSP, ONVIF, Hikvision, Dahua, XMEye) of the devices on the Wi-Fi network you are connected to. Clues, not proof: a wired camera sends nothing.
- **Drone detector**: Remote ID (EU / US electronic identification) broadcast over Bluetooth LE (legacy and long range) and Wi-Fi (beacons, Wi-Fi Aware): drone position, height, speed, operator position and id, on a map, with a background engine that keeps listening with the app closed.

### 📤 Exports

Only when you ask, to the location you choose:
- **CSV**: speed test, coverage map, aircraft, radiosondes, ships, APRS, ACARS, VDL Mode 2, POCSAG, TPMS, ISM sensors, meters, DSC calls, LoRaWAN / Sigfox, Meshtastic and MeshCore packets, LoRa APRS, 406 MHz beacons, Z-Wave, Zigbee, ANT+, handover log, wardriving (WiGLE format);
- **RINEX**: GNSS raw measurements;
- **PNG**: Meteor-M and SSTV images;
- **Debug log** (Settings, App): a detailed journal of the app, at most 50 MB, to share or save when reporting a bug, or to clear.

The app keeps no copy: nothing is saved without your action.

The debug log stays on the phone until you share or save it.

---

## 🔐 Permissions

| Permission | Why |
| --- | --- |
| Precise location | Wi-Fi scans, cells, GNSS (required by Android) |
| Nearby devices | Bluetooth scanning and connection |
| Phone | Carrier, cells, dual SIM |
| Camera | Augmented reality coverage map (asked only when it starts) |
| NFC | Reading and writing tags and cards (no prompt: Android only asks you to turn NFC on) |
| Notifications | Alerts from the tracker guard and the fake cell detector, which run in the background |
| Local network (Android 17) | LAN scan, ping, port scan |
| Install apps | Built-in updates |

No account, no ads, no trackers. Internet is used for updates, the speed test (Cloudflare), satellite elements (CelesTrak) and cell tower data. Augmented reality uses "Google Play Services for AR" if installed; otherwise the step counter takes over.

---

## ⚖️ Responsible use

- The SDR transmits **only from the four transmit features above**, on the amateur bands with your call sign and licence, or on the licence-free mesh band. You answer for what you transmit: follow your country's band plan, power and time-on-air limits, and use a suitable antenna or filter, as the HackRF also emits harmonics. Jamming, spoofing (GPS, ADS-B, AIS), distress beacons, broadcast and replaying other people's remotes are not offered and never will be.
- Follow your country's laws on listening to radio frequencies.
- The Wi-Fi security tools (audit, rogue access points, man in the middle, port scan) are meant for **your own networks** or those you are allowed to test.
- Pager messages (POCSAG) and walkie-talkie conversations (PMR446) are **private correspondence**: in many countries, France included, you may not pass on or use what you hear.
- The NFC bank card reader shows only what any terminal reads without the PIN, never the full number, and keeps nothing. Reading, writing or copying a card you do not own, or a badge you are not allowed to copy, is not what it is for.
- Locking a tag is **permanent**: no phone can write to it again.
- Wardriving records where networks are; once sent to WiGLE.net they are public. Exclude the addresses you do not want there.
- The BLE spam detector only listens: it is there to see an attack, never to send one.
- The smart glasses detector says camera-capable glasses are in range, not that they are recording nor who wears them: never harass anyone because of an alert.
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
3. Start the GitHub Actions workflow "Release": by hand (Actions → Release → Run workflow), by pushing a `v*` tag, or by a push that **changes `.github/release-version`** (on **any** branch, not only `main`: only change that file when you mean to publish). It builds the signed APK and creates the release with `AllRadioTools-v1.3.apk`. A tag that already exists keeps pointing at its commit: delete the tag first when you publish again.

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
- [OnAir](https://github.com/adswill/OnAir) (GPL-3.0), whose decoders were ported for ISM sensors, FLARM, Wireless M-Bus, DSC, VDL Mode 2, DECT, DMR / dPMR, Sigfox, Horus, Mode A/C, CubeSat telemetry, 406 MHz beacons, Z-Wave, SSTV, Zigbee / 802.15.4 and ANT+ (its recording of a real PLB checked the 406 MHz decoder).
- [SoftRF](https://github.com/lyusupov/SoftRF) (GPL-3.0), whose FLARM code (frame CRC, the 2024 protocol's keys and layout) was ported.
- [gr-lora_sdr](https://github.com/tapparelj/gr-lora_sdr) (EPFL) for the LoRa receiver.
- [Meshtastic](https://github.com/meshtastic/firmware) (firmware and protobufs, GPL-3.0) and [MeshCore](https://github.com/meshcore-dev/MeshCore) (MIT) for their packet formats; the real MeshCore packets of [meshcore-decoder](https://github.com/michaelhart/meshcore-decoder) and the reference vector of [meshtastic-sniffer](https://github.com/alphafox02/meshtastic-sniffer), against which the decoders were checked.
- The recordings and sources of [rtl_433_tests](https://github.com/merbanan/rtl_433_tests), [dumpvdl2](https://github.com/szpajder/dumpvdl2), [multimon-ng](https://github.com/EliasOenal/multimon-ng), [horusdemodlib](https://github.com/projecthorus/horusdemodlib), [DSD-FME](https://github.com/lwvmobile/dsd-fme) and [SatDump](https://github.com/SatDump/SatDump), against which the decoders were checked, and sigidwiki's VDL-M2 recording.
- [mbelib](https://github.com/szechyjs/mbelib) (ISC) for the AMBE+2 voice decoder. AMBE+2 is covered by patents in some countries: the code is provided for research and education.
- [librtlsdr](https://github.com/osmocom/rtl-sdr) (osmocom, GPL-2.0-or-later) and [libairspy](https://github.com/airspy/airspyone_host) (BSD-3-Clause) for the RTL-SDR and Airspy drivers, and [libiio](https://github.com/analogdevicesinc/libiio) (LGPL-2.1-or-later) for the PlutoSDR one.
- [WiGLE](https://wigle.net) for the wardriving file format and the upload API.
- Google [GNSS Measurement Tools](https://github.com/google/gps-measurement-tools) for the pseudorange computation.
- Roboto Flex, Google Sans Flex and Material Symbols for the interface.

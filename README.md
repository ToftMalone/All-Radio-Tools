# 📡 All Radio Tools

**Tout ce qui émet autour de vous, dans une seule application Android.**

All Radio Tools réunit les outils pour explorer le Wi-Fi, le Bluetooth, le réseau mobile, le GNSS et, avec un HackRF, les ondes radio : avions, ballons-sondes, navires, drones, satellites météo, réseaux Meshtastic, et même le GPS reçu par le HackRF seul.

> 🌍 Application en français et en anglais (elle suit la langue du téléphone), écrite en Kotlin avec Jetpack Compose et Material 3.

---

## 📲 Installer

1. Téléchargez le dernier `AllRadioTools-vX.Y.apk` depuis la page [Releases](https://github.com/ToftMalone/All-Radio-Tools/releases).
2. Autorisez l'installation depuis cette source quand Android le demande.
3. Les mises à jour suivantes sont proposées **dans l'application** : elle compare sa version à la dernière release GitHub.

**Prérequis :** Android 12 ou plus (API 31). L'application vise Android 17.

---

## 🧰 Les outils

L'écran d'accueil propose six réseaux. Chacun a son tableau de bord, son outil principal dans le dock flottant et une page « Outils ».

### 📶 Wi-Fi

| Outil | À quoi ça sert |
| --- | --- |
| Scanner Wi-Fi | Réseaux alentour, canaux, occupation, points d'accès du réseau connecté |
| Test de débit | Le test de [speed.cloudflare.com](https://speed.cloudflare.com), étape par étape : mêmes mesures, mêmes calculs (débit au 90ᵉ centile, latence et gigue au repos et sous charge, perte de paquets via le relais TURN de Cloudflare), score de qualité réseau et export CSV identique au site |
| Carte de couverture | On marche, la carte se dessine : en **réalité augmentée** (ARCore, capteur ToF quand il existe) le sol se colore sous vos pas comme dans WiFiman ; sans ARCore, un podomètre et la boussole tracent le parcours |
| Appareils du LAN | Découvrir les appareils du réseau |
| Ping, Traceroute, Scan de ports, DNS | Les classiques du diagnostic réseau |
| Whois | Propriétaire d'un domaine ou d'une adresse IP |
| Wi-Fi Direct | Appareils Wi-Fi Direct à proximité |
| Audit du réseau | Chiffrement, WPS, PMF, box |
| Faux points d'accès | « Evil twin » du réseau connecté |
| Homme du milieu | ARP spoofing et DHCP pirate |

### 🔵 Bluetooth

| Outil | À quoi ça sert |
| --- | --- |
| Scanner BLE | Appareils à proximité avec filtres et identification (fabricant, modèle, services) |
| Appareil BLE | Exploration GATT d'un appareil |
| Chaud/Froid | Retrouver un appareil au signal |
| Traqueurs inconnus | Les traqueurs qui pourraient vous suivre (AirTag, accessoires Localiser, AirPods, tags Google, Galaxy SmartTag, Tile), chacun avec son **dessin fidèle à l'objet**, triés par date de détection, avec « faire sonner » |
| Balises | iBeacon, Eddystone, AltBeacon |
| Batterie des écouteurs | AirPods et Beats : gauche, droite, boîtier |

### 📱 Réseau mobile

| Outil | À quoi ça sert |
| --- | --- |
| Cellule de service | Identifiants et mesures de la cellule connectée (LTE, NR) |
| Cellules voisines | Cellules détectées par le modem |
| Journal de handover | Changements de cellule en trajet, lancé à la main, export CSV |
| Carte des antennes | Antennes de votre opérateur, d'après les données ouvertes de l'ANFR |

### 🛰️ GNSS

| Outil | À quoi ça sert |
| --- | --- |
| Ciel GNSS | Sky plot et carte du monde des satellites, prédiction des passages |
| Comparer les positions | GNSS, réseau et position fusionnée |
| Horloge atomique | Heure UTC des satellites à la milliseconde, avance ou retard du téléphone, semaine GPS, secondes intercalaires, dérive du quartz |
| Boussole et cap GNSS | Compare la boussole (corrigée de la déclinaison) au cap des satellites en marchant |
| Masque du ciel | Où le ciel est caché par les immeubles ou les arbres, d'après les satellites présents mais muets |
| Brouillage et leurrage | 8 indices surveillés en continu (force des signaux, AGC, satellites perdus, signaux trop semblables, sauts, accord avec le réseau, heure, position simulée) |
| Mesures brutes | Bandes captées (bi-fréquence L1/L5, E1/E5a, B1C/B2a…), pseudodistances, phase, multitrajet, export **RINEX 3.04** pour RTKLIB ou CSRS-PPP |

### 📻 SDR (HackRF, en réception uniquement)

| Outil | Contenu |
| --- | --- |
| Analyseur de spectre | Spectre et chute d'eau, de 1 MHz à 6 GHz |
| Avions (ADS-B) | Carte des avions sur 1090 MHz |
| Messages d'avions (ACARS) | Messagerie des avions de ligne sur les 3 canaux européens, 131 MHz |
| Ballons-sondes | Vaisala RS41 et Meteomodem M10 / M20 vers 403 MHz, avec prédiction d'atterrissage |
| Radio FM | Écoute et RDS |
| Navires (AIS) | Carte des navires sur 162 MHz |
| Radioamateurs (APRS) | Balises sur 144,800 MHz |
| Meshtastic | Réception et déchiffrement des trames LoRa : choix de la région, du préréglage, de la fréquence et des canaux |
| Récepteur GPS | La position calculée par le HackRF seul, avec son antenne télescopique, sur 1575,42 MHz |
| Satellites météo (Meteor-M) | Images LRPT sur 137 MHz |
| Drones | FPV analogique, DJI (DroneID) et radiocommandes (ExpressLRS, Crossfire, FrSky, FlySky, Spektrum) |

Les décodeurs fonctionnent avec le **HackRF seul** : ni le Wi-Fi ni le Bluetooth du téléphone ne sont utilisés. La carte SDR reste grisée tant qu'aucun HackRF n'est branché en USB.

### 🔌 Talkie-walkie

Lire, modifier et réécrire les mémoires d'un **Baofeng UV-5R**, d'un **Radtel RT-470X** ou d'un **Quansheng UV-K5 / UV-K6 / UV-5R Plus** par câble de programmation USB (puces CH340, CP210x, FTDI, PL2303), avec des préréglages (PMR446, relais, marine…). Sans câble, la carte reste consultable mais les outils ne se lancent pas.

### 📤 Exports

Uniquement quand vous le demandez, à l'emplacement que vous choisissez :
- **CSV** : test de débit, carte de couverture, avions, ballons-sondes, navires, APRS, Meshtastic, journal de handover ;
- **RINEX** : mesures brutes GNSS ;
- **PNG** : images Meteor-M.

L'application ne garde aucune copie : rien n'est enregistré sans votre action.

---

## 🔐 Autorisations

| Autorisation | Pourquoi |
| --- | --- |
| Position précise | Scans Wi-Fi, cellules, GNSS (imposée par Android) |
| Appareils à proximité | Scan et connexion Bluetooth |
| Téléphone | Opérateur, cellules, double SIM |
| Caméra | Carte de couverture en réalité augmentée (demandée seulement à son lancement) |
| Réseau local (Android 17) | Scan du LAN, ping, scan de ports |
| Installer des applications | Mise à jour intégrée |

Aucun compte, aucune publicité, aucun traceur. Internet sert aux mises à jour, au test de débit (Cloudflare), aux éléments de satellites (CelesTrak) et aux données d'antennes. La réalité augmentée utilise « Services Google Play pour la RA » s'il est installé ; sinon le podomètre prend le relais.

---

## ⚖️ Usage responsable

- Le SDR est utilisé **en réception seulement**. Respectez la législation de votre pays sur l'écoute des fréquences.
- Les outils de sécurité Wi-Fi (audit, faux points d'accès, homme du milieu, scan de ports) sont faits pour **vos propres réseaux** ou ceux que vous avez l'autorisation de tester.
- « Traqueurs inconnus » et « Brouillage et leurrage » servent à **vous protéger**, pas à pister ou gêner les autres.

---

## 🛠️ Compiler

```bash
./gradlew :app:assembleDebug        # APK de debug
./gradlew :app:testDebugUnitTest    # tests (Robolectric) et captures d'écran
./gradlew :app:lintDebug            # lint
```

Il faut un JDK récent et le SDK Android (API 37). Les scripts `scripts/` servent à récupérer les polices et à générer la carte du monde.

### 🚀 Publier une version

1. Mettre à jour `versionCode` et `versionName` dans `app/build.gradle.kts`.
2. Écrire le tag dans `.github/release-version` (par ex. `v1.3`) et les notes dans `.github/release-notes/v1.3.md`.
   Les notes sont en anglais (c'est ce qu'affiche GitHub), suivies de chaque traduction dans un commentaire HTML : `<!-- fr`, les notes en français, puis `-->`. L'application montre celle qui correspond à la langue du téléphone, sinon l'anglais.
3. Pousser sur `main` : le workflow GitHub Actions construit l'APK signé et crée la release avec `AllRadioTools-v1.3.apk`.

Le workflow a besoin des secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` et `KEY_PASSWORD`.

---

## 🙏 Remerciements

- [@cloudflare/speedtest](https://github.com/cloudflare/speedtest), le moteur open source de speed.cloudflare.com, porté pour le test de débit.
- [AirGuard](https://github.com/seemoo-lab/AirGuard) (SEEMOO, TU Darmstadt) pour la détection des traqueurs.
- [gr-lora_sdr](https://github.com/tapparelj/gr-lora_sdr) (EPFL) et [Meshtastic](https://meshtastic.org) pour la couche LoRa et le protocole.
- [rs1729/RS](https://github.com/rs1729/RS) pour les formats de sondes.
- Google [GNSS Measurement Tools](https://github.com/google/gps-measurement-tools) pour le calcul des pseudodistances.
- Roboto Flex, Google Sans Flex et Material Symbols pour l'interface.

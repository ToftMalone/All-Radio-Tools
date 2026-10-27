# 📡 All Radio Tools

**Tout ce qui émet autour de vous, dans une seule application Android.**

All Radio Tools réunit les outils pour explorer le Wi-Fi, le Bluetooth, le réseau mobile, le GNSS et, avec un HackRF, les ondes radio : avions, planeurs, ballons-sondes, navires, drones, satellites, talkies-walkies, capteurs et compteurs.

> 🌍 Application disponible en 22 langues (elle suit la langue du téléphone), écrite en Kotlin avec Jetpack Compose et Material 3.
>
> 🇬🇧 [English version](README.md)

---

## 📲 Installer

1. Téléchargez le dernier `AllRadioTools-vX.Y.apk` depuis la page [Releases](https://github.com/ToftMalone/All-Radio-Tools/releases).
2. Autorisez l'installation depuis cette source quand Android le demande.
3. Les mises à jour suivantes sont proposées **dans l'application** : elle compare sa version à la dernière release GitHub.

**Prérequis :** Android 12 ou plus (API 31). L'application vise Android 17.

---

## 🧰 Les outils

L'écran d'accueil propose cinq réseaux. Chacun a son tableau de bord, son outil principal dans le dock flottant et une page « Outils ».

### 📶 Wi-Fi

| Outil | À quoi ça sert |
| --- | --- |
| Scanner Wi-Fi | Réseaux alentour, canaux, occupation, points d'accès du réseau connecté |
| Test de débit | Le test de [speed.cloudflare.com](https://speed.cloudflare.com), étape par étape : mêmes mesures, mêmes calculs (débit au 90ᵉ centile, latence et gigue au repos et sous charge, perte de paquets via le relais TURN de Cloudflare), score de qualité réseau et export CSV identique au site. Sur les lignes rapides, il se termine par 500 Mo puis 1 Go dans chaque sens sur **six connexions en parallèle**, comme l'app Ookla, pour mesurer au-delà de 600 Mbit/s (ignoré sur les connexions limitées) |
| Carte de couverture | On marche, la carte se dessine : en **réalité augmentée** (ARCore, capteur ToF quand il existe) le sol se colore sous vos pas comme dans WiFiman ; sans ARCore, un podomètre et la boussole tracent le parcours |
| Appareils du LAN | Découvrir les appareils du réseau |
| Ping, Traceroute, Scan de ports, DNS | Les classiques du diagnostic réseau |
| Whois | Propriétaire d'un domaine ou d'une adresse IP |
| Wi-Fi Direct | Appareils Wi-Fi Direct à proximité |
| Wi-Fi Aware | Ce que gère le Wi-Fi Aware (NAN) du téléphone, et les téléphones à proximité qui ont l'outil ouvert, avec leur distance par Wi-Fi RTT ; un coucou pour tester le lien |
| Audit du réseau | Chiffrement, WPS, PMF, box |
| Faux points d'accès | « Evil twin » du réseau connecté |
| Homme du milieu | ARP spoofing et DHCP pirate |

### 🔵 Bluetooth

| Outil | À quoi ça sert |
| --- | --- |
| Scanner BLE | Appareils à proximité avec filtres et identification (fabricant, modèle, services) |
| Appareil BLE | Exploration GATT d'un appareil |
| Chaud/Froid | Retrouver un appareil au signal |
| Traqueurs inconnus | Les traqueurs qui pourraient vous suivre (AirTag, accessoires Localiser, AirPods, tags Google, Galaxy SmartTag, Tile, Chipolo, Pebblebee), chacun avec son **dessin fidèle à l'objet**, triés par date de détection, avec « faire sonner ». Une **garde en arrière-plan** continue de surveiller app fermée et vous alerte quand un traqueur vous suit ; les balises connectées à leur propriétaire sont ignorées, comme dans AirGuard |
| Balises | iBeacon, Eddystone, AltBeacon |
| Batterie des écouteurs | AirPods et Beats : gauche, droite, boîtier |

### 📱 Réseau mobile

| Outil | À quoi ça sert |
| --- | --- |
| Cellule de service | Identifiants et mesures de la cellule connectée (LTE, NR) |
| Cellules voisines | Cellules détectées par le modem |
| Journal de handover | Changements de cellule en trajet, lancé à la main, export CSV |
| Carte des antennes | Antennes de votre opérateur, d'après les données ouvertes de l'ANFR |
| Détecteur de fausses antennes | Surveille le réseau, même app fermée, pour repérer les signes d'un IMSI-catcher : 2G forcée, codes de réseau de test, codes de zone incohérents, cellules sans voisines, nouvelles cellules anormalement fortes, aucune antenne ANFR correspondante à proximité |

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
| ACARS | Messagerie des avions de ligne sur les 3 canaux européens, 131 MHz |
| ADS-B | Carte des avions sur 1090 MHz, avec les anciennes réponses Mode A/C (squawk, altitude) |
| AIS | Carte des navires sur 162 MHz |
| APRS | Balises radioamateurs sur 144,800 MHz |
| Ballons-sondes | Vaisala RS41, Meteomodem M10 / M20 et ballons amateurs Horus vers 403 et 434 MHz, avec prédiction d'atterrissage |
| Capteurs ISM | Stations météo, thermomètres, compteurs d'énergie, détecteurs d'ouverture, télécommandes sur 433, 868, 315, 345 et 915 MHz (une trentaine de familles) |
| CubeSat | Télémesure AX.25 des satellites amateurs et de l'ISS (AFSK 1200, G3RUH 9600), prochains passages et correction Doppler automatique |
| DECT | Bases de téléphones sans fil (1880–1900 MHz) : identité, porteuses, intervalles ; la voix n'est jamais touchée |
| DSC (ASN) | Appels sélectifs numériques maritimes sur le canal VHF 70 : alertes de détresse, appels de sécurité et de routine, positions |
| Drones | DJI (DroneID), FPV analogique et radiocommandes (ExpressLRS, Crossfire, FrSky, FlySky, Spektrum) |
| FLARM | Planeurs et aviation légère sur 868 MHz (ancien protocole), positions autour de vous ; les aéronefs qui refusent le suivi sont seulement comptés |
| LoRaWAN / Sigfox | Objets connectés sur 868 MHz : en-têtes LoRaWAN (adresse, réseau, compteur) de SF7 à SF12, messages Sigfox |
| Meteor-M | Images LRPT des satellites météo sur 137 MHz |
| PMR446 | Les 16 canaux de talkies-walkies à la fois : activité, tonalité CTCSS, écoute des canaux analogiques et de la voix DMR446 / dPMR446 (décodage AMBE+2), identifiants des appels |
| POCSAG | Messages de pagers (e*Message France et Allemagne, BOS, DAPNET ou n'importe quelle fréquence) : tous les canaux d'un réseau à la fois, 512, 1200 et 2400 bit/s, correction d'erreurs BCH |
| TPMS | Capteurs de pression des pneus des voitures alentour sur 433,92 MHz (ou 315 MHz) : pression, température, pile, pour Renault, PSA, Toyota, Ford, Hyundai/Kia, BMW/Audi, Porsche et d'autres |
| VDL Mode 2 | Liaison de données numérique des avions de ligne sur 6 canaux vers 136,9 MHz : ACARS numérique, stations sol, ouvertures de liaison |
| Wireless M-Bus | Compteurs d'eau, de chaleur, de gaz et d'électricité sur 868,95 MHz (T1, C1) ; relevés quand ils ne sont pas chiffrés |

Chaque gain a un **mode automatique**, choisi par défaut, qui règle le LNA et le VGA d'après le niveau du signal ; la valeur utilisée s'affiche dans sa propre couleur.

Les décodeurs fonctionnent avec le **HackRF seul** : ni le Wi-Fi ni le Bluetooth du téléphone ne sont utilisés. La carte SDR reste grisée tant qu'aucun HackRF n'est branché en USB.

### 🚗 Wi-Fi + Bluetooth : wardriving

**Appui long sur la carte Wi-Fi** (ou Bluetooth), puis touchez le **+** qui apparaît sur l'autre : les deux cartes glissent l'une vers l'autre et forment une seule case, avec un dock pour la paire et le **Wardriving** comme outil phare. Le bouton de séparation sur la case les désassemble.

- L'écran d'accueil de WiGLE : réseaux et appareils nouveaux de la session, total de la base, position GPS, dernier scan, puis chaque réseau Wi-Fi et appareil Bluetooth LE entendu, du plus fort au plus faible, avec son constructeur (registre OUI de l'IEEE).
- L'enregistrement tourne dans un **service de premier plan** : il continue écran éteint et application fermée, jusqu'à ce que vous l'arrêtiez.
- Tout reste dans une base de données sur le téléphone. **L'envoi vers WiGLE.net** est uniquement manuel, avec le nom d'API et le jeton de votre compte (jamais votre mot de passe).
- Excluez une adresse MAC complète ou un préfixe constructeur (OUI) : il n'est ni affiché ni enregistré, et il est effacé de la base.
- **Carte du wardriving** : chaque réseau enregistré là où il a été entendu le plus fort, coloré selon sa sécurité (ouvert, WEP, WPA, WPA3, Bluetooth), avec filtres et détails.
- **Caméras cachées** : réseaux Wi-Fi et appareils Bluetooth de fabricants de caméras ou portant un nom de caméra, un signal chaud / froid pour s'en approcher, et les ports vidéo (RTSP, ONVIF, Hikvision, Dahua, XMEye) des appareils du réseau Wi-Fi auquel vous êtes connecté. Des indices, pas des preuves : une caméra filaire n'émet rien.
- **Détecteur de drones** : le Remote ID (signalement électronique européen et américain) diffusé en Bluetooth LE (classique et longue portée) et en Wi-Fi (balises, Wi-Fi Aware) : position, hauteur et vitesse du drone, position et identifiant du télépilote, sur une carte, avec un moteur qui continue d'écouter application fermée.

### 📤 Exports

Uniquement quand vous le demandez, à l'emplacement que vous choisissez :
- **CSV** : test de débit, carte de couverture, avions, ballons-sondes, navires, APRS, ACARS, VDL Mode 2, POCSAG, TPMS, capteurs ISM, compteurs, appels ASN, LoRaWAN / Sigfox, journal de handover ;
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
| Notifications | Alertes de la garde anti-traqueurs et du détecteur de fausses antennes, qui tournent en arrière-plan |
| Réseau local (Android 17) | Scan du LAN, ping, scan de ports |
| Installer des applications | Mise à jour intégrée |

Aucun compte, aucune publicité, aucun traceur. Internet sert aux mises à jour, au test de débit (Cloudflare), aux éléments de satellites (CelesTrak) et aux données d'antennes. La réalité augmentée utilise « Services Google Play pour la RA » s'il est installé ; sinon le podomètre prend le relais.

---

## ⚖️ Usage responsable

- Le wardriving enregistre où se trouvent les réseaux ; une fois envoyés à WiGLE.net, ils sont publics. Excluez les adresses que vous ne voulez pas y voir.
- Le SDR est utilisé **en réception seulement**. Respectez la législation de votre pays sur l'écoute des fréquences.
- Les outils de sécurité Wi-Fi (audit, faux points d'accès, homme du milieu, scan de ports) sont faits pour **vos propres réseaux** ou ceux que vous avez l'autorisation de tester.
- Les messages de pagers (POCSAG) et les conversations de talkies-walkies (PMR446) sont des **correspondances privées** : dans de nombreux pays, dont la France, il est interdit de divulguer ou d'utiliser ce que vous entendez.
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

## 📜 Licence

All Radio Tools est un logiciel libre publié sous la [licence publique générale GNU v3.0](LICENSE) : vous pouvez l'utiliser, l'étudier, le partager et le modifier, à condition que les versions dérivées restent sous la même licence et publient leur code source.

---

## 🙏 Remerciements

- [@cloudflare/speedtest](https://github.com/cloudflare/speedtest), le moteur open source de speed.cloudflare.com, porté pour le test de débit.
- [AirGuard](https://github.com/seemoo-lab/AirGuard) (SEEMOO, TU Darmstadt) pour la détection des traqueurs et les règles qui distinguent une balise près de son propriétaire d'une balise qui vous suit.
- [rs1729/RS](https://github.com/rs1729/RS) pour les formats de sondes.
- [rtl_433](https://github.com/merbanan/rtl_433) pour les formats des capteurs de pression des pneus et des capteurs ISM.
- [OnAir](https://github.com/adswill/OnAir) (GPL-3.0), dont les décodeurs ont été portés pour les capteurs ISM, FLARM, Wireless M-Bus, ASN, VDL Mode 2, DECT, DMR / dPMR, Sigfox, Horus, Mode A/C et la télémesure CubeSat.
- [gr-lora_sdr](https://github.com/tapparelj/gr-lora_sdr) (EPFL) pour le récepteur LoRa.
- [mbelib](https://github.com/szechyjs/mbelib) (ISC) pour le décodeur vocal AMBE+2. L'AMBE+2 est couvert par des brevets dans certains pays : ce code est fourni à des fins de recherche et d'enseignement.
- [WiGLE](https://wigle.net) pour le format de fichier du wardriving et l'API d'envoi.
- Google [GNSS Measurement Tools](https://github.com/google/gps-measurement-tools) pour le calcul des pseudodistances.
- Roboto Flex, Google Sans Flex et Material Symbols pour l'interface.

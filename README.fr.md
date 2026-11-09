# 📡 All Radio Tools

☕ **Vous aimez l'application ?** [Soutenez-la sur Ko-fi](https://ko-fi.com/toftmalone)

**Tout ce qui émet autour de vous, dans une seule application Android.**

All Radio Tools réunit les outils pour explorer le Wi-Fi, le Bluetooth, le réseau mobile, le GNSS, le NFC et, avec un HackRF, les ondes radio : avions, planeurs, ballons-sondes, navires, drones, satellites, talkies-walkies, capteurs et compteurs. Et aussi les badges NFC et les cartes sans contact.

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

L'écran d'accueil propose six réseaux. Chacun a son tableau de bord, son outil principal dans le dock flottant et une page « Outils ».

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

Les outils Internet (test de débit, ping, traceroute, scan de ports, DNS, whois) sont **disponibles uniquement en Wi-Fi** : quand le téléphone n'est pas connecté au Wi-Fi, ils sont grisés et le disent.

### 🔵 Bluetooth

| Outil | À quoi ça sert |
| --- | --- |
| Scanner BLE | Appareils à proximité avec filtres et identification (fabricant, modèle, services) |
| Appareil BLE | Exploration GATT d'un appareil |
| Chaud/Froid | Retrouver un appareil au signal |
| Traqueurs inconnus | Les traqueurs qui pourraient vous suivre (AirTag, accessoires Localiser, AirPods, tags Google, Galaxy SmartTag, Tile, Chipolo, Pebblebee), chacun avec son **dessin fidèle à l'objet**, triés par date de détection, avec « faire sonner ». Une **garde en arrière-plan** continue de surveiller app fermée et vous alerte quand un traqueur vous suit ; les balises connectées à leur propriétaire sont ignorées, comme dans AirGuard |
| Balises | iBeacon, Eddystone, AltBeacon |
| Batterie des écouteurs | AirPods et Beats : gauche, droite, boîtier |
| Attaques advertising | Repère l'attaque par inondation de pop-up — cartes d'appairage et de configuration poussées vers votre téléphone (Apple, Fast Pair, Swift Pair, Samsung) — distinguée des écouteurs autour de vous par une trame qu'aucun produit n'émet, une liste de cartes qui défile, ou un seul émetteur derrière une foule d'adresses ; nomme un Flipper Zero à proximité. Lit seulement, n'émet rien |

### 📱 Réseau mobile

| Outil | À quoi ça sert |
| --- | --- |
| Cellule de service | Identifiants et mesures de la cellule connectée (LTE, NR) |
| Cellules voisines | Cellules détectées par le modem |
| Journal de handover | Changements de cellule en trajet, lancé à la main, export CSV |
| Carte des antennes | Antennes de votre opérateur, d'après les données ouvertes de l'ANFR |
| Test de débit, Ping, Traceroute, DNS, Whois | Les mêmes outils qu'en Wi-Fi, **disponibles uniquement avec les données mobiles** : tant que le Wi-Fi est connecté, ou que les données mobiles sont coupées, ils sont grisés et le disent |
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

### 📲 NFC

| Outil | Contenu |
| --- | --- |
| Lecteur de badge | Puce, mémoire, contenu et identité de n'importe quel badge ou carte (lien, texte, contact, Wi-Fi, position, application… en NDEF), lisible ou verrouillé, et ce que le téléphone gère lui-même (lecteur, émulation de carte, élément sécurisé) |
| Écrire un badge | Lien, Wi-Fi, contact, texte, position, téléphone, SMS, e-mail, application : chaque badge posé sur le téléphone tant que l'écriture est armée reçoit le contenu |
| Copier un badge | Le contenu NDEF d'un badge sur un autre (pas l'identifiant, ni la mémoire protégée) |
| Effacer ou verrouiller | Vider un badge, ou le passer en lecture seule pour toujours (demande une confirmation : c'est irréversible) |
| Carte bancaire | Ce que votre carte sans contact dit à n'importe quel terminal, sans le code : réseau, application, date d'expiration, pays, devise et derniers paiements. Le numéro complet n'est jamais affiché ni conservé |
| Mémoire | Chaque page et chaque secteur que le badge laisse lire ; les secteurs MIFARE Classic ne sont ouverts qu'avec les clés par défaut publiques des fabricants |
| Antenne et fiabilité | Faire glisser un badge sur le téléphone pour trouver où est son antenne, et mesurer la fiabilité de la liaison à mesure qu'il s'éloigne |

Rien n'est conservé une fois l'application fermée.

### 📻 SDR (HackRF et autres, en réception uniquement)

> **Autres radios que le HackRF (nouveau, non testé sur du vrai matériel) :** les outils SDR acceptent aussi une clé **RTL-SDR** (tuner R820T / R820T2 / R828D, de 24 à 1766 MHz, jusqu'à environ 3 MS/s), un **Airspy** (R2 ou Mini, de 24 à 1750 MHz, utilisé à 2,5 ou 3 MS/s puis rééchantillonné) et un **ADALM-Pluto / PlutoSDR** (de 70 MHz à 6 GHz, par son câble USB, rééchantillonné depuis 2,1 MS/s ou plus). Un outil dont la fréquence ou le taux d'échantillonnage dépasse ce que la radio branchée sait faire le dit au lieu de démarrer : les outils 2,4 GHz et 5,8 GHz (Zigbee, ANT+, drones, FPV) et ceux à 4 MS/s demandent un HackRF (ou un Pluto pour le 4 MS/s). Les pilotes ont été écrits d'après les bibliothèques open source et testés uniquement sur un bus USB simulé. Les radios SDRplay ne sont pas prises en charge : leur pilote est propriétaire.

| Outil | Contenu |
| --- | --- |
| Analyseur de spectre | Spectre et chute d'eau, de 1 MHz à 6 GHz |
| ACARS | Messagerie des avions de ligne sur les 3 canaux européens, 131 MHz |
| ADS-B | Carte des avions sur 1090 MHz, avec les anciennes réponses Mode A/C (squawk, altitude) |
| AIS | Carte des navires sur 162 MHz |
| APRS | Balises radioamateurs sur 144,800 MHz |
| Ballons-sondes | Vaisala RS41 (trames abîmées réparées par leur code Reed-Solomon), Meteomodem M10 / M10+ / M20 et ballons amateurs Horus vers 403 et 434 MHz, avec prédiction d'atterrissage |
| Capteurs ISM | Stations météo, thermomètres, compteurs d'énergie, détecteurs d'ouverture, télécommandes sur 433, 868, 315, 345 et 915 MHz (une quarantaine de familles, dont Auriol de Lidl, Oregon Scientific, Hideki, Ecowitt) |
| CubeSat | Télémesure AX.25 des satellites amateurs et de l'ISS (AFSK 1200, G3RUH 9600), prochains passages et correction Doppler automatique |
| DECT | Bases de téléphones sans fil (1880–1900 MHz) : identité, porteuses, intervalles ; la voix n'est jamais touchée |
| DSC (ASN) | Appels sélectifs numériques maritimes sur le canal VHF 70 : alertes de détresse, appels de sécurité et de routine, positions |
| Drones | DJI (DroneID), FPV analogique et radiocommandes (ExpressLRS, Crossfire, FrSky, FlySky, Spektrum) |
| FLARM | Planeurs et aviation légère sur 868 MHz (ancien protocole et celui de 2024), positions autour de vous ; les aéronefs qui refusent le suivi sont seulement comptés |
| LoRaWAN / Sigfox | Objets connectés sur 868 MHz : en-têtes LoRaWAN (adresse, réseau nommé d'après la table NetID de la LoRa Alliance, compteur) de SF7 à SF12, avec une option pour écouter les huit canaux montants EU868 à la fois, et messages Sigfox |
| Meshtastic | Le réseau maillé LoRa sur 868 MHz (869,525 MHz et ses voisins, tous les préréglages européens), 433 MHz ou toute fréquence libre de 400 à 1000 MHz avec le préréglage de votre choix (bande américaine 915 MHz comprise) : nœuds (nom, matériel, rôle, batterie, position sur une carte), messages et télémétrie sur les canaux par défaut, et sur les canaux que vous ajoutez avec leur clé ; les messages privés restent chiffrés |
| MeshCore | Le réseau maillé LoRa à répéteurs sur 869,618 MHz (62,5 kHz, SF7 à SF12) et 869,525 MHz : annonces signées des nœuds (nom, type, position sur une carte), le canal Public et les canaux #hashtag que vous ajoutez, la route de chaque inondation |
| LoRa APRS | Les balises APRS des radioamateurs en LoRa sur 433,775 MHz (SF12) et 434,855 MHz (SF9), écoutées en même temps : stations, symboles, commentaires et météo sur une carte |
| Balises 406 MHz | Balises de détresse COSPAS-SARSAT (EPIRB, ELT, PLB), première génération, de 406,0 à 406,1 MHz : identité (15 hexa), pays, protocole, MMSI ou adresse d'aéronef, position GPS quand elle est envoyée, auto-tests signalés ; vérifié sur un vrai enregistrement de PLB |
| Z-Wave | La domotique sur 868,40, 868,42 et 869,85 MHz (R1, R2, R3 en même temps) : réseaux (home id), nœuds, classes de commande en clair (interrupteur, batterie, capteurs…) ; trames chiffrées S0 / S2 comptées, jamais ouvertes |
| SSTV | Images de télévision à balayage lent en FM, l'ISS sur 145,800 MHz ou toute fréquence : en-tête VIS, modes Robot, Martin, Scottie, PD (PD120 / PD180 comme l'ISS) et Wraase, Doppler suivi, inclinaison corrigée, images enregistrées en PNG |
| Zigbee / 802.15.4 | Les réseaux Zigbee, Thread et 802.15.4 à 2,4 GHz, un canal ou les seize à tour de rôle : PAN ID, adresses et fabricants des puces, balises (ajout ouvert ou fermé), en-tête réseau Zigbee et clusters quand ils ne sont pas chiffrés |
| ANT+ | Les capteurs sportifs sur 2457 MHz : fréquence cardiaque et intervalle R-R, puissance, cadence et vitesse vélo, capteurs de foulée, température, fabricant et batterie, avec les profils publics ANT+ |
| Écoute | Écouter des stations : les 2 MHz autour d'une fréquence en spectre, on touche ou on glisse pour accorder, et le canal est joué en **WFM** (radio FM), **NFM** (talkies-walkies, marine), **AM** (aviation, ondes courtes) ou **USB / LSB**, avec volume, squelch et stations prédéfinies |
| Meteor-M | Images LRPT des satellites météo sur 137,100 et 137,900 MHz, des anciens satellites comme des Meteor-M2-x qui émettent en QPSK décalé |
| PMR446 | Les 16 canaux de talkies-walkies à la fois : activité, tonalité CTCSS, écoute des canaux analogiques et de la voix DMR446 / dPMR446 (décodage AMBE+2), identifiants des appels |
| POCSAG | Messages de pagers (e*Message France et Allemagne, BOS, DAPNET ou n'importe quelle fréquence) : tous les canaux d'un réseau à la fois, 512, 1200 et 2400 bit/s, correction d'erreurs BCH |
| TPMS | Capteurs de pression des pneus des voitures alentour sur 433,92 MHz (ou 315 MHz) : pression, température, pile, pour Renault, PSA, Toyota, Ford, Hyundai/Kia, BMW/Audi, Porsche, Honda (TRW), Schrader (Opel/GM, Subaru/Nissan/Renault Koleos, BMW) et d'autres |
| VDL Mode 2 | Liaison de données numérique des avions de ligne sur 6 canaux vers 136,9 MHz : ACARS numérique, stations sol, ouvertures de liaison |
| Wireless M-Bus | Compteurs d'eau, de chaleur, de gaz et d'électricité sur 868,95 MHz (T1, C1), même quand l'horloge du compteur est un peu décalée ; relevés quand ils ne sont pas chiffrés |

Chaque gain a un **mode automatique**, choisi par défaut, qui règle le LNA et le VGA d'après le niveau du signal ; la valeur utilisée s'affiche dans sa propre couleur.

Les décodeurs fonctionnent avec le **HackRF seul** : ni le Wi-Fi ni le Bluetooth du téléphone ne sont utilisés. La carte SDR reste grisée tant qu'aucun HackRF n'est branché en USB.

### 🚗 Wi-Fi + Bluetooth : wardriving

**Sélectionnez la carte Wi-Fi** (ou Bluetooth) : un **+** apparaît sur l'autre. Touchez-le : les deux cartes glissent l'une vers l'autre et forment une seule case, avec un dock pour la paire et le **Wardriving** comme outil phare. Le bouton de séparation sur la case les désassemble.

- L'écran d'accueil de WiGLE : réseaux et appareils nouveaux de la session, total de la base, position GPS, dernier scan, puis chaque réseau Wi-Fi et appareil Bluetooth LE entendu, du plus fort au plus faible, avec son constructeur (registre OUI de l'IEEE).
- L'enregistrement tourne dans un **service de premier plan** : il continue écran éteint et application fermée, jusqu'à ce que vous l'arrêtiez.
- Tout reste dans une base de données sur le téléphone. **L'envoi vers WiGLE.net** est uniquement manuel, avec le nom d'API et le jeton de votre compte (jamais votre mot de passe).
- Excluez une adresse MAC complète ou un préfixe constructeur (OUI) : il n'est ni affiché ni enregistré, et il est effacé de la base.
- **Caméras cachées** : réseaux Wi-Fi et appareils Bluetooth de fabricants de caméras ou portant un nom de caméra, un signal chaud / froid pour s'en approcher, et les ports vidéo (RTSP, ONVIF, Hikvision, Dahua, XMEye) des appareils du réseau Wi-Fi auquel vous êtes connecté. Des indices, pas des preuves : une caméra filaire n'émet rien.
- **Détecteur de drones** : le Remote ID (signalement électronique européen et américain) diffusé en Bluetooth LE (classique et longue portée) et en Wi-Fi (balises, Wi-Fi Aware) : position, hauteur et vitesse du drone, position et identifiant du télépilote, sur une carte, avec un moteur qui continue d'écouter application fermée.

### 📤 Exports

Uniquement quand vous le demandez, à l'emplacement que vous choisissez :
- **CSV** : test de débit, carte de couverture, avions, ballons-sondes, navires, APRS, ACARS, VDL Mode 2, POCSAG, TPMS, capteurs ISM, compteurs, appels ASN, LoRaWAN / Sigfox, paquets Meshtastic et MeshCore, LoRa APRS, balises 406 MHz, Z-Wave, Zigbee, ANT+, journal de handover ;
- **RINEX** : mesures brutes GNSS ;
- **PNG** : images Meteor-M et SSTV ;
- **Journal de debug** (Réglages, Application) : un journal détaillé de l'app, 50 Mo au plus, à partager ou enregistrer pour signaler un bug, ou à vider.

L'application ne garde aucune copie : rien n'est enregistré sans votre action.

Le journal de debug reste sur le téléphone tant que vous ne le partagez ou ne l'enregistrez pas.

---

## 🔐 Autorisations

| Autorisation | Pourquoi |
| --- | --- |
| Position précise | Scans Wi-Fi, cellules, GNSS (imposée par Android) |
| Appareils à proximité | Scan et connexion Bluetooth |
| Téléphone | Opérateur, cellules, double SIM |
| Caméra | Carte de couverture en réalité augmentée (demandée seulement à son lancement) |
| NFC | Lecture et écriture des badges et des cartes (sans demande : Android demande seulement d'activer le NFC) |
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
- Le lecteur de carte bancaire NFC n'affiche que ce que lit n'importe quel terminal sans le code, jamais le numéro complet, et ne garde rien. Lire, écrire ou copier une carte qui n'est pas la vôtre, ou un badge que vous n'avez pas le droit de copier, n'est pas son but.
- Verrouiller un badge est **définitif** : plus aucun téléphone ne pourra y écrire.
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
3. Lancer le workflow GitHub Actions « Release » : à la main (Actions → Release → Run workflow), en poussant un tag `v*`, ou par un push qui **modifie `.github/release-version`** (sur **n'importe quelle** branche, pas seulement `main` : ne touchez ce fichier que pour publier). Il construit l'APK signé et crée la release avec `AllRadioTools-v1.3.apk`. Un tag qui existe déjà reste sur son commit : supprimez-le avant de publier à nouveau.

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
- [OnAir](https://github.com/adswill/OnAir) (GPL-3.0), dont les décodeurs ont été portés pour les capteurs ISM, FLARM, Wireless M-Bus, ASN, VDL Mode 2, DECT, DMR / dPMR, Sigfox, Horus, Mode A/C, la télémesure CubeSat, les balises 406 MHz, Z-Wave, le SSTV, Zigbee / 802.15.4 et ANT+ (son enregistrement d'une vraie PLB a servi à vérifier le décodeur 406 MHz).
- [SoftRF](https://github.com/lyusupov/SoftRF) (GPL-3.0), dont le code FLARM (CRC des trames, clés et format du protocole de 2024) a été porté.
- [gr-lora_sdr](https://github.com/tapparelj/gr-lora_sdr) (EPFL) pour le récepteur LoRa.
- [Meshtastic](https://github.com/meshtastic/firmware) (firmware et protobufs, GPL-3.0) et [MeshCore](https://github.com/meshcore-dev/MeshCore) (MIT) pour leurs formats de paquets ; les vrais paquets MeshCore de [meshcore-decoder](https://github.com/michaelhart/meshcore-decoder) et le vecteur de référence de [meshtastic-sniffer](https://github.com/alphafox02/meshtastic-sniffer), contre lesquels les décodeurs ont été vérifiés.
- Les enregistrements et sources de [rtl_433_tests](https://github.com/merbanan/rtl_433_tests), [dumpvdl2](https://github.com/szpajder/dumpvdl2), [multimon-ng](https://github.com/EliasOenal/multimon-ng), [horusdemodlib](https://github.com/projecthorus/horusdemodlib), [DSD-FME](https://github.com/lwvmobile/dsd-fme) et [SatDump](https://github.com/SatDump/SatDump), contre lesquels les décodeurs ont été vérifiés, ainsi que l'enregistrement VDL-M2 de sigidwiki.
- [mbelib](https://github.com/szechyjs/mbelib) (ISC) pour le décodeur vocal AMBE+2. L'AMBE+2 est couvert par des brevets dans certains pays : ce code est fourni à des fins de recherche et d'enseignement.
- [librtlsdr](https://github.com/osmocom/rtl-sdr) (osmocom, GPL-2.0-or-later) et [libairspy](https://github.com/airspy/airspyone_host) (BSD-3-Clause) pour les pilotes RTL-SDR et Airspy, et [libiio](https://github.com/analogdevicesinc/libiio) (LGPL-2.1-or-later) pour celui du PlutoSDR.
- [WiGLE](https://wigle.net) pour le format de fichier du wardriving et l'API d'envoi.
- Google [GNSS Measurement Tools](https://github.com/google/gps-measurement-tools) pour le calcul des pseudodistances.
- Roboto Flex, Google Sans Flex et Material Symbols pour l'interface.

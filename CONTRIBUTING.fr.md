# Contribuer à All Radio Tools

Merci de prendre le temps d'aider. Cette page est la version courte de la façon dont le projet
est construit, testé et relu, pour qu'une pull request soit intégrée avec le moins
d'allers-retours possible.

*(English version: [CONTRIBUTING.md](CONTRIBUTING.md).)*

## Mise en place

Il faut un JDK récent (la CI utilise le 21) et le SDK Android (API 37).

```bash
git clone https://github.com/ToftMalone/All-Radio-Tools.git
cd All-Radio-Tools
./gradlew :app:assembleDebug        # construit l'APK debug
./gradlew :app:testDebugUnitTest    # lance les tests unitaires (et les tests de capture)
./gradlew :app:lintDebug            # lance le lint Android
```

Le dossier `scripts/` récupère les polices et génère la carte du monde ; on en a rarement besoin.

La version debug s'installe à côté de l'application publiée, avec son propre paquet
(`com.allnetworktools.debug`) et son propre nom : on peut garder les deux sur un même téléphone.

## Organisation du code

Le code est sous `app/src/main/java/com/allnetworktools/` :

| Paquet | Ce qu'on y met |
| --- | --- |
| `data/` | La logique, le décodage et les dépôts. Pas de Compose. C'est là que vit le code testable. |
| `model/` | Les enums `Network` et `Tool` — le catalogue de tous les écrans que l'app peut ouvrir. |
| `ui/pages/` | Un écran par outil, groupé par radio (`ui/pages/bt/`, `ui/pages/wifi/`, …). |
| `ui/components/`, `ui/tools/`, `ui/theme/` | Les briques Compose partagées, les noms d'icônes `Sym` et le thème. |

**Ajouter un outil** touche trois endroits, dans cet ordre :

1. une entrée dans l'enum `Tool` (`model/Network.kt`) ;
2. l'écran lui-même sous le `ui/pages/<radio>/` correspondant ;
3. deux branchements dans `ui/pages/PageHost.kt` — la route qui construit l'écran — et le groupe
   dans `ui/pages/ToolsPage.kt` qui le liste sur la page Outils de la radio.

Garder la logique dans `data/` et le Compose dans `ui/` : c'est ce qui permet de tester la
logique sans émulateur.

## Icônes

Les noms d'icônes sont dans `ui/theme/Symbols.kt` sous `Sym.*`. La police est **réduite** à
exactement cette liste ; réutilisez un nom existant quand c'est possible. S'il faut vraiment un
nouveau glyphe, ajoutez-le à `Sym` et reconstruisez la police avec
`scripts/fetch_fonts.py --icons`, en le signalant dans la PR.

## Chaînes et traductions

Tout texte vu par l'utilisateur passe par les ressources — pas de texte en dur dans l'UI.

- L'anglais (`app/src/main/res/values/strings.xml`) fait foi.
- Chaque traduction a son dossier : `values-fr/`, `values-de/`, … Une chaîne sans traduction
  retombe sur l'anglais ; une nouvelle chaîne en **anglais et, idéalement, en français** suffit
  donc à fusionner, les autres langues peuvent suivre.
- Utilisez les pluriels (`<plurals>`) pour tout ce qui se compte.
- Gardez les guillemets autour des valeurs comme le fait le fichier existant, et gardez les
  arguments positionnels `%1$d` / `%2$s` pour que les traductions puissent les réordonner.

`lintDebug` signale `MissingTranslation` pour les chaînes pas encore présentes dans toutes les
langues. Le projet en porte déjà un certain nombre ; quelques-unes de plus venant d'une nouvelle
chaîne sont attendues et ne sont **pas** une raison de retenir une PR — ce sont les tests
unitaires qui font foi (voir ci-dessous).

## Tests

Les tests unitaires sont dans `app/src/test/java/com/allnetworktools/` et tournent sur la JVM
nue (avec Robolectric quand un écran est en jeu). **Un changement de logique doit venir avec un
test.** Les tests existants sont un bon modèle : ils donnent de vraies charges utiles en octets
à un décodeur et vérifient le résultat, sans appareil Android dans la boucle.

Les tests JVM lisent quand même les vraies chaînes françaises via la propriété système
`ant.strings` (câblée dans `app/build.gradle.kts`) : un test peut donc vérifier une phrase que
l'utilisateur voit vraiment.

Lancez la suite avant d'ouvrir une PR :

```bash
./gradlew :app:testDebugUnitTest
```

## Messages de commit

Suivez le style déjà présent dans l'historique : une ligne de résumé courte et précise,
préfixée par le domaine et un deux-points.

```
Bluetooth: spot the pop-up flooding attack on BLE advertising
Wardriving: export the whole run as a WiGLE CSV file
Settings: one debug log tile opening share / save / clear
```

Expliquez le *pourquoi* dans le corps quand le changement n'est pas évident. Quand votre travail
s'appuie sur le reverse engineering de quelqu'un d'autre ou sur un projet open source,
créditez-le dans le corps et gardez les licences compatibles (l'app est en GPL-3.0).

## Pull requests

- **Un sujet par PR.** Un diff ciblé se relit plus vite qu'un diff large.
- Dites ce que fait le changement et pourquoi. Si vous avez testé sur du vrai matériel, dites
  quel appareil et ce que vous avez vu — pour tout ce qui lit des radios, c'est la preuve qui
  compte.
- Assurez-vous que `:app:assembleDebug` et `:app:testDebugUnitTest` passent. La CI lance les deux
  sur chaque PR (voir [`.github/workflows/ci.yml`](.github/workflows/ci.yml)).
- Le workflow `.github/workflows/release.yml` construit les releases signées sur un tag ; pas
  besoin d'y toucher pour un changement ordinaire.

## Usage responsable

All Radio Tools observe les radios autour de vous ; c'est un outil de mesure et de défense.
Gardez les contributions de ce côté de la ligne : des fonctions qui lisent, identifient et
alertent, pas des fonctions faites pour émettre du brouillage, usurper, inonder ou perturber les
appareils des autres. La section « Usage responsable » du README fait référence.

## Licence

En contribuant, vous acceptez que votre travail soit publié sous la
[GNU General Public License v3.0](LICENSE) du projet.

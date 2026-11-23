# All Radio Tools

## Release notes

Each release has its notes in `.github/release-notes/vX.Y.md`. The app shows them in the update window and the
changelog, and the "Release notes" workflow copies them to the GitHub release.

- English first, then the French in an HTML comment: `<!-- fr` on its own line, the French notes, then `-->`.
- Cut into three sections, always in this order (leave out a section with nothing in it):
  - English: `### ✨ New`, `### 🐛 Bug fixes`, `### 🧰 Other`
  - French: `### ✨ Nouveautés`, `### 🐛 Résolutions de bugs`, `### 🧰 Autres`
- One emoji at the start of every point, nested points included (`  - ` for a nested point).
- Only what is in the released APK: a change pushed after the release goes in the next version's notes.
- Never change `.github/release-version` or publish a release unless explicitly asked.

Example:

```markdown
### ✨ New
- 🔍 Search any tool from the home screen

### 🐛 Bug fixes
- 🔌 PlutoSDR not recognised: it is now found by its USB ids

### 🧰 Other
- 🎬 Smoother Start/Stop button

<!-- fr
### ✨ Nouveautés
- 🔍 Recherche de n'importe quel outil depuis l'accueil

### 🐛 Résolutions de bugs
- 🔌 PlutoSDR non reconnu : il est maintenant détecté par ses identifiants USB

### 🧰 Autres
- 🎬 Bouton Lancer / Arrêter plus fluide
-->
```

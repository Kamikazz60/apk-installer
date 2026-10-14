# APK Installer

Application Android native (Kotlin, Jetpack Compose, Material 3) qui installe des applications à partir de fichiers **APK** ou d'archives **ZIP** (y compris les applications à **APK fractionnés**). Elle est entièrement hors ligne : aucune permission Internet, aucune publicité, aucun SDK d'analyse, aucune donnée collectée.

Android garde toujours la décision finale : l'installation n'est jamais silencieuse et chaque installation est confirmée par le système.

## Fonctions

- Sélection manuelle d'un APK ou d'un ZIP (sélecteur Android, ouvert sur Téléchargements).
- Recherche automatique des APK/ZIP au lancement, tri (date, nom, taille), actualisation manuelle, sans bloquer l'interface.
- Dossiers autorisés par l'utilisateur (Storage Access Framework) avec autorisations persistantes, et détection de la perte d'accès.
- Informations de l'APK : nom, icône, version, identifiant de paquet, taille, état d'installation (installer / mettre à jour / même version / version antérieure), signature incompatible signalée.
- ZIP : analyse sans extraction complète, un seul APK, plusieurs applications indépendantes (choix) ou APK fractionnés (installation groupée en une seule session `PackageInstaller`).
- Réception depuis « Ouvrir avec » (`ACTION_VIEW`) et « Partager » (`ACTION_SEND`) via des URI `content://`.
- États : analyse, extraction, préparation, confirmation Android, terminée, annulée, échouée. Le succès n'est affiché que lorsque `PackageInstaller` le confirme. Pas de faux pourcentage.
- Paramètres (DataStore) : thème sombre/clair/système, recherche automatique, tri, affichage des ZIP, nettoyage automatique du cache, « Vider le cache », dossiers autorisés, version.

## Compilation

Prérequis : Android Studio (Ladybug ou plus récent) **ou** un JDK 17/21 + le SDK Android avec la plateforme 35. Le JDK 25/26 n'est pas compatible avec Gradle 8.9 / AGP 8.7 : utilisez le JDK intégré d'Android Studio (JBR 21) ou un JDK 17/21.

1. Ouvrir le dossier dans Android Studio, ou créer `local.properties` avec `sdk.dir=<chemin du SDK>` (fichier déjà présent, à adapter).
2. En ligne de commande (Windows) :

```bash
gradlew.bat assembleDebug
```

L'APK debug est généré dans `app/build/outputs/apk/debug/app-debug.apk`.

Tests unitaires :

```bash
gradlew.bat testDebugUnitTest
```

Configuration : `minSdk 26`, `targetSdk 35`, `compileSdk 35`, Gradle 8.9, AGP 8.7.3, Kotlin 2.0.21.

## Architecture

```
app/src/main/java/com/apkinstaller/app/
  MainActivity.kt            Point d'entrée, navigation Compose, ACTION_VIEW / ACTION_SEND
  ApkInstallerApp.kt         Application + conteneur de dépendances
  data/
    SettingsRepository.kt    Préférences (DataStore)
    FileRepository.kt        Accès aux URI (ContentResolver), copie en cache, autorisations persistantes
    FileScanner.kt           Recherche : MediaStore + dossiers SAF
    CacheManager.kt          Cache privé de travail
  install/
    AxmlParser.kt            Lecture du AndroidManifest.xml binaire (sans extraction)
    ZipAnalyzer.kt           Inspection d'archives (Zip Slip, limites, extensions trompeuses)
    SplitSelector.kt         Regroupement par application et choix des splits compatibles
    ArchiveExtractor.kt      Extraction sécurisée (noms générés, CRC, limites, fichier .part)
    ApkAnalyzer.kt           Métadonnées via PackageManager (nom, icône, version, signatures)
    PackageInstallManager.kt Session PackageInstaller + récepteur de statut
  ui/
    InstallationViewModel, FilesViewModel, SettingsViewModel
    HomeScreen, FilesScreen, SettingsScreen, Components, Theme
```

## Choix techniques et sécurité

- **Zip Slip** : les entrées contenant `..`, un chemin absolu ou une lettre de lecteur font refuser l'archive ; à l'extraction, les noms de l'archive ne sont jamais utilisés (fichiers `apk_N.apk` dans un dossier privé, chemin canonique vérifié).
- **ZIP bomb / taille** : limites configurables (`ArchiveLimits` : nombre d'entrées, taille par entrée, taille totale, taux de compression). La taille réellement écrite est comptée, pas seulement la taille déclarée.
- **Intégrité** : taille et CRC32 vérifiés ; écriture dans un fichier `.part` renommé seulement une fois complet ; fichier partiel supprimé en cas d'erreur ou d'annulation.
- **Extensions trompeuses** : un fichier est traité comme APK ou archive selon son contenu (présence de `AndroidManifest.xml`), pas selon son extension. Les `.apk` imbriqués invalides sont ignorés et signalés.
- **APK fractionnés** : regroupement par nom de paquet ; exigence d'un seul APK de base, même `versionCode` pour tous les splits, un split d'architecture (ABI préférée de l'appareil), un split de densité (le plus proche), les splits de langue du système, et les modules de fonctionnalité. Archive ambiguë (plusieurs bases, splits en double, ABI incompatible) : erreur explicite, aucune installation.
- **Installation** : `PackageInstaller.Session` unique pour un APK ou un groupe de splits ; confirmation Android obligatoire ; `PendingIntent` explicite et mutable (requis par le système). Le ZIP/APK d'origine n'est jamais modifié ni supprimé ; seules les copies du cache privé le sont.
- **Permissions** : `REQUEST_INSTALL_PACKAGES` (installation), `QUERY_ALL_PACKAGES` (savoir si une application est déjà installée, indispensable depuis Android 11), `READ_EXTERNAL_STORAGE` limitée à Android 9 et antérieur. Ni Internet, ni `MANAGE_EXTERNAL_STORAGE`.

## Limitations connues

- **Téléchargements sur Android 10+** : le stockage cloisonné empêche une application de lister les APK/ZIP qu'elle n'a pas créés sans autorisation de l'utilisateur. La recherche automatique via MediaStore ne remontera donc généralement rien sur Android 10+ ; il faut autoriser un dossier (bouton « Autoriser l'accès aux Téléchargements »). **Depuis Android 11, le système interdit d'autoriser la racine du dossier Téléchargements** : choisissez un sous-dossier, ou utilisez « Sélectionner un fichier » (sélecteur ouvert sur Téléchargements). Android 9 et antérieur : MediaStore fonctionne après autorisation de lecture du stockage.
- **Copie en cache** : un APK ou ZIP sélectionné est copié dans le cache privé avant analyse (le ZIP doit être lisible en accès aléatoire). Prévoir de l'espace libre ; limite d'entrée 4 Go.
- **Rétrogradation** : signalée, mais Android la refuse en général ; la décision finale lui appartient.
- **Signature** : la comparaison avec l'application installée est indicative (l'état « inconnu » est possible) ; seul Android tranche.
- **Visibilité des paquets** : l'état « déjà installée » dépend de `QUERY_ALL_PACKAGES` ; si cette permission est refusée par un magasin d'applications, l'application apparaît comme non installée.
- **Splits** : le choix automatique couvre les conventions de nommage `config.<abi|densité|langue>` (bundletool). Des noms de splits non conventionnels de configuration sont ignorés plutôt que devinés.
- **Fichiers `.xapk`/`.apks`/`.apkm`** : traités comme des archives ZIP (seuls les `.apk` internes sont utilisés ; les fichiers OBB sont ignorés).
- **Progression** : indéterminée, car Android ne fournit pas de pourcentage exploitable avant la confirmation.
- Le filtre « Ouvrir avec » inclut `application/octet-stream` (types MIME mal renseignés) : l'application peut donc apparaître pour d'autres fichiers binaires et refusera proprement ceux qui ne sont pas des APK/ZIP.
- Chaînes de l'interface en français uniquement (non externalisées dans `strings.xml`).
- L'état d'une installation en cours est conservé lors des rotations, mais pas si le système tue le processus.

## Tests réalisés


### Tests unitaires (JVM) — 26 tests, 0 échec

Exécutés avec `gradlew.bat testDebugUnitTest` : lecture du manifeste binaire (valide, tronqué, invalide), ZIP à un APK / sans APK / `.apk` factice / extension trompeuse, Zip Slip, chemins absolus, archive corrompue, trop d'entrées, taille excessive, ZIP bomb, extraction (noms générés, limites, annulation sans fichier partiel, entrée absente), sélection des splits (ABI, densité, langue, modules, ambiguïtés, versions) et comparaison de versions.

### Tests sur émulateur (Android 15, API 35, x86_64) — réalisés

APK d'essai générés à partir de ce projet (`-PsampleId` / `-PsampleVersion`), ZIP et `.apks` bundletool réels. Le dossier de test a été autorisé via le sélecteur Android ; la validation Play Protect de l'émulateur a été désactivée pour les tests.

| # | Scénario | Résultat |
|---|----------|----------|
| 1 | Installation d'un APK valide | OK (confirmé par Android) |
| 2 | Ouverture d'un APK depuis Téléchargements (sélecteur ouvert sur Téléchargements) | OK |
| 3 | Ouverture depuis « Partager » / « Ouvrir avec » | **Non testé** (le shell adb ne peut pas accorder d'URI) ; filtres déclarés, code identique à la sélection manuelle |
| 4 | APK extrait d'un ZIP | OK |
| 5 | ZIP à plusieurs applications | OK (liste de choix, puis installation) |
| 6 | ZIP à APK fractionnés (bundletool) | OK : 4 APK installés en une session (`base` + `config.x86_64`, `config.xxhdpi`, `config.en`) ; vérifié par `pm path` |
| 6b | ZIP avec deux APK de base (sortie `bundletool --mode=default` brute) | Refusé comme ambigu (voulu) |
| 7 | ZIP sans APK | Message clair |
| 8 | ZIP corrompu | Message clair, pas de crash |
| 9 | ZIP Zip Slip | Refusé (message dédié) |
| 10 | ZIP bomb (300 Mo → 306 Ko) | Refusé |
| 11 | Mise à jour (v1 → v2) | Détectée (« Mettre à jour ») ; l'installation de la mise à jour n'a été confirmée que via adb, pas depuis l'application |
| 12 | Version antérieure | Avertissement ; Android refuse (`INSTALL_FAILED_VERSION_DOWNGRADE`) et l'échec est affiché |
| 13 | Autorisation d'installation absente | Écran d'explication + bouton vers le réglage Android ; état rafraîchi au retour |
| 14 | Annulation dans la fenêtre Android | « Installation annulée » |
| 15 | Recherche dans un dossier autorisé | OK (11 fichiers, tri par date) |
| 16 | Perte d'autorisation d'un dossier | **Non testé** sur appareil (code présent : détection par `persistedUriPermissions` et `SecurityException`) |
| 17 | Redémarrage (arrêt forcé) | Autorisation persistante et recherche automatique OK |
| 18 | Nettoyage du cache | Nettoyage automatique au lancement vérifié (35 Mo → 8 Ko) ; bouton « Vider le cache » non actionné pendant les tests |

Non testés : Android < 8/9 (MediaStore + permission de lecture), tablettes, rotation d'écran, thème clair/système, tri par nom/taille.

### Fichiers d'essai

`-PsampleId=<id> -PsampleVersion=<n>` permet de compiler une variante de l'application avec un autre identifiant, uniquement pour fabriquer des APK d'essai.

## APK compilé

L'APK debug (signé avec la clé de débogage) est téléchargeable dans les « Releases » du dépôt GitHub, ou se compile avec `gradlew.bat assembleDebug`.

## Licence

MIT — voir le fichier `LICENSE`.

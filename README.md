# Edge Computing RTMP

Projet compose de 2 briques:

- `rtmp-server/`: serveur RTMP Nginx dans Docker
- `EdgeComputer/`: application Android qui capture la camera et publie un flux RTMP

Objectif: streamer la video du smartphone vers un serveur RTMP local du meme reseau.

## Architecture

- Ingestion RTMP: `rtmp://<IP_DU_PC>:1935/live/stream`
- Port RTMP: `1935/tcp`
- Endpoint de verification serveur: `http://<IP_DU_PC>:8080/`

## Prerequis generaux

- Windows 10/11
- Docker Desktop actif
- Android Studio installe
- Smartphone Android avec camera
- PC et smartphone sur le meme reseau local

## Configuration Android requise

Valeurs exactes relevees dans le projet `EdgeComputer/`.

| Element | Valeur requise | Source projet |
|---|---|---|
| Android Gradle Plugin | `9.0.0` | `EdgeComputer/gradle/libs.versions.toml` |
| Gradle Wrapper | `9.2.1` | `EdgeComputer/gradle/wrapper/gradle-wrapper.properties` |
| compileSdk | `36` (minor `1`) | `EdgeComputer/app/build.gradle.kts` |
| targetSdk | `36` | `EdgeComputer/app/build.gradle.kts` |
| minSdk | `16` | `EdgeComputer/app/build.gradle.kts` |
| Java source/target | `11` | `EdgeComputer/app/build.gradle.kts` |
| JDK de build (toolchain) | `21` recommande | `EdgeComputer/gradle/gradle-daemon-jvm.properties` |
| NDK | `17.2.4988734` | `EdgeComputer/app/build.gradle.kts` |
| CMake | `3.22.1` | `EdgeComputer/app/build.gradle.kts` |
| MultiDex | active | `EdgeComputer/app/build.gradle.kts` |

### SDK/NDK a installer dans Android Studio

Dans `SDK Manager`, installer au minimum:

- `Android SDK Platform 36`
- `Android SDK Build-Tools` compatibles API 36
- `Android SDK Platform-Tools`
- `Android SDK Command-line Tools (latest)`
- `NDK (Side by side)` version `17.2.4988734`
- `CMake` version `3.22.1`

### Permissions et materiel Android utilises

- Permissions: `CAMERA`, `INTERNET`, `RECORD_AUDIO`
- Features: camera requise, accelerometre optionnel
- Application: `androidx.multidex.MultiDexApplication`

Fichier de reference: `EdgeComputer/app/src/main/AndroidManifest.xml`.

## Mise en place du projet

### 1) Cloner le depot

```bash
git clone <URL_DU_REPO>
cd edge_computing
```

### 2) Demarrer le serveur RTMP

```bash
docker compose -f rtmp-server/docker-compose.yml up -d
```

Verifier le serveur:

```bash
docker ps
curl http://localhost:8080/
```

Reponse attendue:

```text
nginx-rtmp OK
```

Logs serveur:

```bash
docker logs -f rtmp
```

### 3) Configurer l'URL RTMP dans l'app Android

Modifier `EdgeComputer/app/src/main/java/com/example/edgecomputer/MainActivity.java`:

```java
private static final String RTMP_URL = "rtmp://<IP_DU_PC>:1935/live/stream";
```

Exemple:

```text
rtmp://192.168.0.28:1935/live/stream
```

Recuperer l'IP locale du PC:

```powershell
ipconfig
```

### 4) Ouvrir et lancer l'app Android

- Ouvrir `EdgeComputer/` dans Android Studio
- Laisser la sync Gradle se terminer
- Brancher un appareil Android physique
- Accepter les permissions camera/micro
- Lancer l'application

Le stream demarre automatiquement quand la surface camera est prete.

## Verification du streaming

Avec VLC (sur le PC), ouvrir:

```text
rtmp://<IP_DU_PC>:1935/live/stream
```

Dans `docker logs -f rtmp`, verifier des lignes de type:

- `connect app='live'`
- `publish name='stream'`

## Depannage rapide

- `Aucun flux`: verifier IP, reseau local commun, port `1935`, et etat du conteneur `rtmp`
- `Connexion RTMP en erreur`: verifier `docker logs -f rtmp`
- `Erreur camera Android`: fermer les autres apps qui utilisent la camera et revalider les permissions runtime
- `Erreur de build Android`: verifier JDK, SDK API 36, NDK `17.2.4988734`, CMake `3.22.1`

## Commandes utiles

```bash
# Demarrage serveur
docker compose -f rtmp-server/docker-compose.yml up -d

# Arret serveur
docker compose -f rtmp-server/docker-compose.yml down

# Logs serveur RTMP
docker logs -f rtmp
```

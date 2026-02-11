# Edge Computing RTMP - Guide de mise en place

Ce depot contient deux composants qui fonctionnent ensemble:

- `rtmp-server/`: serveur RTMP base sur Nginx (dans Docker)
- `EdgeComputer/`: application Android qui capture la camera et publie un flux RTMP

L'objectif du projet est de diffuser un flux video depuis un smartphone Android vers un serveur RTMP local sur le reseau.

## Architecture du projet

- Le serveur expose `1935/tcp` pour RTMP
- Le serveur expose `8080/tcp` pour un endpoint HTTP de verification
- L'application Android envoie le flux vers `rtmp://<IP_DU_PC>:1935/live/stream`

## Prerequis

### 1) Serveur (PC)

- Windows 10 ou Windows 11
- Docker Desktop installe et demarre
- Docker Compose disponible (`docker compose`)
- Le PC et le telephone sur le meme reseau local (Wi-Fi/LAN)

Optionnel mais utile:

- VLC (lecture RTMP)
- FFmpeg/ffplay (debug faible latence)

### 2) Environnement Android

- Android Studio recent
- SDK Android avec API 36 (compile/target du projet)
- JDK 21 (toolchain Gradle detectee)
- NDK `17.2.4988734`
- CMake `3.22.1`
- Un appareil Android physique (la camera est requise)

Notes techniques du projet Android:

- `minSdk = 16`
- Java source/target = 11
- Dependance streaming: `com.github.pedroSG94.RootEncoder:library:2.6.7`

## Installation et demarrage

### Etape 1 - Cloner le depot

```bash
git clone <URL_DU_REPO>
cd edge_computing
```

### Etape 2 - Demarrer le serveur RTMP

Depuis la racine du projet:

```bash
docker compose -f rtmp-server/docker-compose.yml up -d
```

Verifier que le conteneur tourne:

```bash
docker ps
```

Verifier l'endpoint HTTP local:

```bash
curl http://localhost:8080/
```

La reponse attendue est `nginx-rtmp OK`.

Suivre les logs RTMP:

```bash
docker logs -f rtmp
```

### Etape 3 - Configurer l'URL RTMP dans l'app Android

Dans `EdgeComputer/app/src/main/java/com/example/edgecomputer/MainActivity.java`, adapter la constante:

```java
private static final String RTMP_URL = "rtmp://<IP_DU_PC>:1935/live/stream";
```

Exemple:

```text
rtmp://192.168.0.28:1935/live/stream
```

Pour obtenir l'IP du PC sous Windows:

```powershell
ipconfig
```

Prendre l'IPv4 de la carte reseau utilisee par le meme Wi-Fi/LAN que le telephone.

### Etape 4 - Lancer l'application Android

1. Ouvrir `EdgeComputer/` dans Android Studio
2. Laisser Gradle synchroniser le projet
3. Connecter un telephone Android
4. Accepter les permissions camera/micro
5. Lancer l'application

Le flux demarre automatiquement quand la surface video est prete.

## Verification du streaming

Depuis VLC (sur le PC), ouvrir le flux reseau:

```text
rtmp://<IP_DU_PC>:1935/live/stream
```

Dans les logs Docker, vous devez voir des evenements de type:

- `connect app='live'`
- `publish name='stream'`

## Comportement actuel de l'application

- Demarre le stream automatiquement apres permissions + initialisation camera
- Utilise l'accelerometre pour basculer de camera (face avant/arriere)
- Arrete le stream au `onPause` et a la destruction de la surface

## Depannage rapide

- Aucun flux dans VLC:
  - Verifier que le smartphone et le PC sont sur le meme reseau
  - Verifier l'IP configuree dans `RTMP_URL`
  - Verifier que le port `1935` n'est pas bloque (pare-feu Windows)
- Erreur de connexion RTMP:
  - Verifier que le conteneur `rtmp` est en cours d'execution
  - Lire les logs: `docker logs -f rtmp`
- Camera non disponible:
  - Fermer toute autre application utilisant deja la camera

## Commandes utiles

```bash
# Demarrer le serveur
docker compose -f rtmp-server/docker-compose.yml up -d

# Arreter le serveur
docker compose -f rtmp-server/docker-compose.yml down

# Logs serveur
docker logs -f rtmp
```

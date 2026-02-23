# Edge Computing

Projet compose de 3 briques :

- `EdgeComputer/` : application Android qui capture la camera et transmet un flux video TCP
- `server.py` : serveur Python qui recoit le flux TCP et le redistribue en MJPEG pour VLC
- `rtmp-server/` : ancienne approche RTMP via Nginx/Docker (conservee en reference)

Objectif : streamer la video du smartphone vers un PC via TCP, et visualiser le flux dans VLC.

---

## Architecture

```
┌─────────────────────────────────┐        TCP :9999       ┌──────────────────────────┐
│  Application Android            │ ─────────────────────> │  server.py (Python)      │
│  (EdgeComputer)                 │   protocole maison      │                          │
│                                 │   frames JPEG           │  Recepteur TCP           │
│  Camera YUV_420_888             │                         │  ↓                       │
│    ↓ conversion RGBA            │                         │  cv2.imdecode()          │
│  Buffer affichage               │                         │  ↓                       │
│    ↓ clone cv::Mat              │                         │  Serveur HTTP MJPEG      │
│  RGBA → BGR + imencode Q80      │                         │  :8080                   │
│  SendImage() via TCP            │                         └──────────┬───────────────┘
└─────────────────────────────────┘                                    │ HTTP MJPEG
                                                                       ↓
                                                             ┌─────────────────┐
                                                             │   VLC           │
                                                             │ http://IP:8080  │
                                                             └─────────────────┘
```

### Ports utilises

| Port | Protocole | Role |
|------|-----------|------|
| `9999/tcp` | TCP brut | Reception des frames depuis l'app Android |
| `8080/tcp` | HTTP MJPEG | Flux video vers VLC |

---

## Protocole TCP Android → Serveur

Protocole maison minimaliste sans overhead. Chaque message est compose d'un octet de type suivi d'un payload. Encodage little-endian natif ARM.

### Message type 1 — Dimensions (envoye une seule fois a la connexion)

```
Offset   Taille   Valeur exemple   Role
──────   ──────   ──────────────   ──────────────────────────────────
  0        1B     0x01             Type du message (= "dims")
  1        4B     0x80 02 00 00    Largeur en pixels (int32 LE) → 640
  5        4B     0xE0 01 00 00    Hauteur en pixels (int32 LE) → 480
```

Exemple brut pour 640×480 :
```
01  80 02 00 00  E0 01 00 00
^   ───────────  ───────────
│   width=640    height=480
└── type=1
```

> Ces dimensions sont indicatives. Les frames JPEG contiennent leurs propres dimensions en interne.

### Message type 2 — Frame JPEG (envoye en boucle)

```
Offset   Taille   Valeur exemple   Role
──────   ──────   ──────────────   ──────────────────────────────────
  0        1B     0x02             Type du message (= "jpeg")
  1        4B     0xA4 3C 00 00    Taille N du bloc JPEG (int32 LE) → 15524 B
  5        NB     FF D8 FF ...     Donnees JPEG brutes
  5+N-2    2B     ... FF D9        Marqueur de fin JPEG
```

Exemple brut :
```
02  A4 3C 00 00  FF D8 FF E0 ... FF D9
^   ───────────  ──────────────────────
│   N=15524 B   N octets de donnees JPEG
└── type=2
```

**Pourquoi type + taille ?**
TCP est un flux continu sans notion de message. L'octet de type distingue les messages entre eux, et les 4 octets de taille indiquent exactement combien d'octets lire pour la frame courante.

**Fiabilite de l'envoi :** la fonction `sendAll()` boucle sur `send()` jusqu'a envoi complet, car TCP peut fragmenter les donnees.

---

## Encodage video

### Pipeline complet cote Android (C++/NDK)

```
Capture camera (YUV_420_888)
  ↓  Image_Reader::DisplayImage()
Buffer RGBA 32 bits (ANativeWindow)
  ↓  cv::Mat CV_8UC4 — clone() avant unlock du buffer
Mat RGBA en memoire
  ↓  cv::cvtColor(COLOR_RGBA2BGR)   ← swap R↔B + suppression canal alpha
Mat BGR CV_8UC3
  ↓  cv::imencode(".jpg", bgr, jpeg, {IMWRITE_JPEG_QUALITY, 80})
JPEG bytes (FF D8 ... FF D9)
  ↓  sendAll() — envoi fiable via TCP
```

### Pourquoi RGBA → BGR ?

Android stocke les pixels en **RGBA** (ordre naturel + canal alpha). OpenCV travaille en **BGR** (ordre inverse, sans alpha). La conversion fait deux choses :

- Swap des canaux R et B (sinon les couleurs seraient inversees a l'affichage)
- Suppression du canal Alpha (toujours 0xFF, inutile a transmettre)

```
Pixel Android :  [R][G][B][A]  →  4 octets/pixel
Pixel OpenCV  :  [B][G][R]     →  3 octets/pixel
```

### Compression JPEG

| Parametre | Valeur | Detail |
|---|---|---|
| Algorithme | JPEG (DCT) | Compression avec perte |
| Qualite | 80 / 100 | Bon compromis taille / fidelite |
| Sous-echantillonnage chroma | 4:2:0 (defaut OpenCV) | U et V a 1/4 de resolution |
| Espace colorimetrique JPEG | YCbCr | Conversion interne par libjpeg |

Ce que fait JPEG en interne :
```
BGR → YCbCr → sous-echantillonnage 4:2:0 → DCT 8×8 → quantification → Huffman → FF D8...FF D9
```

Chaque frame est **independante** (pas de GOP, pas de compression inter-frame). A qualite 80, une frame 640×480 pese typiquement entre **15 et 50 Ko** selon la scene.

---

## Serveur Python (`server.py`)

### Prerequis

Python 3.8+, dependances :

```bash
pip install -r requirements.txt
```

`requirements.txt` :
```
opencv-python>=4.5
numpy>=1.21
```

### Configuration de l'IP

L'adresse IP du serveur est codee en dur dans l'app Android.
Fichier : `EdgeComputer/app/src/main/cpp/CV_Manager.cpp`, ligne 176 :

```cpp
const char hostname[] = "172.16.81.179";  // ← remplacer par l'IP du PC serveur
int port = 9999;
```

Recuperer l'IP du PC :
```powershell
ipconfig
```

### Lancement

```bash
python server.py
```

Le serveur demarre deux services en parallele :
- **Thread TCP** (port 9999) : attend la connexion de l'app Android
- **Serveur HTTP principal** (port 8080) : sert le flux MJPEG a VLC

Sortie attendue au demarrage :
```
[TCP] En attente de connexion sur le port 9999 ...
[HTTP] Serveur MJPEG demarre sur http://0.0.0.0:8080
```

Sortie apres connexion de l'app :
```
[TCP] Connexion de ('172.16.81.xxx', XXXXX)
[TCP] Dimensions recues : 640 x 480
[TCP] 30 frames recues (derniere : XXXXX octets)
```

### Logique interne

```
Thread TCP                          Thread HTTP (principal)
──────────────────────────          ──────────────────────────
srv.accept()                        HTTPServer.serve_forever()
  ↓                                   ↓
recv_exact(1B) → type               MJPEGHandler.do_GET()
  ↓                                   ↓ boucle infinie
type=1 → recv 8B dims               lire _latest_jpeg (avec lock)
type=2 → recv 4B size                 ↓
       → recv N bytes JPEG          ecrire --frame\r\n
         ↓                               Content-Type: image/jpeg\r\n
         cv2.imdecode()                  Content-Length: N\r\n
         ↓                               \r\n
         _latest_frame (Mat BGR)         [N bytes JPEG]
         _latest_jpeg  (bytes raw)       \r\n
         (proteges par lock)         flush → sleep 33ms (~30 fps)
```

`_latest_jpeg` est la copie brute des bytes JPEG recus — le serveur HTTP la renvoie directement sans reencodage. `_latest_frame` est le `cv::Mat` BGR decode par OpenCV, disponible pour tout traitement futur.

### Visualisation avec VLC

Via l'interface graphique :
```
Media → Ouvrir un flux reseau → http://<IP_DU_PC>:8080
```

Via la ligne de commande :
```bash
vlc http://<IP_DU_PC>:8080
```

---

## Prerequis generaux

- Windows 10/11
- Python 3.8+
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

Dans `SDK Manager`, installer au minimum :

- `Android SDK Platform 36`
- `Android SDK Build-Tools` compatibles API 36
- `Android SDK Platform-Tools`
- `Android SDK Command-line Tools (latest)`
- `NDK (Side by side)` version `17.2.4988734`
- `CMake` version `3.22.1`

### Permissions Android utilisees

- `CAMERA`, `INTERNET`, `RECORD_AUDIO`
- Application : `androidx.multidex.MultiDexApplication`

Fichier de reference : `EdgeComputer/app/src/main/AndroidManifest.xml`.

---

## Depannage

| Symptome | Cause probable | Solution |
|---|---|---|
| `[TCP] En attente...` reste bloque | App Android ne se connecte pas | Verifier l'IP dans `CV_Manager.cpp:176` et que les deux appareils sont sur le meme reseau |
| VLC affiche une image fixe | Serveur Python pas lance | Lancer `python server.py` avant l'app |
| Couleurs inversees | Mauvaise conversion couleur | Normal si modifie — la conversion RGBA→BGR est obligatoire |
| Erreur de build Android | JDK/SDK/NDK incorrect | Verifier les versions dans le tableau ci-dessus |
| Erreur camera Android | Permissions manquantes | Valider les permissions runtime camera a l'ecran |

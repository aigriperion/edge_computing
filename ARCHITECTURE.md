# Cyclope — Architecture & Documentation technique

## Vue d'ensemble

Cyclope est un système de **surveillance à distance multi-appareils** en temps réel. Un ou plusieurs téléphones Android tournent en tant qu'**agents headless** (sans interface visible) et transmettent vidéo, audio, GPS et notifications vers un **dashboard web** via WebRTC, le tout coordonné par un **serveur de signalisation Node.js** sur le réseau local.

```
┌─────────────────┐        WebSocket (signalisation)         ┌──────────────────┐
│  Android Agent  │ ◄──────────────────────────────────────► │  Serveur Node.js │
│  (Cyclope app)  │                                          │  port 3000       │
└────────┬────────┘                                          └────────┬─────────┘
         │                                                            │
         │  WebRTC P2P (vidéo + audio)                                │  WebSocket
         │  DataChannel (GPS JSON)                                    │
         │◄──────────────────────────────────────────────────────────►│
         │                                                  ┌─────────┴────────┐
         │                                                  │  Dashboard Web   │
         └──────────────────────────────────────────────────│  navigateur      │
                          WebRTC P2P direct                 └──────────────────┘
```

---

## 1. Architecture — Les trois composants

### 1.1 App Android (`Cyclope/`)

Agent headless qui tourne en arrière-plan permanent via un `ForegroundService`.

| Fichier | Rôle |
|---|---|
| `MainActivity.java` | UI minimaliste — bouton start/stop, demande de permissions, dialog accès notifications |
| `CyclopeService.java` | Orchestrateur principal — `ForegroundService`, cycle de vie, JNI natif |
| `WebRtcClient.java` | Pipeline WebRTC — PeerConnection, signalisation, DataChannel |
| `CaptationVideo.java` | Tracks vidéo WebRTC + bridge NDK→WebRTC |
| `CaptationSon.java` | Tracks audio WebRTC (annulation d'écho, suppression bruit) |
| `CaptationGPS.java` | Écoute GPS (provider GPS + réseau), callback sur chaque update |
| `CaptationNotification.java` | `NotificationListenerService` — intercepte les notifs système |
| `SignalingClient.java` | WebSocket client OkHttp — connexion au serveur de signalisation |
| `ServerDiscovery.java` | Auto-découverte du serveur (UDP broadcast + fallback HTTP scan) |

### 1.2 Serveur de signalisation (`server/server.js`)

Serveur Node.js avec Express + WebSocket.

- **Rôle** : relais de signalisation WebRTC entre les Androids et le browser. Ne voit jamais le flux vidéo/audio.
- **Port** : 3000 (HTTP + WebSocket)
- **Registry** : `peers['browser']` + `peers[deviceId]`
- **UDP broadcast** : envoie `{ type: "cyclope-server", port: 3000 }` toutes les 2 secondes sur l'adresse broadcast de chaque interface réseau (ex. `192.168.1.255:41234`)
- **Endpoint HTTP** : `GET /cyclope` retourne le JSON de découverte (fallback si UDP bloqué)

### 1.3 Dashboard Web (`server/public/index.html`)

SPA vanilla JS, thème dark terminal, aucune dépendance sauf **Leaflet.js** (cartographie).

---

## 2. Flux de données

### 2.1 Découverte du serveur (au démarrage de l'agent)

```
Serveur                          Android
   │  UDP broadcast toutes 2s        │
   │ ──────────────────────────────► │  ServerDiscovery écoute port 41234
   │  { type:"cyclope-server",        │  → onServerFound("ws://192.168.1.X:3000")
   │    port:3000 }                   │
   │                                  │  [si UDP bloqué par pare-feu/AP isolation]
   │  GET /cyclope                    │
   │ ◄──────────────────────────────  │  HTTP scan /24 en parallèle (32 threads)
   │  { type:"cyclope-server",... }   │  timeout 400ms par IP
   │ ──────────────────────────────► │
```

**Transport** : UDP datagramme (port 41234) + HTTP/TCP fallback  
**Thread** : `ServerDiscovery-UDP` (thread dédié) + pool de 32 threads OkHttp

---

### 2.2 Enregistrement WebSocket

```
Android                         Serveur                        Browser
   │  connect ws://IP:3000          │                              │
   │ ──────────────────────────►    │                              │
   │  { type:"register",            │                              │
   │    role:"android",             │                              │
   │    id:"ANDROID_ID",            │                              │
   │    name:"Samsung Galaxy..." }  │                              │
   │ ──────────────────────────►    │                              │
   │  { type:"registered" }         │  { type:"peer-joined",       │
   │ ◄──────────────────────────    │    id:..., name:... }        │
   │                                │ ──────────────────────────► │
```

**Transport** : WebSocket JSON (OkHttp côté Android, `ws` côté Node.js)  
**Thread** : thread OkHttp dispatcher (I/O)

---

### 2.3 Négociation WebRTC (quand le browser clique sur un appareil)

```
Browser                         Serveur                        Android
   │  { type:"request-offer",       │                              │
   │    targetId:"..." }             │                              │
   │ ──────────────────────────►    │ ──────────────────────────► │
   │                                │                              │  nativeStart() → LED caméra
   │                                │                              │  createOffer()
   │                                │  { type:"offer", sdp:... }  │
   │  { type:"offer",               │ ◄──────────────────────────  │
   │    sdp:..., fromId:... }        │                              │
   │ ◄──────────────────────────    │                              │
   │  createAnswer()                │                              │
   │  { type:"answer",              │                              │
   │    sdp:..., targetId:... }      │                              │
   │ ──────────────────────────►    │ ──────────────────────────► │
   │                                │                              │  setRemoteDescription()
   │  [échange ICE candidates]       │                              │
   │ ◄─────────────────────────────────────────────────────────── │
   │  [connexion P2P établie]        │                              │
```

**Transport** : WebSocket JSON pour la signalisation, puis **P2P direct** (STUN Google) pour les médias  
**Thread Android** : `WebRtcClient.executor` (SingleThreadExecutor) pour toutes les opérations PeerConnection

---

### 2.4 Flux vidéo & audio (après connexion P2P)

```
NDK C++ (CameraLoop)
   │  frame YUV NV21 (byte[])
   │  throttling : skip si intervalle < 1s/m_target_fps  ← ABR
   │  → onNdkFrame() [thread NDK]
   ▼
WebRtcClient.onNdkFrame()
   │  → CaptationVideo.onNdkFrame()
   │     NV21Buffer → VideoFrame
   │     → capturerObserver.onFrameCaptured()
   ▼
Pipeline WebRTC interne (libwebrtc)
   │  encode H.264/VP8
   │  bitrate limité par RtpSender.setParameters()  ← ABR
   │  RTP packets
   ▼
Browser (via P2P WebRTC)
   │  décode vidéo → <video> element
   │  audio → haut-parleurs (uniquement en vue focus)
```

**Transport** : RTP/SRTP P2P (UDP direct, ou relay TURN si nécessaire)  
**Thread** : thread NDK propre → callback Java → pipeline WebRTC interne (multi-thread libwebrtc)

---

### 2.5 Adaptive Bitrate — ABR (flux vidéo uniquement)

L'ABR ajuste la qualité vidéo en temps réel en fonction des conditions réseau mesurées par WebRTC.

```
statsScheduler (1s)
   │  sendStats() → peerConnection.getStats()  [asynchrone]
   ▼
processNetworkStats()  [thread WebRTC interne]
   │  lit "candidate-pair" → availableOutgoingBitrate
   │  lit "remote-inbound-rtp" → fractionLost, currentRoundTripTime
   │  → executor.execute(evaluateAndApplyQuality)
   ▼
evaluateAndApplyQuality()  [WebRtcClient.executor]
   │  state machine : HIGH / MEDIUM / LOW
   │  downgrade → immédiat
   │  upgrade   → après 5 secondes stables (hysteresis)
   ▼
applyQualityProfile()
   ├── RtpSender.setParameters(maxBitrateBps)  → limite encodeur H.264
   └── captureListener.onSetTargetFps(fps)
              ↓ JNI
       CV_Manager::SetTargetFps(fps)
              ↓ CameraLoop
       skip frame si intervalle < 1s/target_fps
```

**Niveaux de qualité** :

| Niveau | Condition de déclenchement | Bitrate max | FPS NDK |
|--------|---------------------------|-------------|---------|
| HIGH   | bw > 1 200 kbps ET perte < 5% | 2 000 kbps | 30 |
| MEDIUM | bw 400–1 200 kbps OU perte 5–10% | 600 kbps | 15 |
| LOW    | bw < 400 kbps OU perte > 10%    | 200 kbps | 10 |

**Stats envoyées via DataChannel** (JSON, 1×/seconde) :
```json
{
  "type":     "stats",
  "fps":      25,
  "width":    1280,
  "height":   720,
  "quality":  "HIGH",
  "bw_kbps":  1450,
  "loss_pct": "0.5",
  "rtt_ms":   12
}
```

**Fichiers concernés** :

| Fichier | Rôle ABR |
|---------|----------|
| `WebRtcClient.java` | State machine, lecture stats, `RtpSender.setParameters()` |
| `CV_Manager.h/.cpp` | `m_target_fps`, `m_last_sent_frame_ns`, `SetTargetFps()` |
| `native-lib.cpp` | JNI `nativeSetTargetFps` |
| `CyclopeService.java` | Native déclaration + `onSetTargetFps` override |
| `index.html` | Affichage qualité / bw / perte / RTT dans focus panel |

---

### 2.7 Flux GPS

```
Android OS (LocationManager)
   │  onLocationChanged() [thread principal Android]
   ▼
CaptationGPS.Listener
   │  → CyclopeService : nativeSetGpsData() [JNI, usage NDK]
   │  → WebRtcClient.sendGps()
   │       DataChannel WebRTC "telemetry" (JSON)
   │       { lat, lon, alt, acc, ts }
   ▼
Browser
   │  pc.ondatachannel → channel.onmessage
   │  → updateTileGps() : overlay sur la tile
   │  → updateFocusGps() : panneau GPS + carte Leaflet
```

**Transport** : WebRTC DataChannel (ordered, reliable) — **pas** via le serveur  
**Fréquence** : 1 update/seconde (GPS provider + réseau)  
**Thread** : thread système Android LocationManager → callback synchrone

---

### 2.8 Flux notifications

```
Android OS (NotificationListenerService)
   │  onNotificationPosted() [thread système]
   │  filtre : ignore com.example.cyclope
   ▼
CaptationNotification.sListener
   │  → WebRtcClient.sendNotification()
   │       SignalingClient.send() — WebSocket JSON
   │       { type:"notification", app, title, text, ts }
   ▼
Serveur (relay Android → Browser)
   │  enrichit avec fromId
   ▼
Browser
   │  handleSignaling case "notification"
   │  → stocké dans dev.notifications[] (50 max)
   │  → addGlobalNotif() : panneau droit global
   │  → renderFocusNotifs() : panneau focus si device ouvert
```

**Transport** : WebSocket JSON via le serveur (pas P2P)  
**Thread** : thread système Android → WebSocket OkHttp dispatcher

---

### 2.9 Commandes Browser → Android

| Commande | JSON | Effet sur Android |
|---|---|---|
| `request-offer` | `{ type, targetId }` | `nativeStart()` + crée PeerConnection + envoie offer |
| `stop-stream` | `{ type, targetId }` | `nativeStop()` + teardown PeerConnection |
| `flip-camera` | `{ type, targetId }` | `nativeFlipCamera()` via JNI |

**Transport** : WebSocket JSON (browser → serveur → relay → Android)

---

## 3. Modèle de threads (Android)

```
┌─────────────────────────────────────────────────────────────┐
│                    THREADS ANDROID                           │
│                                                              │
│  Main Thread          Service + LocationManager callbacks   │
│  ├─ CyclopeService.onStartCommand()                         │
│  └─ CaptationGPS callbacks                                  │
│                                                              │
│  ServerDiscovery-UDP  Thread dédié (bloquant sur receive)   │
│                                                              │
│  OkHttp Dispatcher    Pool I/O pour WebSocket + HTTP scan   │
│  (SignalingClient)    onMessage() → handleSignaling()        │
│                                                              │
│  WebRtcClient.executor  SingleThreadExecutor                │
│  ├─ createOfferAndPeerConnection()                          │
│  ├─ teardownPeerConnection()                                │
│  ├─ setRemoteDescription() / addIceCandidate()              │
│  └─ stop-stream handler                                     │
│                                                              │
│  NDK CameraLoop       Thread natif C++                      │
│  └─ → onNdkFrame() [callback vers Java]                     │
│                                                              │
│  libwebrtc internals  Pool threads WebRTC (encode/ICE/RTP)  │
│                                                              │
│  HTTP Scan Pool       32 threads OkHttp (découverte)        │
│                                                              │
│  NotificationListener Thread système Android                │
└─────────────────────────────────────────────────────────────┘
```

**Règle de sécurité** : toutes les opérations sur `PeerConnection` passent par `WebRtcClient.executor` (single thread). Les callbacks WebRTC postent sur l'executor via `executor.execute()` pour éviter les race conditions.

---

## 4. Fonctionnalités & outils

### 4.1 Vidéo

| Élément | Outil / API |
|---|---|
| Capture caméra | NDK C++ — Camera2 NDK (`ACameraManager`) |
| Format frames | YUV NV21 → `NV21Buffer` |
| Encodage | libwebrtc `DefaultVideoEncoderFactory` (H.264 hardware si dispo, VP8 fallback) |
| Track vidéo | `VideoSource` + `VideoTrack` (WebRTC Java) |
| Rendu browser | `<video>` HTML5, `RTCPeerConnection.ontrack` |
| Flip caméra | `nativeFlipCamera()` JNI → switch camera NDK |
| **ABR bitrate** | `RtpSender.setParameters(maxBitrateBps)` — limite l'encodeur H.264 |
| **ABR FPS** | `nativeSetTargetFps()` JNI → `CV_Manager::m_target_fps` → skip frames dans `CameraLoop` |

**LED caméra** : allumée uniquement quand `nativeStart()` est appelé (i.e. quand un observateur est actif). Éteinte sur `nativeStop()` (déconnexion, `stop-stream`, ICE failed).

### 4.2 Audio

| Élément | Outil / API |
|---|---|
| Capture | `AudioSource` WebRTC Java |
| Traitement | `googEchoCancellation: true`, `googNoiseSuppression: true` |
| Track audio | `AudioTrack` WebRTC Java |
| Transport | RTP/SRTP P2P (même PeerConnection que la vidéo) |
| Lecture browser | Muet dans les tiles grille — son uniquement en **vue focus** |
| Contrôle volume | Slider HTML `<input type="range">`, `videoEl.volume` |

### 4.3 GPS

| Élément | Outil / API |
|---|---|
| Providers | `GPS_PROVIDER` + `NETWORK_PROVIDER` simultanés |
| Fréquence | 1 update/seconde, 0m de distance minimale |
| Transport | WebRTC DataChannel JSON (ordered) — hors serveur |
| Affichage tile | Overlay lat/lon/précision en bas de chaque tile |
| Affichage focus | Coordonnées texte + carte Leaflet interactive |
| Carte | **Leaflet.js** + tuiles **CartoDB Dark Matter** (thème sombre cohérent) |
| Marqueur | Cercle cyan avec halo + cercle de précision semi-transparent |

### 4.4 Notifications

| Élément | Outil / API |
|---|---|
| Capture | `NotificationListenerService` (permission spéciale) |
| Filtre | Ignore `com.example.cyclope` (propres notifications Cyclope) |
| Transport | WebSocket JSON via serveur (pas P2P) |
| Stockage | `dev.notifications[]` par device (50 max, FIFO) |
| Affichage global | Panneau droit de la grille (toutes sources) |
| Affichage focus | Panneau focus (filtré par device sélectionné) |
| Activation | Paramètres → Applications → Accès spécial → Accès aux notifications |

### 4.5 Découverte serveur

| Méthode | Mécanisme | Condition |
|---|---|---|
| **Primaire** — UDP broadcast | Serveur → `255.255.255.255` / broadcast interface toutes 2s sur port 41234. Android écoute avec `MulticastLock`. | Fonctionne si le pare-feu Windows et le routeur laissent passer les broadcasts |
| **Fallback** — HTTP scan | Android scanne toutes les IPs du /24 (`192.168.X.1–254`) via `GET :3000/cyclope` — 32 requêtes parallèles, timeout 400ms | Fonctionne même avec AP isolation ou pare-feu bloquant UDP |

### 4.6 Multi-appareils

- N appareils Android peuvent être connectés simultanément.
- Chaque appareil a sa propre `PeerConnection` indépendante côté browser.
- La grille s'adapte dynamiquement : 1→plein écran, 2→2 colonnes, 3→3 colonnes, 4→2×2, 5-6→3×2.
- Cliquer sur une tile ouvre la **vue focus** (plein écran, audio activé, GPS détaillé, notifications du device).
- Les tiles grille sont toujours **muettes** — le son ne sort que dans le focus.

---

## 5. Signaux de contrôle (protocole WebSocket)

### Android → Serveur → Browser

| Type | Champs | Description |
|---|---|---|
| `register` | `role, id, name` | Enregistrement Android |
| `offer` | `sdp` | SDP offer WebRTC |
| `ice-candidate` | `candidate{sdpMid, sdpMLineIndex, candidate}` | Candidat ICE |
| `notification` | `app, title, text, ts` | Notification interceptée |

### Browser → Serveur → Android (relay via `targetId`)

| Type | Champs | Description |
|---|---|---|
| `request-offer` | `targetId` | Demande de flux (allume la caméra) |
| `stop-stream` | `targetId` | Arrêt du flux (éteint la caméra) |
| `flip-camera` | `targetId` | Bascule caméra avant/arrière |
| `answer` | `targetId, sdp` | SDP answer WebRTC |
| `ice-candidate` | `targetId, candidate` | Candidat ICE |

### Serveur → Browser

| Type | Champs | Description |
|---|---|---|
| `registered` | `role` | Confirmation enregistrement |
| `peer-joined` | `id, name` | Nouvel Android connecté |
| `peer-left` | `id` | Android déconnecté |

### Serveur → Android

| Type | Description |
|---|---|
| `registered` | Confirmation (la caméra reste en veille) |
| `stop-stream` | Envoyé à **tous** les Androids quand le browser se déconnecte |

---

## 6. Permissions Android

| Permission | Usage |
|---|---|
| `CAMERA` | Capture caméra NDK |
| `RECORD_AUDIO` | Capture microphone WebRTC |
| `ACCESS_FINE_LOCATION` | GPS haute précision |
| `ACCESS_COARSE_LOCATION` | GPS réseau |
| `INTERNET` | WebSocket + WebRTC |
| `FOREGROUND_SERVICE` | Service headless |
| `FOREGROUND_SERVICE_CAMERA` | ForegroundService avec type camera (Android 14+) |
| `FOREGROUND_SERVICE_MICROPHONE` | ForegroundService avec type microphone (Android 14+) |
| `POST_NOTIFICATIONS` | Affichage notification persistante service (Android 13+) |
| `ACCESS_WIFI_STATE` | Lecture IP locale + DhcpInfo pour le scan HTTP |
| `CHANGE_WIFI_MULTICAST_STATE` | `MulticastLock` pour recevoir les broadcasts UDP |
| `BIND_NOTIFICATION_LISTENER_SERVICE` | Accès aux notifications système (permission spéciale) |

---

## 7. Notification persistante (ForegroundService)

Android impose une notification visible pour tout `ForegroundService`. Configuration pour la rendre invisible :

- Canal `cyclope_agent_v2` avec `IMPORTANCE_MIN` → aucune icône dans la barre de statut
- `setPriority(PRIORITY_MIN)` + `setSilent(true)`
- `setShowBadge(false)`
- Texte statique "Agent actif" (pas de mise à jour d'état)

> Si la notification reste visible après rebuild, c'est que l'ancien canal est en cache. Changer le `CHANNEL_ID` force la création d'un nouveau canal.

---

## 8. Stack technique

| Composant | Technologies |
|---|---|
| App Android | Java, Android SDK, NDK C++, libwebrtc (WebRTC Android), OkHttp |
| Serveur | Node.js, Express, `ws` (WebSocket), `dgram` (UDP), `os` (interfaces réseau) |
| Dashboard | HTML5 vanilla, CSS Grid, JavaScript ES2020, Leaflet.js, CartoDB Dark Matter tiles |
| Protocole médias | WebRTC (SRTP/RTP), STUN Google (`stun.l.google.com:19302`) |
| Protocole signalisation | WebSocket JSON custom |
| Protocole découverte | UDP broadcast + HTTP REST fallback |
| Télémétrie GPS | WebRTC DataChannel (JSON) |
| Notifications | WebSocket JSON (via serveur) |

---

## 9. Démarrage

### Serveur
```bash
cd server/
npm install   # première fois
npm start     # écoute sur 0.0.0.0:3000, broadcast UDP sur :41234
```

### Android
1. Android Studio → `Run > Run 'app'` (`Shift+F10`)
2. Sur le téléphone : appuyer sur **Démarrer l'agent**
3. Accorder les permissions caméra / localisation / micro / notifications
4. Activer l'accès notifications : **Paramètres → Applications → Accès spécial → Accès aux notifications → Cyclope**

### Dashboard
Ouvrir `http://IP_DU_PC:3000` dans un navigateur sur le même réseau.

---

## 10. Tester l'ABR (Adaptive Bitrate)

### 10.1 Prérequis

- Serveur Node.js démarré (`npm start` dans `server/`)
- App installée et agent démarré sur le téléphone
- Dashboard ouvert dans un navigateur — le téléphone doit apparaître dans la liste

### 10.2 Vérifier que l'ABR fonctionne : Logcat

Dans Android Studio ou via `adb logcat` :

```bash
adb logcat -s WebRtcClient:I
```

En conditions normales (WiFi rapide) :
```
WebRtcClient: ABR → HIGH (2000 kbps, 30 fps)
```

En dégradant le réseau (voir ci-dessous) :
```
WebRtcClient: ABR → MEDIUM (600 kbps, 15 fps)
WebRtcClient: ABR → LOW (200 kbps, 10 fps)
```

Après 5 secondes de bonne connectivité :
```
WebRtcClient: ABR → MEDIUM (600 kbps, 15 fps)
WebRtcClient: ABR → HIGH (2000 kbps, 30 fps)
```

En parallèle, pour observer le throttling NDK :
```bash
adb logcat -s CameraNDK:D
# → CyclopeService: target fps → 15
```

### 10.3 Provoquer une dégradation réseau

**Méthode 1 — Throttling WiFi sur le routeur (recommandé)**
> Limiter la bande passante du téléphone à 300 kbps dans les paramètres QoS du routeur.
> Le niveau doit passer de HIGH → MEDIUM → LOW en quelques secondes.

**Méthode 2 — Mode avion + reconnexion**
> Activer puis désactiver le mode avion → la reconnexion ICE en 4G vs WiFi déclenche souvent un cycle ABR observable.

**Méthode 3 — Android Developer Options**
> `Paramètres → Options pour les développeurs → Simulate network throttle` → sélectionner `EDGE (2G)` ou `3G`.
> Le niveau ABR passe à MEDIUM ou LOW dans les 2 secondes.

**Méthode 4 — `tc` Linux (si le serveur est sur Linux)**
```bash
# Limiter à 300kbps sur l'interface réseau du serveur
sudo tc qdisc add dev eth0 root tbf rate 300kbit burst 32kbit latency 400ms
# Supprimer la limitation
sudo tc qdisc del dev eth0 root
```

### 10.4 Vérifier dans le dashboard

Cliquer sur la tile du téléphone pour ouvrir la **vue focus**, puis observer le panneau droit :

| Champ | Valeur attendue en WiFi | Valeur attendue en 3G simulé |
|-------|------------------------|------------------------------|
| QUALITÉ | `HIGH` (vert) | `MEDIUM` (jaune) ou `LOW` (rouge) |
| BANDE PASSANTE | > 1200 kbps | < 400 kbps |
| PERTE PAQUETS | < 1% | > 5% |
| RTT | < 50 ms | > 100 ms |
| Barre bande passante | Verte, large | Rouge, étroite |

### 10.5 Vérifier que le FPS baisse visuellement

En passant à MEDIUM (15 fps) ou LOW (10 fps), le flux vidéo dans le navigateur doit être **notablement moins fluide**. Le champ "FPS envoyés" dans le focus panel passe de ~30 à ~15 ou ~10.

### 10.6 Points de débogage courants

| Symptôme | Cause probable | Solution |
|---------|---------------|----------|
| QUALITÉ reste à `HIGH` même en 3G | `availableOutgoingBitrate` absent des stats ICE (stats pas encore stables) | Attendre 5–10s après connexion |
| FPS ne baisse pas visuellement | `nativeSetTargetFps()` non atteint — vérifier Logcat `CameraNDK` | Vérifier que `CaptureListener.onSetTargetFps` est overridé dans `CyclopeService` |
| Stats `bw_kbps = 0` dans le dashboard | `candidate-pair` pas encore sélectionné | Normal les 2 premières secondes de connexion |
| Upgrade trop lent | `UPGRADE_STABLE_THRESHOLD = 5` → 5 secondes minimum | Modifier la constante dans `WebRtcClient.java` pour les tests |
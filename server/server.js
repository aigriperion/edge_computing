// Lancer : npm install && npm start
// Android se connecte en ws://VOTRE_IP:3000
// Browser ouvre http://VOTRE_IP:3000

const express = require('express');
const { WebSocketServer } = require('ws');
const http = require('http');
const path = require('path');

const PORT = process.env.PORT || 3000;

const app = express();
app.use(express.static(path.join(__dirname, 'public')));

const server = http.createServer(app);
const wss = new WebSocketServer({ server });

// Deux rôles fixes : 'android' et 'browser'
// Le serveur relaie simplement les messages entre les deux
const peers = {};

wss.on('connection', (ws, req) => {
    const ip = req.socket.remoteAddress;
    let role = null;

    ws.on('message', (raw) => {
        let msg;
        try { msg = JSON.parse(raw); } catch { return; }

        // Premier message obligatoire : { type: 'register', role: 'android' | 'browser' }
        if (msg.type === 'register') {
            role = msg.role;
            peers[role] = ws;
            console.log(`[${ts()}] ${role} connecté (${ip})`);
            ws.send(JSON.stringify({ type: 'registered', role }));

            // Notifier l'autre pair qu'un nouveau pair est arrivé
            const other = role === 'android' ? peers['browser'] : peers['android'];
            if (other?.readyState === 1) {
                other.send(JSON.stringify({ type: 'peer-joined', role }));
            }
            return;
        }

        // Relayer tous les autres messages (offer, answer, ice-candidate, telemetry…)
        const target = role === 'android' ? peers['browser'] : peers['android'];
        if (target?.readyState === 1) {
            target.send(raw.toString());
        } else {
            console.warn(`[${ts()}] [relay] pas de destinataire pour ${role} (msg: ${msg.type})`);
        }
    });

    ws.on('close', () => {
        if (!role) return;
        delete peers[role];
        console.log(`[${ts()}] ${role} déconnecté`);

        const other = role === 'android' ? peers['browser'] : peers['android'];
        if (other?.readyState === 1) {
            other.send(JSON.stringify({ type: 'peer-left', role }));
        }
    });

    ws.on('error', (err) => console.error(`[${ts()}] erreur WS (${role || ip}):`, err.message));
});

function ts() {
    return new Date().toISOString().substring(11, 23);
}

server.listen(PORT, '0.0.0.0', () => {
    console.log(`Serveur de signalisation : http://localhost:${PORT}`);
    console.log(`Android WebSocket        : ws://<VOTRE_IP>:${PORT}`);
    console.log(`(HTTPS requis pour caméra/micro hors localhost)`);
});
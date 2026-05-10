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

// peers['browser'] = WebSocket du browser
// peers[deviceId]  = WebSocket d'un Android
const peers = {};
const meta  = {}; // deviceId → { name }

wss.on('connection', (ws, req) => {
    const ip = req.socket.remoteAddress;
    let role = null; // 'browser' | deviceId

    ws.on('message', (raw) => {
        let msg;
        try { msg = JSON.parse(raw); } catch { return; }

        // ── Enregistrement ──────────────────────────────────────────────────
        if (msg.type === 'register') {
            if (msg.role === 'browser') {
                role = 'browser';
                peers['browser'] = ws;
                console.log(`[${ts()}] browser connecté (${ip})`);
                ws.send(JSON.stringify({ type: 'registered', role: 'browser' }));

                // Notifier le browser de tous les androids déjà connectés
                Object.keys(peers).filter(k => k !== 'browser').forEach(id => {
                    ws.send(JSON.stringify({ type: 'peer-joined', id, name: meta[id]?.name || id }));
                });

            } else if (msg.role === 'android') {
                role = msg.id || `android_${Date.now()}`;
                peers[role] = ws;
                meta[role]  = { name: msg.name || role };
                console.log(`[${ts()}] android connecté : ${role} (${meta[role].name}) (${ip})`);
                ws.send(JSON.stringify({ type: 'registered', role: 'android', id: role }));

                // Notifier le browser
                if (peers['browser']?.readyState === 1) {
                    peers['browser'].send(JSON.stringify({
                        type: 'peer-joined', id: role, name: meta[role].name
                    }));
                }
            }
            return;
        }

        // ── Relais ──────────────────────────────────────────────────────────
        if (role === 'browser') {
            // Browser → Android ciblé via targetId
            const targetId = msg.targetId;
            const target = targetId ? peers[targetId] : null;
            if (target?.readyState === 1) {
                target.send(raw.toString());
            } else {
                console.warn(`[${ts()}] [relay] android introuvable : ${targetId}`);
            }

        } else if (role) {
            // Android → Browser (on ajoute fromId pour que le browser sache qui parle)
            const browser = peers['browser'];
            if (browser?.readyState === 1) {
                const enriched = Object.assign({}, msg, { fromId: role });
                browser.send(JSON.stringify(enriched));
            }
        }
    });

    ws.on('close', () => {
        if (!role) return;
        delete peers[role];
        delete meta[role];
        console.log(`[${ts()}] déconnecté : ${role}`);

        if (role === 'browser') return;

        // Notifier le browser qu'un android est parti
        if (peers['browser']?.readyState === 1) {
            peers['browser'].send(JSON.stringify({ type: 'peer-left', id: role }));
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
});
// Lancer : npm install && npm start
// Android se connecte automatiquement via UDP broadcast
// Browser ouvre http://VOTRE_IP:3000

const express = require('express');
const { WebSocketServer } = require('ws');
const http = require('http');
const path = require('path');
const dgram = require('dgram');
const os = require('os');

const PORT = process.env.PORT || 3000;

const app = express();
app.use(express.static(path.join(__dirname, 'public')));
app.get('/cyclope', (req, res) => res.json({ type: 'cyclope-server', port: PORT }));

const server = http.createServer(app);
const wss = new WebSocketServer({ server });

// peers['browser'] = WebSocket du browser
// peers[deviceId]  = WebSocket d'un Android
const peers = {};
const meta  = {}; // deviceId → { name, connectedAt, lastMsgAt, ip, msgCount }

wss.on('connection', (ws, req) => {
    const ip = req.socket.remoteAddress;
    let role = null; // 'browser' | deviceId

    ws.on('message', (raw) => {
        let msg;
        try { msg = JSON.parse(raw); } catch { return; }

        // Mise à jour activité
        if (role && meta[role]) {
            meta[role].lastMsgAt = Date.now();
            meta[role].msgCount  = (meta[role].msgCount || 0) + 1;
        }

        // ── Enregistrement ──────────────────────────────────────────────────
        if (msg.type === 'register') {
            if (msg.role === 'browser') {
                role = 'browser';
                peers['browser'] = ws;
                console.log(`[${ts()}] BROWSER connecté (${ip})`);
                ws.send(JSON.stringify({ type: 'registered', role: 'browser' }));

                // Notifier le browser de tous les androids déjà connectés
                Object.keys(peers).filter(k => k !== 'browser').forEach(id => {
                    ws.send(JSON.stringify({ type: 'peer-joined', id, name: meta[id]?.name || id }));
                });

            } else if (msg.role === 'android') {
                role = msg.id || `android_${Date.now()}`;
                const wasKnown = !!meta[role];
                peers[role] = ws;
                meta[role]  = { name: msg.name || role, connectedAt: Date.now(), lastMsgAt: Date.now(), ip, msgCount: 0 };
                if (wasKnown) {
                    console.log(`[${ts()}] ANDROID reconnecté : ${meta[role].name} (${role.substring(0,8)}…) depuis ${ip}`);
                } else {
                    console.log(`[${ts()}] ANDROID connecté  : ${meta[role].name} (${role.substring(0,8)}…) depuis ${ip}`);
                }
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

        // ── Crash report Android ─────────────────────────────────────────────
        if (msg.type === 'crash-report' && role && role !== 'browser') {
            const name    = meta[role]?.name || role;
            const crashTs = msg.ts ? new Date(msg.ts).toISOString() : '?';
            console.error(`[${ts()}] *** CRASH REPORT [${name}] — survenu le ${crashTs} ***`);
            console.error(`[${ts()}]   ${msg.msg}`);
            if (msg.trace) {
                msg.trace.split('\n').slice(0, 8).forEach(l => console.error(`[${ts()}]   ${l}`));
            }
            // Relais au browser
            if (peers['browser']?.readyState === 1) {
                peers['browser'].send(JSON.stringify(Object.assign({}, msg, { fromId: role })));
            }
            return;
        }

        // ── Relais ──────────────────────────────────────────────────────────
        if (role === 'browser') {
            const targetId = msg.targetId;
            const target   = targetId ? peers[targetId] : null;
            if (target?.readyState === 1) {
                target.send(raw.toString());
            } else {
                console.warn(`[${ts()}] [relay] android introuvable : ${targetId}`);
            }

        } else if (role) {
            const browser = peers['browser'];
            if (browser?.readyState === 1) {
                browser.send(JSON.stringify(Object.assign({}, msg, { fromId: role })));
            }
        }
    });

    ws.on('close', () => {
        if (!role) return;
        const m = meta[role];

        if (role === 'browser') {
            delete peers[role];
            console.log(`[${ts()}] BROWSER déconnecté`);
            // Dire à tous les Android d'arrêter leur capture caméra
            Object.keys(peers).forEach(id => {
                if (peers[id]?.readyState === 1) {
                    peers[id].send(JSON.stringify({ type: 'stop-stream' }));
                }
            });
            return;
        }

        // Android déconnecté
        const uptime  = m ? Math.round((Date.now() - m.connectedAt) / 1000) : '?';
        const lastMsg = m?.lastMsgAt ? Math.round((Date.now() - m.lastMsgAt) / 1000) : '?';
        const name    = m?.name || role;
        console.log(`[${ts()}] ANDROID déconnecté : ${name} (${role.substring(0,8)}…) — uptime ${uptime}s, dernier msg il y a ${lastMsg}s, ${m?.msgCount || 0} msgs`);

        delete peers[role];
        delete meta[role];

        if (peers['browser']?.readyState === 1) {
            peers['browser'].send(JSON.stringify({ type: 'peer-left', id: role }));
        }
    });

    ws.on('error', (err) => console.error(`[${ts()}] ERREUR WS (${meta[role]?.name || role || ip}): ${err.message}`));
});

function ts() {
    return new Date().toISOString().substring(11, 23);
}

server.listen(PORT, '0.0.0.0', () => {
    console.log(`Serveur de signalisation : http://localhost:${PORT}`);
    startUdpBroadcast();
});

function getBroadcastAddresses() {
    const results = [];
    for (const iface of Object.values(os.networkInterfaces())) {
        for (const addr of iface) {
            if (addr.family !== 'IPv4' || addr.internal) continue;
            const ip   = addr.address.split('.').map(Number);
            const mask = addr.netmask.split('.').map(Number);
            const bcast = ip.map((b, i) => (b | (~mask[i] & 0xff)));
            results.push(bcast.join('.'));
        }
    }
    return results.length ? results : ['255.255.255.255'];
}

function startUdpBroadcast() {
    const udp = dgram.createSocket('udp4');
    const msg = Buffer.from(JSON.stringify({ type: 'cyclope-server', port: PORT }));

    udp.bind(() => {
        udp.setBroadcast(true);
        const addresses = getBroadcastAddresses();
        console.log(`UDP broadcast actif → ${addresses.join(', ')}`);
        setInterval(() => {
            addresses.forEach(addr => {
                udp.send(msg, 0, msg.length, 41234, addr, (err) => {
                    if (err) console.error(`[UDP] erreur envoi vers ${addr}:`, err.message);
                });
            });
        }, 2000);
    });
}
import socket
import struct
import threading
import queue
from http.server import HTTPServer, BaseHTTPRequestHandler

TCP_PORT = 9999
HTTP_PORT = 8080

# Magic bytes du protocole
MAGIC_0 = 0xED
MAGIC_1 = 0x9E
MAX_PAYLOAD_SIZE = 10 * 1024 * 1024  # 10 MB

# SPS/PPS config stockee globalement pour les nouveaux clients HTTP
_config_data = None
_config_lock = threading.Lock()

# Liste des queues des clients HTTP abonnes (pub-sub)
_subscribers = []
_subscribers_lock = threading.Lock()


def recv_exact(sock, n):
    """Lit exactement n octets depuis la socket."""
    buf = bytearray()
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("Connexion fermee par le client")
        buf.extend(chunk)
    return bytes(buf)


def publish(data):
    """Publie un chunk H.264 a tous les abonnes HTTP."""
    with _subscribers_lock:
        dead = []
        for q in _subscribers:
            try:
                q.put_nowait(data)
            except queue.Full:
                dead.append(q)
        for q in dead:
            _subscribers.remove(q)


def subscribe():
    """Cree et enregistre une nouvelle queue d'abonne."""
    q = queue.Queue(maxsize=300)
    with _subscribers_lock:
        _subscribers.append(q)
    return q


def unsubscribe(q):
    """Retire une queue d'abonne."""
    with _subscribers_lock:
        if q in _subscribers:
            _subscribers.remove(q)


def tcp_receiver():
    global _config_data

    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("0.0.0.0", TCP_PORT))
    srv.listen(1)
    print(f"[TCP] En attente de connexion sur le port {TCP_PORT} ...")

    while True:
        conn, addr = srv.accept()
        print(f"[TCP] Connexion de {addr}")
        try:
            handle_client(conn)
        except ConnectionError as e:
            print(f"[TCP] Deconnexion : {e}")
        except Exception as e:
            print(f"[TCP] Erreur : {e}")
        finally:
            conn.close()
            print(f"[TCP] Connexion fermee, en attente d'une nouvelle ...")


def resync(conn):
    """Tente de resynchroniser le flux en scannant les magic bytes."""
    print("[TCP] Desync detecte, tentative de resynchronisation ...")
    skipped = 0
    prev = b'\x00'
    while True:
        b = recv_exact(conn, 1)
        if prev[0] == MAGIC_0 and b[0] == MAGIC_1:
            print(f"[TCP] Resynchronise apres {skipped} octets ignores")
            return True
        prev = b
        skipped += 1
        if skipped > MAX_PAYLOAD_SIZE:
            print("[TCP] Impossible de resynchroniser, abandon")
            return False


def handle_client(conn):
    global _config_data
    frame_count = 0

    while True:
        # Lire magic bytes
        magic = recv_exact(conn, 2)
        if magic[0] != MAGIC_0 or magic[1] != MAGIC_1:
            print(f"[TCP] Magic invalide : 0x{magic[0]:02X} 0x{magic[1]:02X}")
            if not resync(conn):
                break
            # Apres resync, on a deja consomme les magic bytes
            # On continue pour lire type + size + payload

        # Lire type (1 octet) + size (4 octets LE)
        header = recv_exact(conn, 5)
        msg_type = header[0]
        size = struct.unpack("<I", header[1:5])[0]

        # Validation de la taille
        if size > MAX_PAYLOAD_SIZE:
            print(f"[TCP] Taille invalide : {size} octets (max {MAX_PAYLOAD_SIZE}), abandon")
            break

        if msg_type == 1:
            # Dimensions : payload = 8 octets
            if size != 8:
                print(f"[TCP] Taille dims invalide : {size} (attendu 8)")
                break
            data = recv_exact(conn, size)
            width, height = struct.unpack("<ii", data)
            print(f"[TCP] Dimensions recues : {width} x {height}")

        elif msg_type == 3:
            # H.264 config (SPS/PPS)
            if size == 0:
                print("[TCP] Config vide, ignore")
                continue
            config = recv_exact(conn, size)

            with _config_lock:
                _config_data = config

            publish(config)
            print(f"[TCP] Config SPS/PPS recue ({size} octets)")

        elif msg_type == 2:
            # H.264 frame data
            if size == 0:
                continue
            frame_data = recv_exact(conn, size)

            publish(frame_data)
            frame_count += 1

            if frame_count % 30 == 0:
                print(f"[TCP] {frame_count} frames H.264 recues (derniere : {size} octets)")

        else:
            print(f"[TCP] Type inconnu : {msg_type}, abandon")
            break


class H264StreamHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200)
        self.send_header("Content-Type", "video/h264")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "close")
        self.end_headers()

        print(f"[HTTP] Client VLC connecte : {self.client_address}")

        # Envoyer le SPS/PPS en premier si disponible
        with _config_lock:
            config = _config_data

        if config:
            try:
                self.wfile.write(config)
                self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
                print(f"[HTTP] Client VLC deconnecte : {self.client_address}")
                return

        # S'abonner au flux
        q = subscribe()
        try:
            while True:
                try:
                    chunk = q.get(timeout=5.0)
                except queue.Empty:
                    continue

                self.wfile.write(chunk)
                self.wfile.flush()

        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            print(f"[HTTP] Client VLC deconnecte : {self.client_address}")
        finally:
            unsubscribe(q)

    def log_message(self, format, *args):
        pass


if __name__ == "__main__":
    tcp_thread = threading.Thread(target=tcp_receiver, daemon=True)
    tcp_thread.start()

    http_server = HTTPServer(("0.0.0.0", HTTP_PORT), H264StreamHandler)
    print(f"[HTTP] Serveur H.264 demarre sur http://0.0.0.0:{HTTP_PORT}")
    print(f"[INFO] Ouvrir VLC -> Media -> Flux reseau -> http://<IP_DU_PC>:{HTTP_PORT}")
    try:
        http_server.serve_forever()
    except KeyboardInterrupt:
        print("\n[INFO] Arret du serveur")
        http_server.shutdown()

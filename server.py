import socket
import struct
import threading
import queue
from http.server import HTTPServer, BaseHTTPRequestHandler

TCP_PORT = 9999
HTTP_PORT = 8080

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


def handle_client(conn):
    global _config_data
    frame_count = 0

    while True:
        type_byte = recv_exact(conn, 1)
        msg_type = type_byte[0]

        if msg_type == 1:
            # Dimensions
            data = recv_exact(conn, 8)
            width, height = struct.unpack("<ii", data)
            print(f"[TCP] Dimensions recues : {width} x {height}")

        elif msg_type == 3:
            # H.264 config (SPS/PPS)
            data = recv_exact(conn, 4)
            size = struct.unpack("<i", data)[0]
            config = recv_exact(conn, size)

            with _config_lock:
                _config_data = config

            publish(config)
            print(f"[TCP] Config SPS/PPS recue ({size} octets)")

        elif msg_type == 2:
            # H.264 frame data
            data = recv_exact(conn, 4)
            size = struct.unpack("<i", data)[0]
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

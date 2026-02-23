import socket
import struct
import threading
import time

import cv2
import numpy as np
from http.server import HTTPServer, BaseHTTPRequestHandler

TCP_PORT = 9999
HTTP_PORT = 8080

_latest_frame = None
_latest_jpeg = None
_frame_lock = threading.Lock()


def recv_exact(sock, n):
    """Lit exactement n octets depuis la socket."""
    buf = bytearray()
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("Connexion fermee par le client")
        buf.extend(chunk)
    return bytes(buf)


def tcp_receiver():
    global _latest_frame, _latest_jpeg

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
    global _latest_frame, _latest_jpeg
    frame_count = 0

    while True:
        type_byte = recv_exact(conn, 1)
        msg_type = type_byte[0]

        if msg_type == 1:
            data = recv_exact(conn, 8)
            width, height = struct.unpack("<ii", data)
            print(f"[TCP] Dimensions recues : {width} x {height}")

        elif msg_type == 2:
            header = recv_exact(conn, 12)
            width, height, size = struct.unpack("<iii", header)
            raw_data = recv_exact(conn, size)
            frame_count += 1

            # Reconstruire l'image RGBA brute
            frame_rgba = np.frombuffer(raw_data, dtype=np.uint8).reshape((height, width, 4))
            frame_bgr = cv2.cvtColor(frame_rgba, cv2.COLOR_RGBA2BGR)

            # Encoder en JPEG pour le flux MJPEG HTTP
            _, jpeg_encoded = cv2.imencode('.jpg', frame_bgr)

            with _frame_lock:
                _latest_frame = frame_bgr
                _latest_jpeg = jpeg_encoded.tobytes()

            if frame_count % 30 == 0:
                print(f"[TCP] {frame_count} frames recues ({width}x{height}, {size} octets bruts)")

        else:
            print(f"[TCP] Type inconnu : {msg_type}, abandon")
            break


BOUNDARY = b"--frame"


class MJPEGHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200)
        self.send_header("Content-Type",
                         "multipart/x-mixed-replace; boundary=frame")
        self.end_headers()

        print(f"[HTTP] Client VLC connecte : {self.client_address}")
        try:
            while True:
                with _frame_lock:
                    jpeg = _latest_jpeg

                if jpeg is None:
                    time.sleep(0.05)
                    continue

                self.wfile.write(BOUNDARY + b"\r\n")
                self.wfile.write(b"Content-Type: image/jpeg\r\n")
                self.wfile.write(f"Content-Length: {len(jpeg)}\r\n".encode())
                self.wfile.write(b"\r\n")
                self.wfile.write(jpeg)
                self.wfile.write(b"\r\n")
                self.wfile.flush()

                time.sleep(0.033)  # ~30 fps max

        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            print(f"[HTTP] Client VLC deconnecte : {self.client_address}")

    def log_message(self, format, *args):
        pass


if __name__ == "__main__":
    tcp_thread = threading.Thread(target=tcp_receiver, daemon=True)
    tcp_thread.start()

    http_server = HTTPServer(("0.0.0.0", HTTP_PORT), MJPEGHandler)
    print(f"[HTTP] Serveur MJPEG demarre sur http://0.0.0.0:{HTTP_PORT}")
    print(f"[INFO] Ouvrir VLC -> Media -> Flux reseau -> http://<IP_DU_PC>:{HTTP_PORT}")
    try:
        http_server.serve_forever()
    except KeyboardInterrupt:
        print("\n[INFO] Arret du serveur")
        http_server.shutdown()

#!/usr/bin/env python3
"""Local protocol smoke test. Temporarily changes and then restores macOS clipboard."""

import base64
import json
import pathlib
import socket
import subprocess
import time

from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import x25519
from cryptography.hazmat.primitives.kdf.hkdf import HKDF


CONFIG_PATH = pathlib.Path.home() / ".ez-clipboard" / "config.json"
AAD = b"ez-clipboard-v1"


def send_line(sock: socket.socket, value: dict) -> None:
    sock.sendall(json.dumps(value, separators=(",", ":")).encode() + b"\n")


def read_line(stream) -> dict:
    line = stream.readline()
    if not line:
        raise RuntimeError("server closed connection")
    return json.loads(line)


def main() -> None:
    config = json.loads(CONFIG_PATH.read_text())
    saved_clipboard = subprocess.run(
        ["pbpaste"], check=False, stdout=subprocess.PIPE
    ).stdout
    marker_to_mac = f"ez-smoke-android-{time.time_ns()}"
    marker_to_android = f"ez-smoke-mac-{time.time_ns()}"

    try:
        with socket.create_connection(("127.0.0.1", config["port"]), timeout=5) as sock:
            sock.settimeout(5)
            stream = sock.makefile("rb")
            phone_key = x25519.X25519PrivateKey.generate()
            phone_public = phone_key.public_key().public_bytes(
                serialization.Encoding.Raw,
                serialization.PublicFormat.Raw,
            )
            send_line(
                sock,
                {
                    "type": "hello",
                    "device": "Protocol smoke test",
                    "publicKey": base64.b64encode(phone_public).decode(),
                },
            )
            ack = read_line(stream)
            assert ack["type"] == "hello_ack"
            mac_public = x25519.X25519PublicKey.from_public_bytes(
                base64.b64decode(ack["publicKey"])
            )
            secret = HKDF(
                algorithm=hashes.SHA256(),
                length=32,
                salt=AAD,
                info=b"clipboard-key",
            ).derive(phone_key.exchange(mac_public))

            aes = AESGCM(secret)
            message_nonce = b"abcdefghijkl"
            latest_at = int(time.time() * 1000)
            send_line(
                sock,
                {
                    "type": "clip",
                    "id": "smoke-android-to-mac",
                    "updatedAt": latest_at,
                    "nonce": base64.b64encode(message_nonce).decode(),
                    "data": base64.b64encode(
                        aes.encrypt(message_nonce, marker_to_mac.encode(), AAD)
                    ).decode(),
                },
            )
            time.sleep(1)
            actual = subprocess.run(
                ["pbpaste"], check=True, stdout=subprocess.PIPE
            ).stdout.decode()
            assert actual == marker_to_mac

            old_nonce = b"old-message!"
            send_line(
                sock,
                {
                    "type": "clip",
                    "id": "smoke-out-of-order-old",
                    "updatedAt": latest_at - 1_000,
                    "nonce": base64.b64encode(old_nonce).decode(),
                    "data": base64.b64encode(
                        aes.encrypt(old_nonce, b"stale-content", AAD)
                    ).decode(),
                },
            )
            time.sleep(0.3)
            actual = subprocess.run(
                ["pbpaste"], check=True, stdout=subprocess.PIPE
            ).stdout.decode()
            assert actual == marker_to_mac

            subprocess.run(
                ["pbcopy"], input=marker_to_android.encode(), check=True
            )
            deadline = time.monotonic() + 5
            while time.monotonic() < deadline:
                response = read_line(stream)
                if response.get("type") != "clip":
                    continue
                clear = aes.decrypt(
                    base64.b64decode(response["nonce"]),
                    base64.b64decode(response["data"]),
                    AAD,
                ).decode()
                assert clear == marker_to_android
                print("PROTOCOL_SMOKE_OK")
                return
            raise RuntimeError("timed out waiting for Mac clipboard")
    finally:
        subprocess.run(["pbcopy"], input=saved_clipboard, check=True)


if __name__ == "__main__":
    main()

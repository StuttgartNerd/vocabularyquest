#!/usr/bin/env python3
import argparse
import socket
import struct
import sys


def send_packet(sock: socket.socket, request_id: int, packet_type: int, payload: str) -> None:
    data = payload.encode("utf-8") + b"\x00\x00"
    header = struct.pack("<iii", len(data) + 8, request_id, packet_type)
    sock.sendall(header + data)


def recv_packet(sock: socket.socket) -> tuple[int, int, str]:
    raw_length = sock.recv(4)
    if len(raw_length) < 4:
        raise RuntimeError("RCON response length header not received")

    (length,) = struct.unpack("<i", raw_length)
    body = b""
    while len(body) < length:
        chunk = sock.recv(length - len(body))
        if not chunk:
            break
        body += chunk

    if len(body) < 10:
        raise RuntimeError("RCON response body too short")

    request_id, packet_type = struct.unpack("<ii", body[:8])
    payload = body[8:-2].decode("utf-8", errors="replace")
    return request_id, packet_type, payload


def main() -> int:
    parser = argparse.ArgumentParser(description="Send one command via Minecraft RCON")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=25575)
    parser.add_argument("--password", required=True)
    parser.add_argument("command")
    args = parser.parse_args()

    request_id = 99
    with socket.create_connection((args.host, args.port), timeout=5) as sock:
        send_packet(sock, request_id, 3, args.password)
        auth_id, _, _ = recv_packet(sock)
        if auth_id == -1:
            print("RCON auth failed", file=sys.stderr)
            return 2

        send_packet(sock, request_id, 2, args.command)
        _, _, payload = recv_packet(sock)
        if payload:
            print(payload)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())

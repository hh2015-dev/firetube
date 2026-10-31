#!/usr/bin/env python3
"""Encrypt a private-use debug APK before it leaves the standard public GitHub runner.

The RSA *public* key is committed. The corresponding private key must never be
committed, uploaded as a workflow secret, echoed to CI, or copied to a runner.
Format: fixed magic + uint16_be RSA-OAEP-wrapped AES key length + wrapped key
+ 12-byte nonce + AES-256-GCM ciphertext including the 16-byte authentication tag.
"""
import hashlib
import os
import struct
import sys
from pathlib import Path

from cryptography.hazmat.primitives import serialization, hashes
from cryptography.hazmat.primitives.asymmetric import padding, rsa
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

MAGIC = b"FIRETUBE_ENCRYPTED_APK_V1\n"
AAD = b"FireTube personal APK transfer v1"


def encrypt(plaintext_file: Path, public_key_file: Path, output_file: Path) -> None:
    public_key = serialization.load_pem_public_key(public_key_file.read_bytes())
    if not isinstance(public_key, rsa.RSAPublicKey) or public_key.key_size < 3072:
        raise ValueError("Expected RSA public key with at least 3072 bits")
    apk = plaintext_file.read_bytes()
    if apk[:2] != b"PK" or not 10_000_000 < len(apk) < 100_000_000:
        raise ValueError("Input does not look like a FireTube APK")
    key = AESGCM.generate_key(bit_length=256)
    nonce = os.urandom(12)
    wrapped = public_key.encrypt(key, padding.OAEP(
        mgf=padding.MGF1(algorithm=hashes.SHA256()),
        algorithm=hashes.SHA256(),
        label=None,
    ))
    encrypted = AESGCM(key).encrypt(nonce, apk, AAD)
    output_file.parent.mkdir(parents=True, exist_ok=True)
    output_file.write_bytes(MAGIC + struct.pack(">H", len(wrapped)) + wrapped + nonce + encrypted)
    print("Encrypted APK bytes:", output_file.stat().st_size)
    print("Plain APK SHA-256:", hashlib.sha256(apk).hexdigest())
    print("Only authenticated ciphertext is eligible for artifact upload.")


if __name__ == "__main__":
    if len(sys.argv) != 4:
        sys.exit("usage: python encrypt_apk.py app-debug.apk public.pem output.apk.enc")
    encrypt(Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3]))

"""Ephemeral encrypted delivery of existing APK download capabilities to this runner.
Never publishes APK bytes, download URLs, OAuth credentials or private signing keys.
"""
import base64
import hashlib
import json
import os
from pathlib import Path
import sys
import time
import urllib.request
import urllib.error
from urllib.parse import urlparse
from cryptography.hazmat.primitives import serialization, hashes
from cryptography.hazmat.primitives.asymmetric import rsa, padding
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

ROOT = Path(os.environ['RUNNER_TEMP']) / 'firetube-private'
EXPECTED = {
    '215': ('794de4b325d769a3730d37738a92e833b0327ed414663db3a4677bc4abd8bc6c',33247110),
    '217': ('182269a9c124b04cd9f4b2f6a7d28e40541e9606b407ca7dfbfc461aa7618371',33372320),
}

def binding():
    return ('FireTube emulator input v1:'+os.environ['GITHUB_RUN_ID']+':'+os.environ['GITHUB_SHA']).encode()

def host_ok(url):
    u=urlparse(url)
    return u.scheme=='https' and u.hostname and (u.hostname.endswith('.oaiusercontent.com') or u.hostname.endswith('.blob.core.windows.net'))

def prepare():
    ROOT.mkdir(mode=0o700)
    key=rsa.generate_private_key(public_exponent=65537,key_size=3072)
    target=ROOT/'recipient-private.pem'
    target.write_bytes(key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()))
    target.chmod(0o600)
    public=Path('public-recipient');public.mkdir()
    (public/'recipient.pem').write_bytes(key.public_key().public_bytes(serialization.Encoding.PEM,serialization.PublicFormat.SubjectPublicKeyInfo))
    (public/'binding.json').write_text(json.dumps({'run_id':os.environ['GITHUB_RUN_ID'],'source_commit':os.environ['GITHUB_SHA']}))
    print('Ephemeral public recipient ready; private key remains on this runner only.')

def receive():
    url='https://raw.githubusercontent.com/'+os.environ['GITHUB_REPOSITORY']+'/refs/heads/test/drive-offline-upgrade-regression-20261102/.test-handoffs/'+os.environ['GITHUB_RUN_ID']+'.json'
    deadline=time.monotonic()+900
    while True:
        try:
            with urllib.request.urlopen(url,timeout=20) as response:
                envelope=json.loads(response.read(65536))
            break
        except (urllib.error.URLError,TimeoutError):
            if time.monotonic()>=deadline:raise RuntimeError('Encrypted APK handoff not received before timeout')
            time.sleep(15)
    key=serialization.load_pem_private_key((ROOT/'recipient-private.pem').read_bytes(),None)
    aes=key.decrypt(base64.b64decode(envelope['wrapped_key']),padding.OAEP(mgf=padding.MGF1(hashes.SHA256()),algorithm=hashes.SHA256(),label=None))
    payload=json.loads(AESGCM(aes).decrypt(base64.b64decode(envelope['nonce']),base64.b64decode(envelope['ciphertext']),binding()))
    if set(payload)!={'215','217'}:raise RuntimeError('Unexpected private input set')
    for version,(digest,size) in EXPECTED.items():
        private_url=payload[version]
        if not host_ok(private_url):raise RuntimeError('Unapproved download capability host')
        try:
            with urllib.request.urlopen(private_url,timeout=120) as response:
                if not host_ok(response.url):raise RuntimeError('Unapproved redirect')
                apk=response.read(size+1)
        except Exception:
            raise RuntimeError('Private APK download failed; URL suppressed') from None
        if len(apk)!=size or hashlib.sha256(apk).hexdigest()!=digest:
            raise RuntimeError('Original APK size or SHA-256 mismatch')
        target=ROOT/(version+'.apk');target.write_bytes(apk);target.chmod(0o600)
        print('Original APK '+version+' hash and size: PASS')
    (ROOT/'recipient-private.pem').unlink()

if __name__=='__main__':
    try:
        {'prepare':prepare,'receive':receive}[sys.argv[1]]()
    except Exception:
        print('PRIVATE INPUT FAILED (sensitive diagnostics suppressed)',file=sys.stderr)
        sys.exit(1)

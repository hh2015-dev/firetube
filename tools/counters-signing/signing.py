#!/usr/bin/env python3
"""Fail-closed Counters signing. Signing secrets never leave the local signer."""
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = 'com.palmerintech.firetube.drivefavoritescounterspreview'
SHA1 = 'A41B369138EBA1D50E23BF110955BD386BEB2A68'
SHA256 = 'CFE89608EDD0944E0C0D571784D6B0554AAD8AE5BAEA7968BF0DEFD956CADE12'

def run(args):
    p = subprocess.run(args, capture_output=True)
    if p.returncode:
        raise ValueError('Signing tool failed; secret-bearing diagnostics suppressed')
    return p.stdout

def version():
    p = json.loads((ROOT / 'config/counters-version.json').read_text())
    n, old = p['versionCode'], p['previousVersionCode']
    if type(n) is not int or type(old) is not int or not 215 <= old < n <= 2100000000:
        raise ValueError('versionCode must increase above the previous delivered version (at least 215)')
    override = os.environ.get('VERSION_CODE')
    if override and override != str(n):
        raise ValueError('VERSION_CODE override disagrees with the reviewed version file')
    return n

def preflight():
    version()
    names = ['COUNTERS_KEYSTORE', 'COUNTERS_STORE_PASSWORD', 'COUNTERS_KEY_ALIAS', 'COUNTERS_KEY_PASSWORD']
    if any(not os.environ.get(n) for n in names):
        raise ValueError('Missing Counters signing configuration; no Debug-key fallback is allowed')
    if not Path(os.environ['COUNTERS_KEYSTORE']).is_file():
        raise ValueError('Approved signing keystore is missing')
    args = ['keytool', '-J-Duser.language=en', '-J-Duser.country=US', '-keystore', os.environ['COUNTERS_KEYSTORE'], '-storepass:env', 'COUNTERS_STORE_PASSWORD', '-alias', os.environ['COUNTERS_KEY_ALIAS']]
    cert = run(args + ['-exportcert'])
    if hashlib.sha1(cert).hexdigest().upper() != SHA1 or hashlib.sha256(cert).hexdigest().upper() != SHA256:
        raise ValueError('Signing certificate mismatch; refusing to build or sign')
    if b'PrivateKeyEntry' not in run(args + ['-list']):
        raise ValueError('Keystore entry has no private key')
    return version()

def manifest_identity(apk):
    with zipfile.ZipFile(apk) as z:
        if z.testzip() is not None:
            raise ValueError('APK ZIP integrity failed')
        m = z.read('AndroidManifest.xml')
    strings = []; pos = 8
    while pos < len(m):
        typ, header, size = struct.unpack_from('<HHI', m, pos)
        if size < header or size == 0 or pos + size > len(m):
            raise ValueError('Malformed manifest')
        if typ == 1:
            count, _, flags, base, _ = struct.unpack_from('<IIIII', m, pos + 8)
            for i in range(count):
                o = pos + base + struct.unpack_from('<I', m, pos + header + 4*i)[0]
                if flags & 0x100:
                    def length(o):
                        a = m[o]
                        return (((a & 127) << 8) | m[o+1], o+2) if a & 128 else (a, o+1)
                    _, o = length(o); n, o = length(o)
                    strings.append(m[o:o+n].decode('utf-8'))
                else:
                    n = struct.unpack_from('<H', m, o)[0]; o += 2
                    if n & 0x8000:
                        n = ((n & 0x7fff) << 16) | struct.unpack_from('<H', m, o)[0]; o += 2
                    strings.append(m[o:o+2*n].decode('utf-16le'))
        elif typ == 0x102:
            _, name, start, stride, count = struct.unpack_from('<IIHHH', m, pos+16)
            if strings[name] == 'manifest':
                attrs = {}
                for i in range(count):
                    o = pos+16+start+i*stride
                    _, key, raw = struct.unpack_from('<III', m, o)
                    data = struct.unpack_from('<I', m, o+16)[0]
                    attrs[strings[key]] = strings[raw] if raw != 0xffffffff else (strings[data] if m[o+15] == 3 else data)
                return attrs['package'], int(attrs['versionCode'])
        pos += size
    raise ValueError('Manifest identity unavailable')

def verify(apk, jar):
    n = version()
    if manifest_identity(apk) != (PACKAGE, n):
        raise ValueError('APK package or versionCode mismatch')
    s = run(['java', '-jar', str(jar), 'verify', '--verbose', '--print-certs', str(apk)]).decode()
    if not re.search(r"(?m)^Number of signers: 1\s*$", s):
        raise ValueError("APK certificate mismatch or multiple signers")
    for label, expected in [('SHA-1', SHA1), ('SHA-256', SHA256)]:
        actual = re.findall(r'(?m)^.*\bcertificate ' + label + r' digest: ([0-9a-fA-F]+)\s*$', s)
        if not actual or {v.upper() for v in actual} != {expected}:
            raise ValueError('APK certificate mismatch or multiple signers')
    if 'Verified using v2 scheme (APK Signature Scheme v2): true' not in s:
        raise ValueError('APK v2 signature verification failed')
    b = Path(apk).read_bytes()
    return dict(package=PACKAGE, versionCode=n, certificate_sha1=SHA1, certificate_sha256=SHA256, apk_sha256=hashlib.sha256(b).hexdigest(), size_bytes=len(b), apksigner='PASS', zip_crc='PASS')

def main():
    command = sys.argv[1]
    if command == 'preflight':
        print('Approved certificate and private-key entry: PASS; versionCode:', preflight())
    elif command == 'verify':
        print(json.dumps(verify(Path(sys.argv[2]), Path(sys.argv[3])), indent=2))
    elif command == 'sign':
        preflight()
        source, jar, output = map(Path, sys.argv[2:5])
        if output.exists() or source.resolve() == output.resolve():
            raise ValueError('Refusing to overwrite an existing APK')
        if manifest_identity(source) != (PACKAGE, version()):
            raise ValueError('Unsigned input identity mismatch')
        # The remote compiler must never choose a signing identity.
        probe = subprocess.run(['java', '-jar', str(jar), 'verify', str(source)], capture_output=True)
        if probe.returncode == 0:
            raise ValueError('Expected unsigned compilation input')
        try:
            run(['java', '-jar', str(jar), 'sign', '--ks', os.environ['COUNTERS_KEYSTORE'], '--ks-key-alias', os.environ['COUNTERS_KEY_ALIAS'], '--ks-pass', 'env:COUNTERS_STORE_PASSWORD', '--key-pass', 'env:COUNTERS_KEY_PASSWORD', '--min-sdk-version', '26', '--v2-signing-enabled', 'true', '--out', str(output), str(source)])
            output.chmod(0o600)
            report = verify(output, jar)
            output.with_suffix('.verification.json').write_text(json.dumps(report, indent=2) + '\n')
            print(json.dumps(report, indent=2))
        except Exception:
            output.unlink(missing_ok=True)
            raise
    else:
        raise ValueError('Expected preflight, sign, or verify')

if __name__ == '__main__':
    try:
        main()
    except Exception as e:
        print('COUNTERS SIGNING FAILED:', str(e), file=sys.stderr)
        sys.exit(1)

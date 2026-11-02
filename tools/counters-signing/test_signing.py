import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec=importlib.util.spec_from_file_location('signing',Path(__file__).with_name('signing.py'))
s=importlib.util.module_from_spec(spec); spec.loader.exec_module(s)

class SigningGuards(unittest.TestCase):
    def test_missing_configuration_fails(self):
        with patch.dict(os.environ,{},clear=True):
            with self.assertRaisesRegex(ValueError,'Missing Counters'): s.preflight()
    def test_missing_keystore_fails(self):
        with patch.dict(os.environ,dict(COUNTERS_KEYSTORE='/no/such/key',COUNTERS_STORE_PASSWORD='test-only',COUNTERS_KEY_ALIAS='test',COUNTERS_KEY_PASSWORD='test-only'),clear=True):
            with self.assertRaisesRegex(ValueError,'missing'): s.preflight()
    def test_reused_or_decreased_version_fails(self):
        for n in [214,215]:
            with self.subTest(n=n), patch.object(Path,'read_text',return_value=json.dumps(dict(versionCode=n,previousVersionCode=215))):
                with self.assertRaisesRegex(ValueError,'increase'): s.version()
    def test_version_override_fails(self):
        with patch.dict(os.environ,{'VERSION_CODE':'999'}):
            with self.assertRaisesRegex(ValueError,'override'): s.version()
    def test_wrong_certificate_fails(self):
        with tempfile.NamedTemporaryFile() as key, patch.dict(os.environ,dict(COUNTERS_KEYSTORE=key.name,COUNTERS_STORE_PASSWORD='test-only',COUNTERS_KEY_ALIAS='test',COUNTERS_KEY_PASSWORD='test-only'),clear=True), patch.object(s,'run',return_value=b'an unapproved certificate'):
            with self.assertRaisesRegex(ValueError,'certificate mismatch'): s.preflight()
    def test_signed_apk_wrong_identity_fails_before_verification(self):
        with patch.object(s,'manifest_identity',return_value=('wrong.package',216)):
            with self.assertRaisesRegex(ValueError,'package or versionCode'): s.verify(Path('x'),Path('jar'))
    def test_old_apk_version_fails(self):
        with patch.object(s,'manifest_identity',return_value=(s.PACKAGE,215)):
            with self.assertRaisesRegex(ValueError,'package or versionCode'): s.verify(Path('x'),Path('jar'))
    def test_wrong_final_signer_fails(self):
        with patch.object(s,'manifest_identity',return_value=(s.PACKAGE,217)), patch.object(s,'run',return_value=b'Signer #1 certificate SHA-1 digest: deadbeef\n'):
            with self.assertRaisesRegex(ValueError,'certificate mismatch'): s.verify(Path('x'),Path('jar'))

if __name__=='__main__': unittest.main()

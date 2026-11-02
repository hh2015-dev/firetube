"""Mocked SDK regressions: these are NOT an emulator integration test."""
import contextlib
import io
import json
import pathlib
import shlex
import sys
import tempfile
import unittest
from unittest.mock import patch
from test_verify_drive_offline_upgrade import upgrade

AUDIO = upgrade.OFFLINE_ROOT + '/test-account/audio-' + 'a'*64 + '.bin'
FILES = {AUDIO: {'bytes': 3, 'sha256': 'test-digest'}}

class RegressionTests(unittest.TestCase):
    def apk_check(self, report, package=None):
        with tempfile.TemporaryDirectory() as d:
            apk=pathlib.Path(d)/'test.apk';apk.write_bytes(b'mock')
            details=f"package: name='{package or upgrade.PACKAGE}' versionCode='217'"
            with patch.object(upgrade.shutil,'which',return_value='/mock/tool'), patch.object(upgrade,'cmd',side_effect=[details,report]):
                return upgrade.inspect_apk(apk)

    def report(self, prefix='V3.0 Signer:', sha256=None, signers=1):
        return f'Number of signers: {signers}\n{prefix} certificate SHA-1 digest: {upgrade.EXPECTED_SHA1}\n{prefix} certificate SHA-256 digest: {sha256 or upgrade.EXPECTED_SHA256}\nVerified using v2 scheme (APK Signature Scheme v2): true'

    def test_current_apksigner_scheme_label(self):
        self.assertEqual(217,self.apk_check(self.report()))
    def test_multiple_signers_rejected(self):
        with self.assertRaisesRegex(RuntimeError,'exactly one'):self.apk_check(self.report(signers=2))
    def test_wrong_sha256_rejected(self):
        with self.assertRaisesRegex(RuntimeError,'signature does not match'):self.apk_check(self.report(sha256='00'))
    def test_wrong_package_rejected(self):
        with self.assertRaisesRegex(RuntimeError,'Unexpected APK package'):self.apk_check(self.report(),package='wrong')
    def test_nonempty_audio_and_remote_quoting(self):
        extra=upgrade.OFFLINE_ROOT+'/test-account/file with spaces.json'
        listing=(AUDIO+'\0'+extra+'\0').encode()
        with patch.object(upgrade,'adb',side_effect=[listing,b'abc',b'{}']) as adb:
            result=upgrade.snapshot('emulator-5554')
            self.assertEqual(2,len(result))
            self.assertEqual(['run-as',upgrade.PACKAGE,'cat',extra],shlex.split(adb.call_args.args[3]))
    def test_empty_audio_rejected(self):
        with patch.object(upgrade,'adb',side_effect=[(AUDIO+'\0').encode(),b'']):
            with self.assertRaisesRegex(RuntimeError,'audio is empty'):upgrade.snapshot('emulator-5554')
    def test_unexpected_path_rejected(self):
        with patch.object(upgrade,'adb',return_value=b'../outside\0'):
            with self.assertRaisesRegex(RuntimeError,'Unexpected offline file path'):upgrade.snapshot('emulator-5554')
    def test_installed_apk_is_pulled_and_verified(self):
        with patch.object(upgrade,'adb',side_effect=['package:/data/app/test/base.apk','']) as adb, patch.object(upgrade,'inspect_apk',return_value=215) as inspect:
            self.assertEqual(215,upgrade.inspect_installed_apk('emulator-5554'))
            self.assertEqual(('emulator-5554','pull'),adb.call_args.args[1:3])
            inspect.assert_called_once()

    def scenario(self, versions=None, snapshots=None, incoming=217, identity_error=False):
        stack=contextlib.ExitStack();self.addCleanup(stack.close)
        d=stack.enter_context(tempfile.TemporaryDirectory());state=pathlib.Path(d)/'baseline.json'
        state.write_text(json.dumps(dict(package=upgrade.PACKAGE,serial='emulator-5554',versionCode=215,files=FILES)))
        stack.enter_context(patch.object(sys,'argv',['verify','upgrade','--state',str(state),'--apk',str(pathlib.Path(d)/'new.apk')]))
        stack.enter_context(patch.object(upgrade,'check_emulator',return_value='emulator-5554'))
        stack.enter_context(patch.object(upgrade,'installed_version',side_effect=versions or [215,217]))
        stack.enter_context(patch.object(upgrade,'inspect_installed_apk',side_effect=RuntimeError('wrong installed signer') if identity_error else [215,217]))
        stack.enter_context(patch.object(upgrade,'snapshot',side_effect=snapshots or [FILES,FILES]))
        stack.enter_context(patch.object(upgrade,'inspect_apk',return_value=incoming))
        adb=stack.enter_context(patch.object(upgrade,'adb',return_value='Success'))
        stack.enter_context(contextlib.redirect_stdout(io.StringIO()))
        return adb

    def test_old_installed_signature_failure_blocks_install(self):
        adb=self.scenario(identity_error=True)
        with self.assertRaisesRegex(RuntimeError,'wrong installed signer'):upgrade.main()
        adb.assert_not_called()
    def test_nonincreasing_version_blocks_install(self):
        for n in [214,215]:
            with self.subTest(n=n):
                adb=self.scenario(incoming=n)
                with self.assertRaisesRegex(RuntimeError,'Not an upgrade'):upgrade.main()
                adb.assert_not_called()
    def test_preexisting_file_change_blocks_install(self):
        adb=self.scenario(snapshots=[{}])
        with self.assertRaisesRegex(RuntimeError,'BEFORE upgrade'):upgrade.main()
        adb.assert_not_called()
    def test_post_upgrade_file_loss_is_failure(self):
        adb=self.scenario(snapshots=[FILES,{}])
        with self.assertRaisesRegex(RuntimeError,'offline files changed'):upgrade.main()
        self.assertEqual('install',adb.call_args.args[2])
    def test_mocked_upgrade_uses_only_install_r(self):
        adb=self.scenario();upgrade.main()
        self.assertEqual(1,adb.call_count)
        self.assertEqual(('-s','emulator-5554','install','-r'),adb.call_args.args[:4])

if __name__=='__main__':unittest.main()

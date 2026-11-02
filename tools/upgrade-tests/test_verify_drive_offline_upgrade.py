"""Unit checks for the safe-failure guards; do not require an Android device."""
import importlib.util
import pathlib
import tempfile
import unittest
from unittest.mock import patch

MODULE = pathlib.Path(__file__).with_name("verify_drive_offline_upgrade.py")
spec = importlib.util.spec_from_file_location("drive_upgrade", MODULE)
upgrade = importlib.util.module_from_spec(spec)
spec.loader.exec_module(upgrade)


class UpgradeSafetyTest(unittest.TestCase):
    def test_refuses_physical_device(self):
        with patch.object(upgrade, "cmd", return_value="List of devices attached\nPHONE\tdevice\n"), \
             patch.object(upgrade, "adb", return_value="0"):
            with self.assertRaisesRegex(RuntimeError, "physical phone"):
                upgrade.check_emulator()

    def test_refuses_multiple_devices(self):
        with patch.object(upgrade, "cmd", return_value="List of devices attached\nemu1\tdevice\nemu2\tdevice\n"):
            with self.assertRaisesRegex(RuntimeError, "Exactly one"):
                upgrade.check_emulator()

    def test_accepts_single_emulator(self):
        with patch.object(upgrade, "cmd", return_value="List of devices attached\nemulator-5554\tdevice\n"), \
             patch.object(upgrade, "adb", return_value="1"):
            self.assertEqual("emulator-5554", upgrade.check_emulator())

    def test_missing_download_fails_closed(self):
        with patch.object(upgrade, "adb", return_value=""):
            with self.assertRaisesRegex(RuntimeError, "No Drive offline audio"):
                upgrade.snapshot("emulator-5554")

    def test_reads_installed_version(self):
        with patch.object(upgrade, "adb", return_value="Package [test] versionCode=215 minSdk=23"):
            self.assertEqual(215, upgrade.installed_version("emulator-5554"))

    def test_missing_version_fails_closed(self):
        with patch.object(upgrade, "adb", return_value="Unable to find package"):
            with self.assertRaisesRegex(RuntimeError, "Could not verify"):
                upgrade.installed_version("emulator-5554")

    def test_signature_and_package_checked(self):
        with tempfile.TemporaryDirectory() as d:
            apk = pathlib.Path(d) / "test.apk"
            apk.write_bytes(b"dummy")
            details = "package: name='com.palmerintech.firetube.drivefavoritescounterspreview' versionCode='217'"
            cert = "Signer #1 certificate SHA-1 digest: A4:1B:36:91:38:EB:A1:D5:0E:23:BF:11:09:55:BD:38:6B:EB:2A:68"
            with patch.object(upgrade.shutil, "which", return_value="/tool"), \
                 patch.object(upgrade, "cmd", side_effect=[details, cert]):
                self.assertEqual(217, upgrade.inspect_apk(apk))

    def test_other_signature_is_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            apk = pathlib.Path(d) / "test.apk"
            apk.write_bytes(b"dummy")
            details = "package: name='com.palmerintech.firetube.drivefavoritescounterspreview' versionCode='217'"
            cert = "Signer #1 certificate SHA-1 digest: 00:11:22:33"
            with patch.object(upgrade.shutil, "which", return_value="/tool"), \
                 patch.object(upgrade, "cmd", side_effect=[details, cert]):
                with self.assertRaisesRegex(RuntimeError, "signature does not match"):
                    upgrade.inspect_apk(apk)


if __name__ == "__main__":
    unittest.main()

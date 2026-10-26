"""SDK-independent regression check for the app's no-backup boundary.

Run from the checkout: python3 app/checks/check_backup_policy.py
"""

import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

MAIN = Path(__file__).resolve().parents[1] / "src" / "main"
ANDROID = "{http://schemas.android.com/apk/res/android}"
DOMAINS = {
    "root", "file", "database", "sharedpref", "external",
    "device_root", "device_file", "device_database", "device_sharedpref",
}


class BackupPolicyTest(unittest.TestCase):
    def test_background_network_and_private_connections_manifest_contract(self):
        manifest = ET.parse(MAIN / "AndroidManifest.xml").getroot()  # noqa: S314
        permissions = {entry.get(ANDROID + "name") for entry in manifest.findall("uses-permission")}
        self.assertTrue({"android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE",
                         "android.permission.POST_NOTIFICATIONS", "android.permission.RECEIVE_BOOT_COMPLETED"} <= permissions)
        application = manifest.find("application")
        if application is None:
            self.fail("Missing application manifest entry")
        self.assertEqual("false", application.get(ANDROID + "usesCleartextTraffic"))
        connections = next(entry for entry in application.findall("activity")
                           if entry.get(ANDROID + "name") == ".ConnectionsActivity")
        self.assertEqual("false", connections.get(ANDROID + "exported"))
        job = next(entry for entry in application.findall("service")
                   if entry.get(ANDROID + "name") == ".RefreshJob")
        self.assertEqual("android.permission.BIND_JOB_SERVICE", job.get(ANDROID + "permission"))

    def test_legacy_and_modern_transfer_modes_exclude_all_app_data(self):
        # Only checked-in XML is parsed here, never untrusted provider input.
        manifest = ET.parse(MAIN / "AndroidManifest.xml")  # noqa: S314
        application = manifest.getroot().find("application")
        if application is None:
            self.fail("Missing application manifest entry")
        self.assertEqual("false", application.get(ANDROID + "allowBackup"))
        self.assertEqual("false", application.get(ANDROID + "fullBackupContent"))
        rules = application.get(ANDROID + "dataExtractionRules")
        if rules is None:
            self.fail("Android 12+ needs explicit device-transfer exclusions")
        self.assertTrue(rules.startswith("@xml/"))
        policy = ET.parse(MAIN / "res" / "xml" / (rules[5:] + ".xml")).getroot()  # noqa: S314
        for mode in ("cloud-backup", "device-transfer"):
            with self.subTest(mode=mode):
                section = policy.find(mode)
                if section is None:
                    self.fail("Missing backup mode: " + mode)
                self.assertEqual([], section.findall("include"))
                excluded = {entry.get("domain") for entry in section.findall("exclude")
                            if entry.get("path") == "."}
                self.assertEqual(DOMAINS, excluded)


if __name__ == "__main__":
    unittest.main()

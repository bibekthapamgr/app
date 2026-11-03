"""Tests of scripts/check_release_manifest.py on synthetic merged manifests and resource files, so that a rule that can
never fail shows here first: permissions, backups, cleartext and trust (M8), the VPN service and exported components.

Run: python3 -m unittest discover -s scripts/tests -v
"""

from __future__ import annotations

import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import check_release_manifest  # noqa: E402

A = 'xmlns:android="http://schemas.android.com/apk/res/android"'
DOMAINS = "".join(f'<exclude domain="{d}" path="." />' for d in sorted(check_release_manifest.BACKUP_DOMAINS))
RULES = {
    "data_extraction_rules.xml": f"<data-extraction-rules><cloud-backup>{DOMAINS}</cloud-backup><device-transfer>{DOMAINS}</device-transfer></data-extraction-rules>",
    "backup_rules.xml": f"<full-backup-content>{DOMAINS}</full-backup-content>",
}
NSC = """<network-security-config>
    <!-- a comment is not an element -->
    <base-config cleartextTrafficPermitted="false"><trust-anchors><certificates src="system" /></trust-anchors></base-config>
</network-security-config>"""


def manifest(application_extra: str = "", components: str = "", application_attributes: str | None = None) -> str:
    attributes = application_attributes if application_attributes is not None else (
        'android:allowBackup="false" android:dataExtractionRules="@xml/data_extraction_rules" '
        'android:fullBackupContent="@xml/backup_rules" android:networkSecurityConfig="@xml/network_security_config"'
    )
    return f"""<manifest {A} package="app.proxyflow.android">
  <uses-permission android:name="android.permission.INTERNET" />
  <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
  <application {attributes} {application_extra}>
    <activity android:name="app.proxyflow.MainActivity" android:exported="true">
      <intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter>
    </activity>
    <service android:name="{check_release_manifest.VPN_SERVICE}" android:exported="false"
        android:permission="android.permission.BIND_VPN_SERVICE" android:foregroundServiceType="systemExempted">
      <intent-filter><action android:name="android.net.VpnService" /></intent-filter>
    </service>
    {components}
  </application>
</manifest>"""


class ReleaseManifestTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.res = os.path.join(self.tmp.name, "xml")
        os.makedirs(self.res)
        for name, text in {**RULES, "network_security_config.xml": NSC}.items():
            self.write(name, text)
        previous = check_release_manifest.RES_XML
        check_release_manifest.RES_XML = self.res
        self.addCleanup(setattr, check_release_manifest, "RES_XML", previous)
        self.release = os.path.join(self.tmp.name, "release-res")
        previous_release = check_release_manifest.RELEASE_RES
        check_release_manifest.RELEASE_RES = [self.release]
        self.addCleanup(setattr, check_release_manifest, "RELEASE_RES", previous_release)

    def write(self, name: str, text: str) -> None:
        with open(os.path.join(self.res, name), "w", encoding="utf-8") as f:
            f.write(text)

    def problems(self, text: str) -> list[str]:
        path = os.path.join(self.tmp.name, "AndroidManifest.xml")
        with open(path, "w", encoding="utf-8") as f:
            f.write(text)
        return check_release_manifest.check(path)[0]

    def test_the_app_as_built_passes(self):
        self.assertEqual([], self.problems(manifest()))

    def test_cleartext_or_user_certificates_fail(self):
        for config, why in (
            (NSC.replace('cleartextTrafficPermitted="false"', 'cleartextTrafficPermitted="true"'), "cleartext"),
            (NSC.replace('cleartextTrafficPermitted="false"', ""), "cleartext left to the default"),
            (NSC.replace('<certificates src="system" />', '<certificates src="system" /><certificates src="user" />'), "user CAs"),
            (NSC.replace('<certificates src="system" />', '<certificates src="@raw/extra_ca" />'), "a bundled CA"),
            (NSC.replace('src="system"', 'src="system" overridePins="true"'), "pins overridden"),
            (NSC.replace("</network-security-config>", '<domain-config cleartextTrafficPermitted="true"><domain>x.example</domain></domain-config></network-security-config>'), "a domain exception"),
            (NSC.replace("</network-security-config>", "<debug-overrides><trust-anchors><certificates src=\"user\" /></trust-anchors></debug-overrides></network-security-config>"), "debug overrides"),
        ):
            with self.subTest(why):
                self.write("network_security_config.xml", config)
                self.assertTrue(self.problems(manifest()), f"{why} must fail")

    def test_a_variant_of_the_network_security_config_is_checked_too(self):
        # Android takes xml-v31/… on Android 12+, and a release build type's own resources over main's.
        loose = NSC.replace('cleartextTrafficPermitted="false"', 'cleartextTrafficPermitted="true"')
        for directory in (os.path.join(self.tmp.name, "xml-v31"), os.path.join(self.release, "xml")):
            with self.subTest(directory):
                os.makedirs(directory)
                with open(os.path.join(directory, "network_security_config.xml"), "w", encoding="utf-8") as f:
                    f.write(loose)
                problems = self.problems(manifest())
                self.assertTrue(any(directory in p and "cleartext" in p for p in problems), problems)
                with open(os.path.join(directory, "network_security_config.xml"), "w", encoding="utf-8") as f:
                    f.write(NSC)
                self.assertEqual([], self.problems(manifest()))

    def test_a_missing_network_security_config_fails(self):
        attributes = 'android:allowBackup="false" android:dataExtractionRules="@xml/data_extraction_rules" android:fullBackupContent="@xml/backup_rules"'
        self.assertTrue(any("networkSecurityConfig" in p for p in self.problems(manifest(application_attributes=attributes))))
        os.remove(os.path.join(self.res, "network_security_config.xml"))
        self.assertTrue(any("cannot read" in p for p in self.problems(manifest())))

    def test_cleartext_debuggable_and_exports_fail(self):
        self.assertTrue(self.problems(manifest('android:usesCleartextTraffic="true"')))
        self.assertTrue(self.problems(manifest('android:debuggable="true"')))
        exported = '<receiver android:name="x.Receiver" android:exported="true"><intent-filter><action android:name="x.A" /></intent-filter></receiver>'
        self.assertTrue(any("exported without a permission" in p for p in self.problems(manifest(components=exported))))
        guarded = '<receiver android:name="x.Receiver" android:exported="true" android:permission="android.permission.DUMP" />'
        self.assertEqual([], self.problems(manifest(components=guarded)))

    def test_another_permission_fails(self):
        text = manifest().replace("<application", '<uses-permission android:name="android.permission.READ_CONTACTS" />\n  <application')
        self.assertTrue(any("READ_CONTACTS" in p for p in self.problems(text)))


if __name__ == "__main__":
    unittest.main()

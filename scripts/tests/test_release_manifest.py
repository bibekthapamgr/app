"""Tests of scripts/check_release_manifest.py on synthetic merged manifests, resource files and aapt2 output, so that a
rule that can never fail shows here first: permissions, backups, cleartext and trust (M8), the VPN service, exported
components, and what the release APK ships for the manifest's @xml files (M8).

Run: python3 -m unittest discover -s scripts/tests -v
"""

from __future__ import annotations

import os
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

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

    def test_the_manifests_xml_files_are_the_ones_read_back_from_the_apk(self):
        path = os.path.join(self.tmp.name, "AndroidManifest.xml")
        with open(path, "w", encoding="utf-8") as f:
            f.write(manifest())
        self.assertEqual(REFERENCES, check_release_manifest.xml_references(path))


# `aapt2 dump resources` of a release APK as AGP builds it: a path may be shortened (res/Zq.xml); what follows
# "Flag disabled values:" never applies.
DUMP = """Binary APK
Package name=app.proxyflow.android id=7f
  type string id=12 entryCount=1
    resource 0x7f120000 string/app_name
      () "ProxyFlow"
  type xml id=14 entryCount=3
    resource 0x7f140000 xml/backup_rules
      () (file) res/xml/backup_rules.xml type=XML
    resource 0x7f140001 xml/data_extraction_rules
      () (file) res/Zq.xml type=XML
    resource 0x7f140002 xml/network_security_config
      () (file) res/xml/network_security_config.xml type=XML
      Flag disabled values:
        (v31) (file) res/xml-v31/network_security_config.xml type=XML
"""
REFERENCES = [
    ("networkSecurityConfig", "network_security_config"),
    ("dataExtractionRules", "data_extraction_rules"),
    ("fullBackupContent", "backup_rules"),
]
SHIPPED = {
    "res/xml/backup_rules.xml": RULES["backup_rules.xml"],
    "res/Zq.xml": RULES["data_extraction_rules.xml"],
    "res/xml/network_security_config.xml": NSC,
}
LOOSE_NSC = NSC.replace('cleartextTrafficPermitted="false"', 'cleartextTrafficPermitted="true"')


def xmltree(text: str) -> str:
    """What `aapt2 dump xmltree` prints for a file (tools/aapt2/Debug.cpp, XmlPrinter): two spaces per level, an
    element's attributes one level in, its children two, each string attribute with its raw value."""
    lines: list[str] = []

    def visit(element, level: int) -> None:
        lines.append(f"{'  ' * level}E: {element.tag} (line={len(lines) + 1})")
        for name, value in element.attrib.items():
            lines.append(f'{"  " * (level + 1)}A: {name}="{value}" (Raw: "{value}")')
        for child in element:
            visit(child, level + 2)

    visit(ET.fromstring(text), 0)
    return "\n".join(lines) + "\n"


class PackagedResourcesTest(unittest.TestCase):
    """The release APK's own files for the manifest's @xml references, read back with aapt2 (a stand-in here that
    prints what aapt2 prints) and checked as the sources are."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.apk = os.path.join(self.tmp.name, "app-release-unsigned.apk")

    def aapt2(self, dump: str = DUMP, shipped: dict[str, str | None] | None = None, fails: bool = False) -> str:
        """A stand-in for aapt2: `dump resources` prints [dump], `dump xmltree --file <path>` that file's tree (nothing
        for None, or the file not shipped: cat fails, as aapt2 does)."""
        directory = tempfile.mkdtemp(dir=self.tmp.name)  # each stand-in its own files
        with open(os.path.join(directory, "resources"), "w", encoding="utf-8") as f:
            f.write(dump)
        for path, text in (SHIPPED if shipped is None else shipped).items():
            with open(os.path.join(directory, path.replace("/", "_")), "w", encoding="utf-8") as f:
                f.write("" if text is None else xmltree(text))
        tool = os.path.join(directory, "aapt2")
        with open(tool, "w", encoding="utf-8") as f:
            if fails:
                f.write('#!/bin/sh\necho "not an APK" >&2\nexit 1\n')
            else:
                f.write(f'#!/bin/sh\ncase "$2" in\n  resources) cat "{directory}/resources" ;;\n'
                        f'  xmltree) cat "{directory}/$(echo "$4" | tr / _)" ;;\nesac\n')
        os.chmod(tool, 0o755)
        return tool

    def check(self, **stand_in) -> tuple[list[str], list[str]]:
        return check_release_manifest.check_apk(REFERENCES, self.apk, self.aapt2(**stand_in))

    def test_the_apps_own_files_pass_as_read_back(self):
        problems, facts = self.check()
        self.assertEqual([], problems)
        self.assertIn("each file read back and checked", facts[0])
        self.assertIn("@xml/network_security_config ['default']", facts[0])
        self.assertEqual([("", "res/Zq.xml")], check_release_manifest.packaged_files(DUMP)["xml/data_extraction_rules"])

    def test_a_variant_that_loosens_a_file_fails(self):
        # A library's xml-v31/<name>.xml ships beside the app's file, and Android 12+ would take it.
        nsc = "xml/network_security_config\n"
        dump = DUMP.replace(nsc, f"{nsc}      (v31) (file) res/xml-v31/network_security_config.xml type=XML\n")
        problems, _ = self.check(dump=dump, shipped={**SHIPPED, "res/xml-v31/network_security_config.xml": LOOSE_NSC})
        self.assertTrue(any("xml-v31" in p and "v31" in p and "cleartext" in p for p in problems), problems)
        # The same variant as strict as the app's own passes: it is checked, not counted.
        problems, _ = self.check(dump=dump, shipped={**SHIPPED, "res/xml-v31/network_security_config.xml": NSC})
        self.assertEqual([], problems)

    def test_a_release_build_types_copy_is_checked_as_it_ships(self):
        # src/release/res/xml/data_extraction_rules.xml replaces main's in the default configuration.
        transfers = RULES["data_extraction_rules.xml"].replace('<exclude domain="database" path="." />', "", 1)
        problems, _ = self.check(shipped={**SHIPPED, "res/Zq.xml": transfers})
        self.assertTrue(any("res/Zq.xml" in p and "database" in p for p in problems), problems)
        rules = RULES["backup_rules.xml"].replace("</full-backup-content>", '<include domain="file" path="." /></full-backup-content>')
        problems, _ = self.check(shipped={**SHIPPED, "res/xml/backup_rules.xml": rules})
        self.assertTrue(any("must not include anything" in p for p in problems), problems)

    def test_a_missing_default_or_file_fails(self):
        variant_only = DUMP.replace("() (file) res/xml/network_security_config.xml", "(v31) (file) res/xml-v31/network_security_config.xml")
        problems, _ = self.check(dump=variant_only, shipped={**SHIPPED, "res/xml-v31/network_security_config.xml": NSC})
        self.assertTrue(any("not the default" in p for p in problems), problems)
        problems, _ = self.check(dump=DUMP.replace("xml/backup_rules", "xml/other_rules"))
        self.assertTrue(any("no file for @xml/backup_rules" in p for p in problems), problems)
        self.assertEqual(3, len(self.check(dump="")[0]))

    def test_a_tree_aapt2_cant_print_or_aapt2_failing_fails(self):
        problems, _ = self.check(shipped={**SHIPPED, "res/Zq.xml": None})
        self.assertTrue(any("res/Zq.xml" in p and "no element tree" in p for p in problems), problems)
        missing = dict(SHIPPED)
        del missing["res/xml/backup_rules.xml"]
        problems, _ = self.check(shipped=missing)
        self.assertTrue(any("xmltree --file res/xml/backup_rules.xml" in p and "exited 1" in p for p in problems), problems)
        problems, _ = self.check(fails=True)
        self.assertTrue(any("not an APK" in p for p in problems), problems)

    def test_aapt2s_ways_of_printing_an_attribute_are_read(self):
        read = check_release_manifest.xml_attribute
        self.assertEqual(("cleartextTrafficPermitted", "false"), read('cleartextTrafficPermitted="false" (Raw: "false")'))
        self.assertEqual(("cleartextTrafficPermitted", "false"), read("cleartextTrafficPermitted=false"))
        self.assertEqual(("src", "@raw/extra_ca"), read("src=@raw/extra_ca"))
        self.assertEqual(("name", "x"), read('http://schemas.android.com/apk/res/android:name(0x01010003)="x" (Raw: "x")'))

    def test_a_tree_with_namespaces_and_text_is_read_whole(self):
        tree = (
            "N: android=http://schemas.android.com/apk/res/android (line=2)\n"
            "  E: network-security-config (line=2)\n"
            "      E: base-config (line=3)\n"
            '        A: cleartextTrafficPermitted="true" (Raw: "true")\n'
            "          E: trust-anchors (line=4)\n"
            "              E: certificates (line=5)\n"
            '                A: src="user" (Raw: "user")\n'
            "      E: domain-config (line=7)\n"
            "          E: domain (line=8)\n"
            "              T: 'x.example'\n"
        )
        root = check_release_manifest.xmltree_root(tree)
        self.assertEqual("network-security-config", root.tag)
        self.assertEqual(["base-config", "domain-config"], [child.tag for child in root])
        self.assertEqual("user", root.find("base-config/trust-anchors/certificates").get("src"))
        problems = check_release_manifest.check_network_security_root(root, "res/xml/network_security_config.xml")
        for why in ("cleartextTrafficPermitted", "domain-config", "system's certificates alone"):
            self.assertTrue(any(why in p for p in problems), (why, problems))
        self.assertIsNone(check_release_manifest.xmltree_root(""))
        self.assertIsNone(check_release_manifest.xmltree_root("E: a (line=1)\nE: b (line=2)\n"))


if __name__ == "__main__":
    unittest.main()

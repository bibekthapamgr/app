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
    <service android:name="{check_release_manifest.TILE_SERVICE}" android:exported="true"
        android:permission="android.permission.BIND_QUICK_SETTINGS_TILE">
      <intent-filter><action android:name="android.service.quicksettings.action.QS_TILE" /></intent-filter>
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

    def test_a_tile_any_app_could_bind_fails(self):
        open_tile = manifest().replace('android:permission="android.permission.BIND_QUICK_SETTINGS_TILE"', "")
        self.assertTrue(any("BIND_QUICK_SETTINGS_TILE" in p for p in self.problems(open_tile)))
        self.assertTrue(any("is exported without a permission" in p for p in self.problems(open_tile)))
        other_action = manifest().replace("android.service.quicksettings.action.QS_TILE", "app.proxyflow.CONNECT")
        self.assertTrue(any("QS_TILE only" in p for p in self.problems(other_action)))

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


def printed(value: str) -> str:
    """An attribute's value as aapt2 prints it read back from an APK: a boolean compiled (`false`), a reference as its
    id (`@0x7f…`), a string with its raw value."""
    if value in ("true", "false"):
        return value
    if value.startswith("@"):
        return "@0x7f120000"
    return f'"{value}" (Raw: "{value}")'


def xmltree(text: str) -> str:
    """What `aapt2 dump xmltree` prints for a file (tools/aapt2/Debug.cpp, XmlPrinter): two spaces per level, an
    element's attributes one level in, its children two."""
    lines: list[str] = []

    def visit(element, level: int) -> None:
        lines.append(f"{'  ' * level}E: {element.tag} (line={len(lines) + 1})")
        for name, value in element.attrib.items():
            lines.append(f"{'  ' * (level + 1)}A: {name}={printed(value)}")
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
        self.assertIn("each file read back strictly and checked", facts[0])
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
        self.assertTrue(any("res/Zq.xml" in p and "read strictly" in p for p in problems), problems)
        missing = dict(SHIPPED)
        del missing["res/xml/backup_rules.xml"]
        problems, _ = self.check(shipped=missing)
        self.assertTrue(any("xmltree --file res/xml/backup_rules.xml" in p and "exited 1" in p for p in problems), problems)
        problems, _ = self.check(fails=True)
        self.assertTrue(any("not an APK" in p for p in problems), problems)

    def test_aapt2s_ways_of_printing_a_value_are_read(self):
        tree = (
            "E: certificates (line=1)\n"
            '  A: src="system" (Raw: "system")\n'
            "  A: overridePins=false\n"
            '  A: enabled=true (Raw: "true")\n'
            "  A: digest=@0x7f120000\n"
        )
        root = check_release_manifest.xmltree_root(tree)
        self.assertEqual({"src": "system", "overridePins": "false", "enabled": "true", "digest": "@0x7f120000"}, root.attrib)
        # A string whose printed and raw values differ is not one aapt2 makes from a file: not read.
        self.assertIsNone(check_release_manifest.xmltree_root('E: certificates (line=1)\n  A: src="system" (Raw: "user")\n'))

    def test_a_tree_in_any_other_form_is_not_read(self):
        # These files never hold a namespace, text or another kind of name or value, and aapt2 prints names, values and
        # text unescaped: anything else reads as no tree, and the check fails, rather than being read some other way.
        nsc = xmltree(NSC)
        lines = nsc.split("\n")
        others = {
            "a namespace declared": "N: x=urn:x (line=1)\n" + "\n".join("  " + line if line else line for line in lines),
            "a namespaced element": nsc + "    E: urn:x:domain-config (line=9)\n",
            "a namespaced attribute": nsc + '              A: urn:x:src="user" (Raw: "user")\n',
            "text": nsc + "            T: 'x'\n",
            "an attribute after a child": nsc + '      A: cleartextTrafficPermitted="true" (Raw: "true")\n',
            "a repeated attribute": nsc + '              A: src="user" (Raw: "user")\n',
            "a child three levels in": nsc + "      E: domain-config (line=9)\n",
            "a second top element": nsc + "E: network-security-config (line=9)\n",
            "a root not at the left": "  " + nsc,
            "a value over two lines": nsc + '              A: note="a\n  E: exclude (line=20)\n" (Raw: "a")\n',
            "a line separator in a line": nsc.replace("E: certificates", "E: certificates (line=4)\u2028E: certificates"),
            "a carriage return": nsc.replace("E: base-config", "E: base-config (line=2)\rE: base-config"),
            "a tab for spaces": nsc.replace("    E: base-config", "\t\tE: base-config"),
        }
        for what, tree in others.items():
            with self.subTest(what):
                self.assertIsNone(check_release_manifest.xmltree_root(tree), tree)
        self.assertIsNone(check_release_manifest.xmltree_root(""))
        self.assertIsNotNone(check_release_manifest.xmltree_root(nsc))

    def test_a_tree_not_read_fails_the_check(self):
        # A library's variant that loosens the file in a way the strict reading refuses still fails: as no tree.
        nsc = "xml/network_security_config\n"
        dump = DUMP.replace(nsc, f"{nsc}      (v31) (file) res/xml-v31/network_security_config.xml type=XML\n")
        directory = tempfile.mkdtemp(dir=self.tmp.name)
        for path, text in SHIPPED.items():
            with open(os.path.join(directory, path.replace("/", "_")), "w", encoding="utf-8") as f:
                f.write(xmltree(text))
        with open(os.path.join(directory, "res_xml-v31_network_security_config.xml"), "w", encoding="utf-8") as f:
            f.write(xmltree(NSC) + "            N: x=urn:x (line=5)\n              E: certificates (line=5)\n"
                    '                A: src="user" (Raw: "user")\n')
        with open(os.path.join(directory, "resources"), "w", encoding="utf-8") as f:
            f.write(dump)
        tool = os.path.join(directory, "aapt2")
        with open(tool, "w", encoding="utf-8") as f:
            f.write(f'#!/bin/sh\ncase "$2" in\n  resources) cat "{directory}/resources" ;;\n'
                    f'  xmltree) cat "{directory}/$(echo "$4" | tr / _)" ;;\nesac\n')
        os.chmod(tool, 0o755)
        problems, _ = check_release_manifest.check_apk(REFERENCES, self.apk, tool)
        self.assertTrue(any("xml-v31" in p and "read strictly" in p for p in problems), problems)


def uleb(value: int) -> bytes:
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        out.append(byte | (0x80 if value else 0))
        if not value:
            return bytes(out)


def dex(classes: list[str], methods: list[tuple[str, str]] = (), fields: list[tuple[str, str]] = (), extra: list[str] = ()) -> bytes:
    """A minimal dex file: the header's string, type, field, method and class tables — the classes [classes] defines, and
    [methods] and [fields] as (class, name) ids — and nothing else (prototypes and class data are left out)."""
    types = sorted({c for c in classes} | {c for c, _ in methods} | {c for c, _ in fields})
    strings = sorted(set(types) | {n for _, n in methods} | {n for _, n in fields} | set(extra))
    index = {text: i for i, text in enumerate(strings)}
    type_index = {t: i for i, t in enumerate(types)}
    header = bytearray(0x70)
    header[:8] = b"dex\n035\0"
    tables = bytearray()
    at = 0x70

    def table(count_at: int, rows: list[bytes]) -> None:
        nonlocal at
        header[count_at : count_at + 4] = len(rows).to_bytes(4, "little")
        header[count_at + 4 : count_at + 8] = at.to_bytes(4, "little")
        for row in rows:
            tables.extend(row)
        at += sum(len(row) for row in rows)

    string_ids_at = at
    table(56, [b"\0\0\0\0"] * len(strings))  # filled below, once the data's place is known
    table(64, [index[t].to_bytes(4, "little") for t in types])
    table(80, [type_index[c].to_bytes(2, "little") + b"\0\0" + index[n].to_bytes(4, "little") for c, n in fields])
    table(88, [type_index[c].to_bytes(2, "little") + b"\0\0" + index[n].to_bytes(4, "little") for c, n in methods])
    table(96, [type_index[c].to_bytes(4, "little") + bytes(28) for c in classes])
    data = bytearray()
    for i, text in enumerate(strings):
        offset = at + len(data)
        tables[string_ids_at - 0x70 + 4 * i : string_ids_at - 0x70 + 4 * i + 4] = offset.to_bytes(4, "little")
        data += uleb(len(text)) + text.encode("utf-8") + b"\0"
    return bytes(header + tables + data)


def engine_dex(leave_out: str = "") -> bytes:
    """What the engine's JNI layer needs, as R8 keeps it, but for [leave_out] (a class, "Class.method" or "Class.field")."""
    classes = [c for c in check_release_manifest.ENGINE_CLASSES if c != leave_out]
    methods = [(c, m) for c, names in check_release_manifest.ENGINE_METHODS.items() for m in names if f"{c}.{m}" != leave_out]
    fields = [(c, f) for c, names in check_release_manifest.ENGINE_FIELDS.items() for f in names if f"{c}.{f}" != leave_out]
    return dex(classes, methods, fields)


class EngineNamesTest(unittest.TestCase):
    """M10: the release APK's dex files must still define what the engine's JNI layer finds by name, after R8."""

    def apk(self, dexes: dict[str, bytes]) -> str:
        import zipfile

        path = os.path.join(self.enterContext(tempfile.TemporaryDirectory()), "app-release.apk")
        with zipfile.ZipFile(path, "w") as archive:
            for name, data in dexes.items():
                archive.writestr(name, data)
            archive.writestr("AndroidManifest.xml", b"binary")
        return path

    def test_everything_kept_passes_across_dex_files(self):
        other = dex(["La/b;"], [("La/b;", "a")], extra=["x" * 200])  # a class R8 renamed; a two-byte ULEB128 length
        problems, facts = check_release_manifest.check_engine_names(self.apk({"classes.dex": other, "classes2.dex": engine_dex()}))
        self.assertEqual([], problems)
        self.assertIn("(2)", facts[0])
        classes, methods, fields = check_release_manifest.dex_tables(engine_dex())
        self.assertIn("Lgo/Seq$Ref;", classes)
        self.assertIn(("Lgo/Seq;", "getRef"), methods)
        self.assertEqual({("Lgo/Seq$Ref;", "obj")}, fields)

    def test_a_renamed_class_method_or_field_fails(self):
        for leave_out in ["Lgo/Seq;", "Lgo/Seq;.incGoObjectRef", "Lapp/proxyflow/engine/go/pfengine/Platform;.verifyChain", "Lgo/Seq$Ref;.obj"]:
            problems, _ = check_release_manifest.check_engine_names(self.apk({"classes.dex": engine_dex(leave_out)}))
            self.assertEqual(1, len(problems), leave_out)
            name = leave_out if leave_out.endswith(";") else leave_out.split(";.")[1]  # a class, or a member's name
            self.assertIn(name, problems[0])

    def test_a_name_only_in_the_string_table_is_not_enough(self):
        # "protect" is in every dex that calls VpnService.protect: a name used anywhere is not the method defined on the class.
        crippled = dex([c for c in check_release_manifest.ENGINE_CLASSES], [], [], extra=["protect", "getRef", "obj", "start"])
        problems, _ = check_release_manifest.check_engine_names(self.apk({"classes.dex": crippled}))
        self.assertIn("Lgo/Seq;.getRef()", problems[0])

    def test_no_dex_fails(self):
        problems, _ = check_release_manifest.check_engine_names(self.apk({}))
        self.assertIn("no dex file", problems[0])


if __name__ == "__main__":
    unittest.main()

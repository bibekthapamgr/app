"""Generate the throw-away test PKI used by the feasibility spike.

Creates (in the output directory):
  ca.pem / ca.key                 "ProxyFlow Spike Test CA" (trusted by the engine in tests)
  proxy.pem / proxy.key           proxy.test (+ IP 10.20.0.2), signed by the test CA
  proxy-expired.pem / .key        same names, validity entirely in the past
  untrusted-ca.pem, proxy-untrusted.pem / .key   chain from a CA the engine does NOT trust
  web.pem / web.key               web.test (HTTPS target behind the proxy)

These keys exist only for the spike. Never reuse them.
"""
from __future__ import annotations

import datetime as dt
import ipaddress
import sys
from pathlib import Path

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import ExtendedKeyUsageOID, NameOID


def _key():
    return ec.generate_private_key(ec.SECP256R1())


def _write(out: Path, name: str, cert: x509.Certificate, key) -> None:
    (out / f"{name}.pem").write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    (out / f"{name}.key").write_bytes(
        key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption())
    )
    (out / f"{name}.key").chmod(0o600)


def make_ca(cn: str):
    key = _key()
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, cn)])
    now = dt.datetime.now(dt.timezone.utc)
    cert = (
        x509.CertificateBuilder()
        .subject_name(name).issuer_name(name).public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - dt.timedelta(days=1)).not_valid_after(now + dt.timedelta(days=30))
        .add_extension(x509.BasicConstraints(ca=True, path_length=0), critical=True)
        .add_extension(x509.KeyUsage(False, False, False, False, False, True, True, False, False), critical=True)
        .sign(key, hashes.SHA256())
    )
    return cert, key


def make_leaf(ca_cert, ca_key, dns: list[str], ips: list[str], not_before, not_after):
    key = _key()
    sans = [x509.DNSName(d) for d in dns] + [x509.IPAddress(ipaddress.ip_address(i)) for i in ips]
    cert = (
        x509.CertificateBuilder()
        .subject_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, dns[0])]))
        .issuer_name(ca_cert.subject).public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(not_before).not_valid_after(not_after)
        .add_extension(x509.SubjectAlternativeName(sans), critical=False)
        .add_extension(x509.ExtendedKeyUsage([ExtendedKeyUsageOID.SERVER_AUTH]), critical=False)
        .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
        .sign(ca_key, hashes.SHA256())
    )
    return cert, key


def main(out_dir: str) -> None:
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    now = dt.datetime.now(dt.timezone.utc)
    ca, ca_key = make_ca("ProxyFlow Spike Test CA")
    _write(out, "ca", ca, ca_key)
    _write(out, "proxy", *make_leaf(ca, ca_key, ["proxy.test"], ["10.20.0.2", "fd00:20::2"],
                                    now - dt.timedelta(hours=1), now + dt.timedelta(days=7)))
    _write(out, "proxy-expired", *make_leaf(ca, ca_key, ["proxy.test"], ["10.20.0.2"],
                                            now - dt.timedelta(days=20), now - dt.timedelta(days=10)))
    _write(out, "web", *make_leaf(ca, ca_key, ["web.test"], ["10.30.0.2"],
                                  now - dt.timedelta(hours=1), now + dt.timedelta(days=7)))
    bad_ca, bad_key = make_ca("Some Other CA (untrusted)")
    _write(out, "untrusted-ca", bad_ca, bad_key)
    _write(out, "proxy-untrusted", *make_leaf(bad_ca, bad_key, ["proxy.test"], ["10.20.0.2"],
                                              now - dt.timedelta(hours=1), now + dt.timedelta(days=7)))
    # stunnel wants cert+key in one file
    for n in ("proxy", "proxy-expired", "proxy-untrusted"):
        (out / f"{n}.bundle").write_bytes((out / f"{n}.pem").read_bytes() + (out / f"{n}.key").read_bytes())
        (out / f"{n}.bundle").chmod(0o600)
    print(f"test PKI written to {out}")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "certs")

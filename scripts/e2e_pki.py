#!/usr/bin/env python3
"""The certificates of the end-to-end lab (CI runner only), made fresh for each run.

    e2e_pki.py OUT_DIR

writes into OUT_DIR, as PEM:

  ca.crt                 the run's test CA. The device tests trust it only where a test says
                         so (TestTrust): the app as shipped never does, which one test checks.
  other-ca.crt           a second CA that nothing trusts
  proxy-ok.pem           proxy.proxyflow.test, issued by the test CA, valid now
  proxy-other.pem        other.proxyflow.test, issued by the test CA (wrong name for the proxy)
  proxy-expired.pem      proxy.proxyflow.test, issued by the test CA, expired 30 days ago
  proxy-untrusted.pem    proxy.proxyflow.test, issued by the other CA
  destination.pem        secure.proxyflow.test, issued by the test CA: the HTTPS destination
  destination.sha256     the SHA-256 of the destination certificate (DER, hex), which the
                         device's TLS client must see through the tunnel

Each .pem holds the server's certificate then its private key (stunnel's and Python's
format); servers send their certificate alone, not the CA. Keys are P-256, new for each
run, readable only by the runner; nothing here is a secret beyond the run.
"""

from __future__ import annotations

import datetime
import hashlib
import os
import sys

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import ExtendedKeyUsageOID, NameOID

DAY = datetime.timedelta(days=1)


def name(common: str) -> x509.Name:
    return x509.Name([x509.NameAttribute(NameOID.ORGANIZATION_NAME, "ProxyFlow CI"), x509.NameAttribute(NameOID.COMMON_NAME, common)])


def ca(common: str, now: datetime.datetime) -> tuple[x509.Certificate, ec.EllipticCurvePrivateKey]:
    key = ec.generate_private_key(ec.SECP256R1())
    subject = name(common)
    cert = (
        x509.CertificateBuilder()
        .subject_name(subject)
        .issuer_name(subject)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - DAY)
        .not_valid_after(now + 30 * DAY)
        .add_extension(x509.BasicConstraints(ca=True, path_length=0), critical=True)
        .add_extension(
            x509.KeyUsage(
                digital_signature=False, content_commitment=False, key_encipherment=False, data_encipherment=False,
                key_agreement=False, key_cert_sign=True, crl_sign=True, encipher_only=False, decipher_only=False,
            ),
            critical=True,
        )
        .add_extension(x509.SubjectKeyIdentifier.from_public_key(key.public_key()), critical=False)
        .sign(key, hashes.SHA256())
    )
    return cert, key


def leaf(
    dns_name: str,
    issuer: tuple[x509.Certificate, ec.EllipticCurvePrivateKey],
    not_before: datetime.datetime,
    not_after: datetime.datetime,
) -> tuple[x509.Certificate, ec.EllipticCurvePrivateKey]:
    issuer_cert, issuer_key = issuer
    key = ec.generate_private_key(ec.SECP256R1())
    cert = (
        x509.CertificateBuilder()
        .subject_name(name(dns_name))
        .issuer_name(issuer_cert.subject)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(not_before)
        .not_valid_after(not_after)
        .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
        .add_extension(
            x509.KeyUsage(
                digital_signature=True, content_commitment=False, key_encipherment=False, data_encipherment=False,
                key_agreement=False, key_cert_sign=False, crl_sign=False, encipher_only=False, decipher_only=False,
            ),
            critical=True,
        )
        .add_extension(x509.ExtendedKeyUsage([ExtendedKeyUsageOID.SERVER_AUTH]), critical=False)
        .add_extension(x509.SubjectAlternativeName([x509.DNSName(dns_name)]), critical=False)
        .add_extension(x509.AuthorityKeyIdentifier.from_issuer_public_key(issuer_key.public_key()), critical=False)
        .sign(issuer_key, hashes.SHA256())
    )
    return cert, key


def pem(cert: x509.Certificate) -> bytes:
    return cert.public_bytes(serialization.Encoding.PEM)


def write(path: str, data: bytes, mode: int = 0o644) -> None:
    with open(path, "wb") as f:
        f.write(data)
    os.chmod(path, mode)


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print(__doc__, file=sys.stderr)
        return 2
    out = argv[1]
    os.makedirs(out, exist_ok=True)
    now = datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0)
    test_ca = ca("ProxyFlow CI test CA", now)
    other_ca = ca("ProxyFlow CI other CA (trusted by nothing)", now)
    write(os.path.join(out, "ca.crt"), pem(test_ca[0]))
    write(os.path.join(out, "other-ca.crt"), pem(other_ca[0]))
    valid = (now - DAY, now + 30 * DAY)
    servers = {
        "proxy-ok": leaf("proxy.proxyflow.test", test_ca, *valid),
        "proxy-other": leaf("other.proxyflow.test", test_ca, *valid),
        "proxy-expired": leaf("proxy.proxyflow.test", test_ca, now - 60 * DAY, now - 30 * DAY),
        "proxy-untrusted": leaf("proxy.proxyflow.test", other_ca, *valid),
        "destination": leaf("secure.proxyflow.test", test_ca, *valid),
    }
    for file_name, (cert, key) in servers.items():
        key_pem = key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption())
        write(os.path.join(out, f"{file_name}.pem"), pem(cert) + key_pem, 0o600)
    destination = servers["destination"][0].public_bytes(serialization.Encoding.DER)
    write(os.path.join(out, "destination.sha256"), hashlib.sha256(destination).hexdigest().encode() + b"\n")
    print(f"test PKI in {out}: CA, other CA, {', '.join(servers)}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))

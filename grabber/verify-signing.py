#!/usr/bin/env python3
"""Fail the build if the APK is not signed by the key we expect.

The APK was signed by a different, randomly generated key on every CI run for
weeks. Nothing complained: the build went green, the release published, and the
only symptom was on the phone, where Android quietly refused every upgrade and
the app had to be uninstalled — taking all of its data with it.

Signing is invisible from the outside, so it gets a check of its own.

    python3 verify-signing.py <app.apk> <keystore> <storepass>
"""
import hashlib, re, struct, subprocess, sys, tempfile, os


def apk_signing_cert(path):
    """The signer certificate from the APK Signature Scheme v2 block.

    Layout: the block sits just before the central directory and ends with the
    magic. Inside are id/value pairs; the v2 pair holds a sequence of signers,
    each holding signed data, which holds digests then certificates.
    """
    data = open(path, 'rb').read()
    eocd = data.rfind(b'PK\x05\x06')
    if eocd < 0:
        sys.exit('verify-signing: not a zip')
    cd = struct.unpack_from('<I', data, eocd + 16)[0]
    if data[cd - 16:cd] != b'APK Sig Block 42':
        sys.exit('verify-signing: no APK Signing Block — the APK is unsigned or v1 only')
    size = struct.unpack_from('<Q', data, cd - 24)[0]

    pairs, p, end = {}, cd - size - 8 + 8, cd - 24
    while p < end:
        ln  = struct.unpack_from('<Q', data, p)[0]
        pid = struct.unpack_from('<I', data, p + 8)[0]
        pairs[pid] = data[p + 12: p + 8 + ln]
        p += 8 + ln

    def seq(b):
        o = 0
        while o < len(b):
            n = struct.unpack_from('<I', b, o)[0]
            yield b[o + 4: o + 4 + n]
            o += 4 + n

    v2 = pairs.get(0x7109871a)
    if v2 is None:
        sys.exit('verify-signing: no v2 signature block')
    signer = list(seq(next(seq(v2))))[0]
    signed = list(seq(signer))[0]
    return list(seq(list(seq(signed))[1]))[0]


def sha256_of_cert_der(der):
    with tempfile.NamedTemporaryFile(suffix='.der', delete=False) as f:
        f.write(der)
        tmp = f.name
    try:
        out = subprocess.run(['keytool', '-printcert', '-file', tmp],
                             capture_output=True, text=True).stdout
    finally:
        os.unlink(tmp)
    return fingerprint(out), out


def fingerprint(text):
    m = re.search(r'SHA256:\s*([0-9A-F:]+)', text)
    return m.group(1) if m else None


def keystore_fingerprint(keystore, storepass):
    out = subprocess.run(
        ['keytool', '-list', '-v', '-keystore', keystore, '-storepass', storepass],
        capture_output=True, text=True).stdout
    return fingerprint(out)


def main():
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    apk, keystore, storepass = sys.argv[1:]

    got, printed = sha256_of_cert_der(apk_signing_cert(apk))
    want = keystore_fingerprint(keystore, storepass)

    owner = re.search(r'Owner:\s*(.+)', printed)
    valid = re.search(r'Valid from:\s*(.+)', printed)
    print('APK signer : %s' % (owner.group(1).strip() if owner else '?'))
    print('Valid from : %s' % (valid.group(1).strip() if valid else '?'))
    print('APK      SHA256 %s' % got)
    print('keystore SHA256 %s' % want)

    if not got or not want:
        sys.exit('verify-signing: could not read one of the fingerprints')
    if got != want:
        sys.exit('\nverify-signing: FAILED — this APK is signed by a different key than\n'
                 '%s. Android will refuse to install it over the copy already on the\n'
                 'phone, and the only way in would be to uninstall, which destroys all\n'
                 'the data the app holds. Not publishing this build.' % keystore)
    print('\nverify-signing: OK — same key as every previous build, so this installs as an upgrade.')


if __name__ == '__main__':
    main()

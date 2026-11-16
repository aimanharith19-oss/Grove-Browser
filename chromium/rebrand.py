#!/usr/bin/env python3
"""
Grove Browser (Chromium): turns a Titanium release (an open-source Chromium fork for Android, GPL-2.0,
https://github.com/jqssun/android-titanium-browser) into Grove Browser without recompiling Chromium:

  * package io.github.jqssun.helium -> app.grove.browser (so it installs over Grove Browser), in the binary
    AndroidManifest.xml and in resources.arsc
  * the name "Titanium" -> "Grove Browser" in every string of the app (all languages)
  * Grove icon (the launcher icon files are replaced in place)
  * Groveguard (ads, trackers, 18+, dangerous sites) bundled as a built-in extension, signed with the Grove key
  * signed with the Grove key

usage: rebrand.py IN.apk OUT-unsigned.apk ICON_DIR NAME=EXTENSION_DIR=KEY.pem [...]
Then zipalign + apksigner (see .github/workflows/chromium.yml).
"""
import io, json, hashlib, os, struct, subprocess, sys, tempfile, zipfile

OLD_PKG, NEW_PKG = "io.github.jqssun.helium", "app.grove.browser"
NAME_SWAPS = [("Titanium Browser", "Grove Browser"), ("Titanium", "Grove Browser"), ("Helium", "Grove Browser")]

# ------------------------------------------------------------------------------------------------ string pools
RES_STRING_POOL = 0x0001
UTF8_FLAG = 1 << 8


def _len8(b, i):
    n = b[i]; i += 1
    if n & 0x80: n = ((n & 0x7F) << 8) | b[i]; i += 1
    return n, i


def _len16(b, i):
    n = struct.unpack_from("<H", b, i)[0]; i += 2
    if n & 0x8000: n = ((n & 0x7FFF) << 16) | struct.unpack_from("<H", b, i)[0]; i += 2
    return n, i


def read_pool(chunk):
    """-> (strings, styles_raw, flags, header_size). Only the strings are edited; styles are kept byte for byte."""
    typ, hsize, size, count, scount, flags, sstart, stystart = struct.unpack_from("<HHIIIIII", chunk, 0)
    assert typ == RES_STRING_POOL
    offs = struct.unpack_from("<%dI" % count, chunk, hsize)
    styoffs = struct.unpack_from("<%dI" % scount, chunk, hsize + 4 * count)
    utf8 = bool(flags & UTF8_FLAG)
    out = []
    for o in offs:
        i = sstart + o
        if utf8:
            _, i = _len8(chunk, i)        # length in UTF-16 units
            n, i = _len8(chunk, i)        # length in bytes
            out.append(chunk[i:i + n].decode("utf-8", "surrogatepass"))
        else:
            n, i = _len16(chunk, i)
            out.append(chunk[i:i + 2 * n].decode("utf-16-le", "surrogatepass"))
    styles = b""
    if scount:
        styles = chunk[stystart:size]
    return out, styoffs, styles, flags, hsize


def _enc8(n):
    return bytes([n]) if n < 0x80 else bytes([0x80 | (n >> 8), n & 0xFF])


def _enc16(n):
    return struct.pack("<H", n) if n < 0x8000 else struct.pack("<HH", 0x8000 | (n >> 16), n & 0xFFFF)


def write_pool(strings, styoffs, styles, flags, hsize):
    utf8 = bool(flags & UTF8_FLAG)
    data = bytearray(); offs = []
    for s in strings:
        offs.append(len(data))
        if utf8:
            raw = s.encode("utf-8", "surrogatepass")
            u16 = len(s.encode("utf-16-le", "surrogatepass")) // 2
            if u16 > 0x7FFF or len(raw) > 0x7FFF: raise ValueError("string too long for UTF-8 pool")
            data += _enc8(u16) + _enc8(len(raw)) + raw + b"\0"
        else:
            raw = s.encode("utf-16-le", "surrogatepass")
            data += _enc16(len(raw) // 2) + raw + b"\0\0"
    while len(data) % 4: data += b"\0"
    count, scount = len(strings), len(styoffs)
    sstart = hsize + 4 * count + 4 * scount
    stystart = sstart + len(data) if scount else 0
    size = sstart + len(data) + len(styles)
    head = struct.pack("<HHIIIIII", RES_STRING_POOL, hsize, size, count, scount, flags, sstart, stystart)
    head += b"\0" * (hsize - len(head))
    return head + struct.pack("<%dI" % count, *offs) + struct.pack("<%dI" % scount, *styoffs) + bytes(data) + styles


# ------------------------------------------------------------------------------------------------ AndroidManifest.xml
RES_XML_START_ELEMENT = 0x0102
TYPE_STRING, TYPE_REFERENCE = 0x03, 0x01
ATTR_LABEL = 0x01010001


def patch_manifest(axml, label):
    typ, hsize, size = struct.unpack_from("<HHI", axml, 0)
    pool_at = hsize
    _, _, psize = struct.unpack_from("<HHI", axml, pool_at)
    strings, styoffs, styles, flags, phs = read_pool(axml[pool_at:pool_at + psize])
    changed = 0
    for k, s in enumerate(strings):
        if OLD_PKG in s:
            strings[k] = s.replace(OLD_PKG, NEW_PKG); changed += 1
    label_idx = len(strings); strings.append(label)
    rest = bytearray(axml[pool_at + psize:])
    # resource-id map comes right after the pool; attribute names are matched by resource id
    ids = []
    if struct.unpack_from("<H", rest, 0)[0] == 0x0180:
        _, ih, isz = struct.unpack_from("<HHI", rest, 0)
        ids = list(struct.unpack_from("<%dI" % ((isz - ih) // 4), rest, ih))
    # <application android:label=...> becomes the plain text label (launcher name and app info)
    i, found = 0, False
    while i < len(rest):
        ctyp, chs, csz = struct.unpack_from("<HHI", rest, i)
        if ctyp == RES_XML_START_ELEMENT:
            ns, name, attr_start, attr_size, attr_count = struct.unpack_from("<iIHHH", rest, i + chs)
            if strings[name] == "application":
                a0 = i + chs + attr_start
                for a in range(attr_count):
                    at = a0 + a * attr_size
                    _, aname, raw, vsize, _, dtype, dval = struct.unpack_from("<iIiHBBI", rest, at)
                    if aname < len(ids) and ids[aname] == ATTR_LABEL:
                        ans = struct.unpack_from("<i", rest, at)[0]
                        struct.pack_into("<iIiHBBI", rest, at, ans, aname, label_idx, 8, 0, TYPE_STRING, label_idx)
                        found = True
        i += csz
    assert found, "application label not found"
    pool = write_pool(strings, styoffs, styles, flags, phs)
    body = pool + bytes(rest)
    return struct.pack("<HHI", typ, hsize, hsize + len(body)) + axml[8:hsize] + body, changed


# ------------------------------------------------------------------------------------------------ resources.arsc
RES_TABLE_PACKAGE = 0x0200


def patch_arsc(arsc):
    typ, hsize, size, pkgcount = struct.unpack_from("<HHII", arsc, 0)
    pool_at = hsize
    _, _, psize = struct.unpack_from("<HHI", arsc, pool_at)
    strings, styoffs, styles, flags, phs = read_pool(arsc[pool_at:pool_at + psize])
    n = 0
    for k, s in enumerate(strings):
        t = s
        for a, b in NAME_SWAPS:
            if a in t and "titanium." not in t.lower() and "/" not in t: t = t.replace(a, b)
        if OLD_PKG in t: t = t.replace(OLD_PKG, NEW_PKG)
        if t != s: strings[k] = t; n += 1
    pool = write_pool(strings, styoffs, styles, flags, phs)
    rest = bytearray(arsc[pool_at + psize:])
    # package chunk name (UTF-16, 128 chars): keep it equal to the app's package for getIdentifier()
    i = 0
    while i < len(rest):
        ctyp, chs, csz = struct.unpack_from("<HHI", rest, i)
        if ctyp == RES_TABLE_PACKAGE:
            name = rest[i + 12:i + 12 + 256].decode("utf-16-le").split("\0")[0]
            if name == OLD_PKG:
                rest[i + 12:i + 12 + 256] = NEW_PKG.encode("utf-16-le").ljust(256, b"\0")
        i += csz
    body = pool + bytes(rest)
    return struct.pack("<HHII", typ, hsize, hsize + len(body), pkgcount) + arsc[12:hsize] + body, n


# ------------------------------------------------------------------------------------------------ CRX3 (Groveguard)
def _varint(n):
    out = bytearray()
    while True:
        b = n & 0x7F; n >>= 7
        out.append(b | (0x80 if n else 0))
        if not n: return bytes(out)


def _field(num, data):
    return _varint((num << 3) | 2) + _varint(len(data)) + data


def make_crx(ext_dir, key_pem):
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        for root, _, files in os.walk(ext_dir):
            for f in sorted(files):
                p = os.path.join(root, f)
                z.write(p, os.path.relpath(p, ext_dir))
    payload = buf.getvalue()
    pub = subprocess.run(["openssl", "pkey", "-in", key_pem, "-pubout", "-outform", "DER"], check=True, capture_output=True).stdout
    crx_id = hashlib.sha256(pub).digest()[:16]
    signed = _field(1, crx_id)
    msg = b"CRX3 SignedData\x00" + struct.pack("<I", len(signed)) + signed + payload
    with tempfile.NamedTemporaryFile(delete=False) as t: t.write(msg)
    sig = subprocess.run(["openssl", "dgst", "-sha256", "-sign", key_pem, t.name], check=True, capture_output=True).stdout
    os.unlink(t.name)
    header = _field(2, _field(1, pub) + _field(2, sig)) + _field(10000, signed)
    crx = b"Cr24" + struct.pack("<II", 3, len(header)) + header + payload
    ext_id = "".join(chr(97 + (b >> 4)) + chr(97 + (b & 15)) for b in crx_id)
    version = json.load(open(os.path.join(ext_dir, "manifest.json")))["version"]
    return crx, ext_id, version


# ------------------------------------------------------------------------------------------------ icons
def icon_files(apk):
    """Image files of the launcher icon, found by following the app's icon resources (and the layers of its adaptive icons)."""
    bt = sorted(os.listdir(os.path.join(os.environ["ANDROID_HOME"], "build-tools")))[-1]
    aapt2 = os.path.join(os.environ["ANDROID_HOME"], "build-tools", bt, "aapt2")
    dump = subprocess.run([aapt2, "dump", "resources", apk], check=True, capture_output=True, text=True).stdout.splitlines()
    files, names, cur = {}, {}, None          # resource id -> [paths], resource id -> name
    for line in dump:
        t = line.strip().split()
        if line.strip().startswith("resource 0x") and len(t) > 2:
            cur = int(t[1], 16); names[cur] = t[2]; files.setdefault(cur, [])
        elif cur is not None and "(file)" in line:
            files[cur].append(line.split("(file)")[1].strip().split()[0])
    by_name = {v: k for k, v in names.items()}
    out = {}

    def take(rid, kind):
        for p in files.get(rid, []):
            if p.endswith(".xml") and kind != "fg":
                layers(p)
            else:
                out[p] = kind

    def layers(path):
        """adaptive-icon: background, foreground, monochrome."""
        tree = subprocess.run([aapt2, "dump", "xmltree", "--file", path, apk], capture_output=True, text=True).stdout.splitlines()
        if not any("adaptive-icon" in l for l in tree):
            return
        role, refs = None, {}
        for l in tree:
            l = l.strip()
            for r in ("background", "foreground", "monochrome"):
                if l.startswith("E: " + r): role = r
            if role and "drawable(0x01010199)=@0x" in l:
                refs[role] = int(l.split("=@")[1], 16); role = None
        bg = refs.get("background")
        if bg is not None and bg >> 24 == 0x7f: take(bg, "bg")
        # a foreground drawn over a plain system colour carries the whole picture; over Titanium's own background it is just the logo
        if "foreground" in refs: take(refs["foreground"], "fg" if bg is not None and bg >> 24 == 0x7f else "full108")
        if "monochrome" in refs: take(refs["monochrome"], "fg")

    for n, kind in [("mipmap/app_icon", "full"), ("drawable/ic_launcher", "full"), ("drawable/ic_launcher_round", "round"),
                    ("mipmap/layered_app_icon", "full108"), ("mipmap/layered_app_icon_background", "bg"), ("mipmap/layered_app_icon_foreground", "fg")]:
        if n in by_name: take(by_name[n], kind)
    return out


def resized(icon_dir, kind, size):
    from PIL import Image
    im = Image.open(os.path.join(icon_dir, kind + ".png")).convert("RGBA")
    out = io.BytesIO()
    im.resize(size, Image.LANCZOS).save(out, "PNG", optimize=True)
    return out.getvalue()


# ------------------------------------------------------------------------------------------------ main
def main():
    src, dst, icon_dir = sys.argv[1:4]
    zin = zipfile.ZipFile(src)
    icons = icon_files(src)
    print("icon files:", icons)
    exts = []
    for spec in sys.argv[4:]:
        name, d, key_pem = spec.split("=")
        crx, ext_id, version = make_crx(d, key_pem)
        print("bundled extension", name, ext_id, version, len(crx), "bytes")
        exts.append((name, crx, ext_id, version))
    with zipfile.ZipFile(dst, "w") as zout:
        for info in zin.infolist():
            name = info.filename
            if name.startswith("META-INF/") and name.split("/")[-1].upper().endswith((".SF", ".RSA", ".EC", ".DSA", "MANIFEST.MF")):
                continue
            data = zin.read(name)
            if name == "AndroidManifest.xml":
                data, n = patch_manifest(data, "Grove Browser"); print("manifest strings changed:", n)
            elif name == "resources.arsc":
                data, n = patch_arsc(data); print("resource strings changed:", n)
            elif name == "assets/extensions/bundled.json":
                idx = json.loads(data)
                for ename, _, ext_id, version in exts: idx[ext_id] = {"external_crx": ename + ".crx", "external_version": version}
                data = (json.dumps(idx, indent=2) + "\n").encode(); print("bundled:", list(idx))
            elif name in icons and (data[:8] == b"\x89PNG\r\n\x1a\n" or (data[:4] == b"RIFF" and data[8:12] == b"WEBP")):
                # resource files have short obfuscated names without extensions: recognise the image by its first bytes
                from PIL import Image
                w, h = Image.open(io.BytesIO(data)).size
                png = resized(icon_dir, icons[name], (w, h))
                if data[:4] == b"RIFF":
                    b = io.BytesIO(); Image.open(io.BytesIO(png)).save(b, "WEBP", lossless=True); png = b.getvalue()
                data = png
                print("icon", name, icons[name], w, h)
            elif name in icons and icons[name] == "fg" and data[:2] == b"\x03\x00":
                # the icon's foreground / monochrome layer is a vector: use Grove Browser's own compiled vector (the tree)
                data = open(os.path.join(icon_dir, "fg-vector.axml"), "rb").read(); print("icon", name, "fg vector")
            elif name in icons:
                print("icon left as is:", name)
            zi = zipfile.ZipInfo(name, info.date_time); zi.compress_type = info.compress_type; zi.external_attr = info.external_attr
            zout.writestr(zi, data)
        names = set(zin.namelist())
        assert "assets/extensions/bundled.json" in names, "Titanium no longer has assets/extensions/bundled.json"
        for ename, crx, _, _ in exts:
            zi = zipfile.ZipInfo("assets/extensions/" + ename + ".crx", (2001, 1, 1, 0, 0, 0)); zi.compress_type = zipfile.ZIP_STORED
            zout.writestr(zi, crx)
    out = zipfile.ZipFile(dst)
    idx = json.loads(out.read("assets/extensions/bundled.json"))
    assert all(e[2] in idx for e in exts), "bundled.json is missing an extension"
    print("check: bundled.json lists", list(idx))


if __name__ == "__main__":
    main()

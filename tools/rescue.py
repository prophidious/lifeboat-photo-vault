#!/usr/bin/env python3
"""
Lifeboat Rescue - get deleted photos and videos back from an SD card (or any drive).

Run it on a computer, with the SD card in a card reader. Needs Python 3.8+ and nothing else.

  1) SAFEST: make a raw copy of the card first, then scan the copy:
       python rescue.py image  <card>  card.img
       python rescue.py scan   card.img  RecoveredPhotos

  2) Or scan the card directly:
       python rescue.py scan   <card>  RecoveredPhotos

  3) Already used PhotoRec/DiskDigger? Sort its messy output by year, drop duplicates:
       python rescue.py sort   recup_dir_folder  SortedPhotos

  <card> is the whole SD card device:
     Linux:   /dev/sdX        (find it with: lsblk)                 run with sudo
     macOS:   /dev/rdiskN     (find it with: diskutil list)         run with sudo
     Windows: \\\\.\\PhysicalDriveN (Disk Management shows N)       run as Administrator

  NEVER save recovered files onto the same SD card you're rescuing.

How it works: deleting a file only marks its space as free - the photo bytes stay on the card
until something new overwrites them. This tool reads every sector of the card, finds the
start of JPEG/PNG/HEIC/MP4/MOV files, follows each file's own internal structure to find
where it ends, and writes it out. Then it files everything into year folders using the date
stored inside each photo, and removes exact duplicates.
"""
import hashlib
import os
import re
import struct
import sys
import time
from datetime import datetime, timezone

SECTOR = 512
BLOCK = 8 * 1024 * 1024
MAX_IMAGE = 200 * 1024 * 1024
MAX_VIDEO = 16 * 1024 * 1024 * 1024


class Reader:
    """Random-access reads with sector-aligned I/O (needed for raw devices on Windows/macOS)."""

    def __init__(self, path):
        self.f = open(path, "rb", buffering=0)
        self.cache_off = -1
        self.cache = b""
        try:
            self.size = os.path.getsize(path) or self._probe_size()
        except OSError:
            self.size = self._probe_size()

    def _probe_size(self):
        try:
            return self.f.seek(0, os.SEEK_END)
        except OSError:
            return 1 << 62  # unknown: read until EOF

    def read(self, off, n):
        start = off - off % SECTOR
        end = off + n
        end = end + (-end % SECTOR)
        if self.cache_off >= 0 and start >= self.cache_off and end <= self.cache_off + len(self.cache):
            s = off - self.cache_off
            return self.cache[s:s + n]
        want = max(end - start, 1024 * 1024)
        self.f.seek(start)
        data = b""
        try:
            data = self.f.read(want) or b""
        except OSError:
            data = b""
        self.cache_off, self.cache = start, data
        s = off - start
        return data[s:s + n]

    def copy_to(self, off, n, out):
        done = 0
        while done < n:
            chunk = self.read(off + done, min(4 * 1024 * 1024, n - done))
            if not chunk:
                break
            out.write(chunk)
            done += len(chunk)
        return done


# ---------- file structure parsers: return total length or None ----------
def jpeg_len(r, o):
    if r.read(o, 3) != b"\xff\xd8\xff":
        return None
    p = o + 2
    while p - o < MAX_IMAGE:
        h = r.read(p, 4)
        if len(h) < 2 or h[0] != 0xFF:
            return None
        m = h[1]
        if m == 0xFF:
            p += 1
            continue
        if m == 0xD9:
            return p + 2 - o
        if 0xD0 <= m <= 0xD7 or m == 0x01:
            p += 2
            continue
        if len(h) < 4:
            return None
        seglen = struct.unpack(">H", h[2:4])[0]
        if seglen < 2:
            return None
        p += 2 + seglen
        if m == 0xDA:  # start of scan: walk the compressed data to the next real marker
            while p - o < MAX_IMAGE:
                buf = r.read(p, 1024 * 1024)
                if len(buf) < 2:
                    return None
                i = 0
                found = False
                while True:
                    i = buf.find(b"\xff", i)
                    if i < 0 or i + 1 >= len(buf):
                        break
                    nb = buf[i + 1]
                    if nb == 0x00 or 0xD0 <= nb <= 0xD7 or nb == 0xFF:
                        i += 1 if nb == 0xFF else 2
                        continue
                    p += i
                    found = True
                    break
                if found:
                    break
                p += max(len(buf) - 1, 1)
    return None


def png_len(r, o):
    if r.read(o, 8) != b"\x89PNG\r\n\x1a\n":
        return None
    p = o + 8
    while p - o < MAX_IMAGE:
        h = r.read(p, 8)
        if len(h) < 8:
            return None
        ln, typ = struct.unpack(">I4s", h)
        if not typ.isalpha():
            return None
        p += 12 + ln
        if typ == b"IEND":
            return p - o
    return None


ISO_BOXES = {b"ftyp", b"moov", b"mdat", b"free", b"skip", b"wide", b"uuid", b"meta", b"pnot", b"PICT",
             b"pdin", b"moof", b"mfra", b"sidx", b"styp", b"emsg", b"prft", b"junk", b"udta", b"idat", b"iloc"}


def iso_len(r, o):
    h = r.read(o, 12)
    if len(h) < 12 or h[4:8] != b"ftyp":
        return None
    brand = h[8:12]
    p, seen = o, set()
    while p - o < MAX_VIDEO:
        b = r.read(p, 16)
        if len(b) < 8:
            break
        size, typ = struct.unpack(">I4s", b[:8])
        if typ not in ISO_BOXES:
            break
        if size == 1:
            if len(b) < 16:
                break
            size = struct.unpack(">Q", b[8:16])[0]
        if size == 0 or size < 8:
            break
        seen.add(typ)
        p += size
    if b"mdat" in seen and (b"moov" in seen or b"meta" in seen or b"moof" in seen):
        return p - o, brand
    return None


def ext_for_brand(brand):
    if brand == b"qt  ":
        return ".mov"
    if brand in (b"heic", b"heix", b"mif1", b"msf1", b"heim", b"heis", b"avif"):
        return ".avif" if brand == b"avif" else ".heic"
    if brand.startswith(b"3g"):
        return ".3gp"
    return ".mp4"


# ---------- dates ----------
DATE_RE = re.compile(rb"((?:19[89]\d|20[0-4]\d):[01]\d:[0-3]\d [0-2]\d:[0-5]\d:[0-5]\d)")


def exif_date(data):
    m = DATE_RE.search(data)
    if m:
        try:
            return datetime.strptime(m.group(1).decode(), "%Y:%m:%d %H:%M:%S")
        except ValueError:
            return None
    return None


def mp4_date(path):
    try:
        with open(path, "rb") as f:
            data = f.read(4 * 1024 * 1024)
            if b"mvhd" not in data:
                f.seek(max(0, os.path.getsize(path) - 8 * 1024 * 1024))
                data = f.read()
        i = data.find(b"mvhd")
        if i < 0:
            return None
        ver = data[i + 4]
        secs = struct.unpack(">Q", data[i + 8:i + 16])[0] if ver == 1 else struct.unpack(">I", data[i + 8:i + 12])[0]
        if secs < 2082844800 + 315532800:  # before 1980 = unset
            return None
        return datetime.fromtimestamp(secs - 2082844800, tz=timezone.utc).replace(tzinfo=None)
    except Exception:
        return None


def file_date(path, ext):
    if ext in (".mp4", ".mov", ".3gp", ".m4v"):
        d = mp4_date(path)
        if d:
            return d
    try:
        with open(path, "rb") as f:
            d = exif_date(f.read(256 * 1024))
        if d:
            return d
    except OSError:
        pass
    return None


# ---------- output ----------
class Sink:
    def __init__(self, out):
        self.out = out
        self.hashes = set()
        self.saved = self.dupes = 0
        os.makedirs(out, exist_ok=True)
        idx = os.path.join(out, ".hashes")
        if os.path.exists(idx):
            self.hashes = set(open(idx).read().split())
        self.idx = open(idx, "a")

    def place(self, tmp, ext, size):
        h = hashlib.sha256()
        with open(tmp, "rb") as f:
            for chunk in iter(lambda: f.read(1 << 20), b""):
                h.update(chunk)
        digest = h.hexdigest()
        if digest in self.hashes:
            os.remove(tmp)
            self.dupes += 1
            return None
        self.hashes.add(digest)
        self.idx.write(digest + "\n")
        self.idx.flush()
        d = file_date(tmp, ext)
        small = ext in (".jpg", ".png") and size < 30 * 1024
        folder = "_small_or_thumbnails" if small else (str(d.year) if d else "unknown_date")
        name = (d.strftime("%Y-%m-%d_%H%M%S") if d else "photo") + "_" + digest[:8] + ext
        dest_dir = os.path.join(self.out, folder)
        os.makedirs(dest_dir, exist_ok=True)
        dest = os.path.join(dest_dir, name)
        os.replace(tmp, dest)
        if d:
            ts = time.mktime(d.timetuple())
            try:
                os.utime(dest, (ts, ts))
            except OSError:
                pass
        self.saved += 1
        return dest


def same_device(a, b):
    try:
        return os.path.realpath(a) == os.path.realpath(b)
    except OSError:
        return False


def cmd_scan(src, out):
    r = Reader(src)
    sink = Sink(out)
    tmp = os.path.join(out, ".carving.tmp")
    sigs = [(b"\xff\xd8\xff", 0), (b"\x89PNG\r\n\x1a\n", 0), (b"ftyp", 4)]
    off = 0
    t0 = time.time()
    last_print = 0
    print(f"Scanning {src} ...")
    while off < r.size:
        r.f.seek(off)
        try:
            block = r.f.read(BLOCK)
        except OSError:
            block = None
        if not block:
            if off >= r.size or block == b"":
                break
            off += BLOCK  # unreadable area: skip it
            continue
        cands = []
        for sig, back in sigs:
            i = block.find(sig)
            while i >= 0:
                start = i - back
                if start >= 0 and start % SECTOR == 0:
                    cands.append(off + start)
                i = block.find(sig, i + 1)
        for c in sorted(set(cands)):
            head = r.read(c, 12)
            res = None
            if head.startswith(b"\xff\xd8\xff"):
                n = jpeg_len(r, c)
                res = (n, ".jpg") if n and n > 1024 else None
            elif head.startswith(b"\x89PNG"):
                n = png_len(r, c)
                res = (n, ".png") if n else None
            elif head[4:8] == b"ftyp":
                x = iso_len(r, c)
                res = (x[0], ext_for_brand(x[1])) if x else None
            if not res:
                continue
            n, ext = res
            with open(tmp, "wb") as f:
                got = r.copy_to(c, n, f)
            if got < n:
                os.remove(tmp)
                continue
            sink.place(tmp, ext, n)
        off += len(block)
        if time.time() - last_print > 2:
            last_print = time.time()
            pct = f"{100 * off / r.size:5.1f}%" if r.size < (1 << 61) else f"{off >> 20} MB"
            print(f"\r  {pct}  found {sink.saved} photos/videos ({sink.dupes} duplicates skipped)", end="", flush=True)
    print(f"\nDone in {int(time.time() - t0)}s. Recovered {sink.saved} files into: {out}")
    print("Look in the year folders first. 'unknown_date' has files with no date inside them.")


def cmd_sort(src, out):
    sink = Sink(out)
    exts = {".jpg", ".jpeg", ".png", ".heic", ".heif", ".mp4", ".mov", ".3gp", ".m4v", ".gif", ".webp", ".dng", ".avif"}
    n = 0
    for root, _, files in os.walk(src):
        for fn in files:
            ext = os.path.splitext(fn)[1].lower()
            if ext not in exts:
                continue
            p = os.path.join(root, fn)
            tmp = os.path.join(out, ".sorting.tmp")
            with open(p, "rb") as a, open(tmp, "wb") as b:
                for chunk in iter(lambda: a.read(1 << 20), b""):
                    b.write(chunk)
            sink.place(tmp, ".jpg" if ext == ".jpeg" else ext, os.path.getsize(p))
            n += 1
            if n % 100 == 0:
                print(f"\r  {n} files…", end="", flush=True)
    print(f"\nSorted {sink.saved} unique files ({sink.dupes} duplicates dropped) into {out}")


def cmd_image(src, dest):
    r = Reader(src)
    done, bad = 0, 0
    with open(dest, "wb") as out:
        while True:
            try:
                r.f.seek(done)
                b = r.f.read(BLOCK)
            except OSError:
                b = b"\x00" * BLOCK if done < r.size else b""
                bad += 1
            if not b:
                break
            out.write(b)
            done += len(b)
            print(f"\r  copied {done >> 20} MB", end="", flush=True)
            if done >= r.size:
                break
    print(f"\nImage saved: {dest}" + (f"  ({bad} unreadable blocks filled with zeros)" if bad else ""))


def main():
    if len(sys.argv) != 4 or sys.argv[1] not in ("scan", "sort", "image"):
        print(__doc__)
        sys.exit(1)
    cmd, a, b = sys.argv[1:]
    if same_device(a, b):
        sys.exit("Input and output can't be the same.")
    {"scan": cmd_scan, "sort": cmd_sort, "image": cmd_image}[cmd](a, b)


if __name__ == "__main__":
    main()

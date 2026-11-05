# Installing a port on the device the builder app runs on, with no adb. Android lets only the game write to the game's files folder, so the work splits in two:
# the app hands the APK to Android's own installer (the player taps Install, as for any app), and the game, at its first start, fetches its data from here.
# The server listens on the device's loopback, only while this waits, and answers only to the secret in the APK (assets/pack.txt). The other half is dm/android/pack_sync_core.h.
import http.server, os, re, secrets, threading, time, urllib.parse, zlib
from pathlib import Path

from porter import datapack
from porter.upstream import BuildError, PROG, say

PORT = 47183
WAIT = 6 * 3600   # seconds this waits for the player: everything before the install is done by then, and a stop here would only ask them to tap again
WHOLE, PIECE, PIECES = 64 << 20, 65536, 256   # pack_sync_core.h's fingerprint
BY_SIZE = ('RomFS', 'RomFS.img', 'aoc')       # the game's own files: they do not change at the same size (adb's push treats them alike)


def fingerprint(path, size=None):
    """CRC-32 of the file, or past 64 MB of 256 pieces of it: a changed file is found in a moment"""
    size = os.path.getsize(path) if size is None else size
    crc = 0
    with open(path, 'rb') as f:
        if size <= WHOLE:
            while b := f.read(1 << 20): crc = zlib.crc32(b, crc)
        else:
            for i in range(PIECES):
                f.seek((size - PIECE) // (PIECES - 1) * i); crc = zlib.crc32(f.read(PIECE), crc)
    return crc


def secret(file):
    """The APK's secret. It stays the same across runs of one build folder, so a port taken up again serves the APK it made"""
    file = Path(file)
    if file.is_file() and (t := file.read_text(encoding='utf-8').strip()): return t
    t = secrets.token_hex(16); file.parent.mkdir(parents=True, exist_ok=True); file.write_text(t + '\n', encoding='utf-8')
    return t


def rows(data):
    """The list the game compares its folder with: F size crc keep name per file, the manifest last, then O name for each folder the pack owns. Returns (text, {name: path})"""
    data = Path(data)
    files = sorted((p.relative_to(data).as_posix(), p) for p in data.rglob('*') if p.is_file())
    files = [(rel, p) for rel, p in files if rel != datapack.MANIFEST and not rel.startswith('.dmpart')]
    if (data / datapack.MANIFEST).is_file(): files.append((datapack.MANIFEST, data / datapack.MANIFEST))
    out = []
    for rel, p in files:
        if '\n' in rel: raise BuildError(f'a file name with a line break: {rel!r}')
        size = p.stat().st_size
        crc = 0 if rel.split('/', 1)[0] in BY_SIZE else fingerprint(p, size) or 1   # 0 means by size alone
        out.append(f'F {size} {crc:08x} {int(rel in datapack.KEEP_ON_DEVICE)} {rel}\n')
    out += [f'O {d}\n' for d in datapack.OWNED if (data / d).is_dir()]
    return ''.join(out).encode('utf-8'), dict(files)


class Pack(http.server.ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, data, token, port=PORT, captures=None):
        self.token, self.listing, self.files = token, *rows(data)
        self.captures, self.got = Path(captures) if captures else None, []
        self.seen = self.finished = False; self.failed = None
        self.need = None; self.sent = 0; self.note = ''; self.mu = threading.Lock()
        super().__init__(('127.0.0.1', port), Answer)


class Answer(http.server.BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.0'
    timeout = 120

    def log_message(self, *a): pass

    def reply(self, code, body=b''):
        self.send_response(code); self.send_header('Content-Length', str(len(body))); self.end_headers(); self.wfile.write(body)

    def do_GET(self):
        s = self.server
        token, _, what = urllib.parse.unquote(self.path.lstrip('/')).partition('/')
        if not secrets.compare_digest(token.encode(), s.token.encode()): return self.reply(403)
        cmd, _, arg = what.partition('/')
        if cmd == 'hello': s.seen = True; return self.reply(200, b'nx2android')
        if cmd == 'list': return self.reply(200, s.listing)
        if cmd == 'need': s.need = int(arg); return self.reply(200)
        if cmd == 'done': s.finished = True; return self.reply(200)
        if cmd == 'fail': s.failed = arg or 'the game could not copy its data'; return self.reply(200)
        if cmd != 'file' or arg not in s.files: return self.reply(404)
        self.send_file(s.files[arg], arg)

    def do_PUT(self):   # a shader the game saved because the bundle lacks it (gfx_vk_shader.c): for the next port to translate, as porter/pipeline.py pull_captures does over adb
        s = self.server
        token, _, what = urllib.parse.unquote(self.path.lstrip('/')).partition('/')
        cmd, _, name = what.partition('/')
        size = int(self.headers.get('Content-Length', -1))
        if not secrets.compare_digest(token.encode(), s.token.encode()): return self.reply(403)
        if cmd != 'captured' or not s.captures or not re.fullmatch(r'[0-9a-f]{16}\.bin', name) or not 0 < size <= 1 << 26: return self.reply(400)
        body = self.rfile.read(size)
        if len(body) != size: return self.reply(400)
        s.captures.mkdir(parents=True, exist_ok=True)
        if not (s.captures / name).is_file():
            (s.captures / (name + '.part')).write_bytes(body); (s.captures / (name + '.part')).replace(s.captures / name); s.got.append(name)
        self.reply(200)

    def send_file(self, path, name):
        s = self.server
        size = path.stat().st_size
        from_ = 0
        if (r := self.headers.get('Range', '')).startswith('bytes='):
            try: from_ = min(int(r[6:].split('-')[0] or 0), size)
            except ValueError: from_ = 0
        self.send_response(206 if from_ else 200); self.send_header('Content-Length', str(size - from_)); self.end_headers(); self.wfile.flush()
        with s.mu:
            s.note = name; s.sent += from_   # what the game already has counts as done
        with open(path, 'rb') as f:
            at = from_
            try:
                while at < size:
                    n = self.connection.sendfile(f, at, min(size - at, 1 << 24))   # the socket's own: it waits out a full socket, which a socket with a timeout otherwise reports as an error
                    if not n: break
                    at += n
                    with s.mu: s.sent += n
            except OSError: pass
            if at < size:   # cut off: the game asks again from where it has got to
                with s.mu: s.sent -= at


def serve(data, token, port=PORT, captures=None):
    try: srv = Pack(data, token, port, captures)
    except OSError as e: raise BuildError(f'could not serve the game data on port {port}: {e}') from e
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv


def tell(ev, **fields):
    if PROG.mode == 'json': PROG._json({'ev': ev, **fields})


def install(apk, data, token, port=PORT, wait=WAIT, captures=None):
    apk, data = Path(apk), Path(data)
    man = datapack.read_manifest(data)
    if not man: raise BuildError(f'not a data pack (no {datapack.MANIFEST}): {data}')
    if not apk.is_file(): raise BuildError(f'no APK: {apk}')
    if PROG.mode != 'json': raise BuildError('an install with no adb is made by the builder app, which runs the installer')
    pkg, name = man['package'], man['name']
    PROG.stage('install', step='getting the game data ready to copy')
    flag = data.parent / '.install-failed'; flag.unlink(missing_ok=True)
    srv = serve(data, token, port, captures)
    try:
        try: os.remove('/sdcard/nx2android/frame-dump.tgz')   # an older frame capture: half a GB
        except OSError: pass
        PROG.set(step=f'tap Install on the popup. {name} then opens by itself, or open it yourself')
        say('asking Android to install the game: tap Install on the popup')
        tell('install_apk', apk=str(apk), package=pkg, label=name, flag=str(flag))
        end, was = time.time() + wait, False
        while not srv.finished:
            if flag.is_file():
                time.sleep(0.5)   # the app may be between making the file and filling it
                raise BuildError(flag.read_text(encoding='utf-8').strip() or 'Android did not install the game')
            if srv.failed: raise BuildError(f'the game could not copy its data: {srv.failed}')
            if time.time() > end: raise BuildError(f'{name} was not opened: tap Port again to go on')
            if srv.seen and not was:
                was = True; tell('game_seen'); PROG.set(step='the game is checking its files')
            if srv.need is not None:
                if srv.need == 0: PROG.set(step='the game has all its data')
                else:
                    with srv.mu: sent, note = srv.sent, srv.note
                    PROG.set(step='the game is copying its data: keep it open', done=min(sent, srv.need), total=srv.need, unit='B', note=note)
            time.sleep(1)
    finally:
        time.sleep(1.5)   # the game's last request gets its answer
        srv.shutdown(); srv.server_close()
    say(f'{name} has all its data ({(srv.need or 0) / 2**30:.1f} GB copied)')
    if srv.got: say(f'{len(srv.got)} shader(s) the game saved came back: the next port translates them')
    tell('open_game', package=pkg, label=name)
    PROG.end(f'{name} on this device')
    say(f'installed: open "{name}"')

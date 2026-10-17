"""Development experiments only. Never replaces the supplied source video."""
from __future__ import annotations
import argparse, ctypes as C, hashlib, json, os, shutil, sqlite3
import subprocess as S, sys, tempfile, time
from pathlib import Path
from ctypes import wintypes as W

ROOT = Path(__file__).resolve().parents[2]
RUN = ROOT / 'validation' / '2026-10-17'
RUN_ID = os.environ.setdefault('ERGOU_PREFLIGHT_RUN_ID', time.strftime('%Y%m%d-%H%M%S'))
LOG = RUN / 'logs' / RUN_ID
WORK = RUN / 'work' / RUN_ID
REPORT = RUN / 'reports' / RUN_ID
FF = Path(r'D:\OtherLanguageSetting\FFmpeg\ffmpeg-8.1.1-essentials_build\bin\ffmpeg.exe')
FP = FF.with_name('ffprobe.exe')
SOURCE = ROOT / 'test_resources' / '[餅月なこ][2026-05-13][_YouTube非公開動画.mp4'
RESULTS = []

def save(path, obj):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + '.writing')
    with tmp.open('w', encoding='utf-8') as f:
        json.dump(obj, f, ensure_ascii=False, indent=2)
        f.flush(); os.fsync(f.fileno())
    os.replace(tmp, path)

def digest(path):
    with Path(path).open('rb') as f:
        return hashlib.file_digest(f, 'sha256').hexdigest()

def record(name, ok, **details):
    item = dict(test=name, status='PASS' if ok else 'FAIL', **details)
    RESULTS.append(item)
    save(REPORT / 'preflight-results.json', RESULTS)
    print(json.dumps(item, ensure_ascii=False), flush=True)
    return ok

def command(name, argv, timeout=180, q_after=None):
    argv = list(map(str, argv))
    save(LOG / (name + '.argv.json'), argv)
    start = time.monotonic()
    p = S.Popen(argv, stdin=S.PIPE, stdout=S.PIPE, stderr=S.PIPE,
                cwd=WORK, creationflags=S.CREATE_NO_WINDOW)
    stopped = False
    try:
        if q_after is not None:
            time.sleep(q_after)
            stopped = True
            out, err = p.communicate(b'q\n', timeout=20)
        else:
            out, err = p.communicate(timeout=timeout)
    except S.TimeoutExpired:
        p.kill(); out, err = p.communicate()
        stopped = True
    elapsed = time.monotonic() - start
    (LOG / (name + '.stdout.txt')).write_bytes(out)
    (LOG / (name + '.stderr.txt')).write_bytes(err)
    meta = dict(exitCode=p.returncode, seconds=round(elapsed, 3), stopRequested=stopped,
                stdoutBytes=len(out), stderrBytes=len(err))
    save(LOG / (name + '.process.json'), meta)
    return p.returncode, out, err, meta

def probe(name, path):
    rc, out, err, meta = command(name, [FP, '-v', 'error', '-show_format',
        '-show_streams', '-show_chapters', '-of', 'json', path])
    if rc: raise RuntimeError(err.decode('utf-8', 'replace'))
    data = json.loads(out)
    save(REPORT / (name + '.json'), data)
    return data

def progress(raw):
    blocks, block = [], {}
    for line in raw.decode('utf-8', 'replace').splitlines():
        key, sep, value = line.partition('=')
        if not sep: continue
        block[key] = value
        if key == 'progress':
            blocks.append(block); block = {}
    return blocks

def encode(name, options, seconds=12, start=60):
    output = WORK / (name + '.mp4')
    argv = [FF, '-hide_banner', '-n', '-xerror', '-nostats', '-stats_period', '0.5',
            '-progress', 'pipe:1', *(['-ss', start] if start > 0 else []), '-i', SOURCE, *(['-t', seconds] if seconds is not None else []),
            '-map', '0:V:0', '-map', '0:a?', *options, '-movflags', '+faststart', output]
    rc, out, err, meta = command(name, argv, timeout=600)
    blocks = progress(out)
    if rc or meta['stopRequested']:
        record(name, False, **meta, error=err.decode('utf-8', 'replace')[-1500:])
        return None
    info = probe(name + '-probe', output)
    v = next(s for s in info['streams'] if s['codec_type'] == 'video')
    a = next((s for s in info['streams'] if s['codec_type'] == 'audio'), None)
    decode_rc, _, decode_err, decode_meta = command(name + '-decode', [FF, '-hide_banner',
        '-nostdin', '-v', 'error', '-xerror', '-err_detect', 'explode', '-i', output,
        '-map', '0:V?', '-map', '0:a?', '-f', 'null', 'NUL'], timeout=600)
    dur = float(info['format']['duration'])
    expected = seconds if seconds is not None else float(SOURCE_INFO['format']['duration'])
    duration_ok = abs(dur - expected) <= 0.6
    record(name, decode_rc == 0 and duration_ok and blocks and blocks[-1].get('progress') == 'end',
           **meta, output=str(output), bytes=output.stat().st_size, sha256=digest(output),
           duration=dur, codec=v['codec_name'], width=v['width'], height=v['height'],
           fps=v['avg_frame_rate'], audioCodec=a.get('codec_name') if a else None,
           sampleRate=a.get('sample_rate') if a else None, channels=a.get('channels') if a else None,
           progressBlocks=len(blocks), decodeSeconds=decode_meta['seconds'])
    save(REPORT / (name + '-progress-summary.json'), dict(first=blocks[0], last=blocks[-1], count=len(blocks)))
    return output

def kernel():
    k = C.WinDLL('kernel32', use_last_error=True)
    k.ReplaceFileW.argtypes = [W.LPCWSTR, W.LPCWSTR, W.LPCWSTR, W.DWORD, W.LPVOID, W.LPVOID]
    k.ReplaceFileW.restype = W.BOOL
    k.CreateFileW.argtypes = [W.LPCWSTR, W.DWORD, W.DWORD, W.LPVOID, W.DWORD, W.DWORD, W.HANDLE]
    k.CreateFileW.restype = W.HANDLE
    k.CloseHandle.argtypes = [W.HANDLE]; k.CloseHandle.restype = W.BOOL
    k.OpenProcess.argtypes = [W.DWORD, W.BOOL, W.DWORD]; k.OpenProcess.restype = W.HANDLE
    k.WaitForSingleObject.argtypes = [W.HANDLE, W.DWORD]; k.WaitForSingleObject.restype = W.DWORD
    return k

def replace(old, new, backup):
    k = kernel(); C.set_last_error(0)
    ok = k.ReplaceFileW(str(old), str(new), str(backup), 0, None, None)
    return bool(ok), 0 if ok else C.get_last_error()

def replace_child(directory, phase):
    d = Path(directory)
    save(d / 'intent.json', dict(phase='PublishIntent', sourceHash=digest(d/'source.bin'),
                               candidateHash=digest(d/'candidate.bin')))
    if phase == 'before': os._exit(73)
    ok, err = replace(d/'source.bin', d/'candidate.bin', d/'backup.bin')
    if not ok: raise OSError(err, 'ReplaceFileW failed')
    os._exit(74)

def native_tests():
    for phase in ['before', 'after']:
        d = WORK / ('replace-crash-' + phase); d.mkdir()
        original, candidate = b'original\0' * 1000, b'validated-candidate\0' * 500
        (d/'source.bin').write_bytes(original); (d/'candidate.bin').write_bytes(candidate)
        result = S.run([sys.executable, __file__, '--replace-child', str(d), phase], creationflags=S.CREATE_NO_WINDOW)
        intent = json.loads((d/'intent.json').read_text('utf-8'))
        if phase == 'before':
            ok = digest(d/'source.bin') == intent['sourceHash'] and (d/'candidate.bin').exists()
        else:
            ok = digest(d/'source.bin') == intent['candidateHash'] and digest(d/'backup.bin') == intent['sourceHash']
        record('replace-crash-' + phase, ok, childExit=result.returncode, intent=intent)
    d = WORK/'replace-locked'; d.mkdir()
    (d/'source.bin').write_bytes(b'old'); (d/'candidate.bin').write_bytes(b'new')
    k = kernel()
    h = k.CreateFileW(str(d/'source.bin'), 0x80000000, 1, None, 3, 0x80, None)
    if h == C.c_void_p(-1).value: raise C.WinError(C.get_last_error())
    try:
        ok, error = replace(d/'source.bin', d/'candidate.bin', d/'backup.bin')
        record('replace-locked-preserves-source', not ok and (d/'source.bin').read_bytes()==b'old'
               and (d/'candidate.bin').read_bytes()==b'new', win32Error=error)
    finally: k.CloseHandle(h)
    ok, error = replace(d/'source.bin', d/'candidate.bin', d/'backup.bin')
    record('replace-unlocked-retry', ok and (d/'backup.bin').read_bytes()==b'old', win32Error=error)
    other = Path(tempfile.mkdtemp(prefix='ergou-preflight-cross-volume-'))
    (other/'candidate.bin').write_bytes(b'cross-volume')
    d = WORK/'replace-cross-volume'; d.mkdir(); (d/'source.bin').write_bytes(b'old')
    ok, error = replace(d/'source.bin', other/'candidate.bin', d/'backup.bin')
    record('replace-cross-volume-rejected', not ok and (d/'source.bin').read_bytes()==b'old'
           and (other/'candidate.bin').exists(), win32Error=error, alternateRoot=str(other))
    shutil.copyfile(other/'candidate.bin', d/'staged.bin')
    with (d/'staged.bin').open('rb+') as f: os.fsync(f.fileno())
    same = digest(other/'candidate.bin') == digest(d/'staged.bin')
    ok, error = replace(d/'source.bin', d/'staged.bin', d/'backup.bin')
    record('cross-volume-stage-then-replace', same and ok and (d/'backup.bin').read_bytes()==b'old', win32Error=error)

def job_child(result_path):
    k = kernel()
    class BASIC(C.Structure):
        _fields_=[('perProcess', C.c_int64), ('perJob', C.c_int64), ('flags', W.DWORD),
                  ('minWorking', C.c_size_t), ('maxWorking', C.c_size_t), ('active', W.DWORD),
                  ('affinity', C.c_size_t), ('priority', W.DWORD), ('scheduling', W.DWORD)]
    class IO(C.Structure):
        _fields_=[(x, C.c_uint64) for x in ['readOps','writeOps','otherOps','readBytes','writeBytes','otherBytes']]
    class EXT(C.Structure):
        _fields_=[('basic', BASIC), ('io', IO), ('processMemory', C.c_size_t), ('jobMemory', C.c_size_t),
                  ('peakProcess', C.c_size_t), ('peakJob', C.c_size_t)]
    class SI(C.Structure):
        _fields_=[('cb', W.DWORD), ('reserved', W.LPWSTR), ('desktop', W.LPWSTR), ('title', W.LPWSTR),
          ('x', W.DWORD),('y', W.DWORD),('xSize',W.DWORD),('ySize',W.DWORD),('xCount',W.DWORD),
          ('yCount',W.DWORD),('fill',W.DWORD),('flags',W.DWORD),('show',W.WORD),('reserved2Size',W.WORD),
          ('reserved2',C.POINTER(C.c_byte)),('stdin',W.HANDLE),('stdout',W.HANDLE),('stderr',W.HANDLE)]
    class SIX(C.Structure): _fields_=[('si',SI),('attrs',W.LPVOID)]
    class PI(C.Structure): _fields_=[('process',W.HANDLE),('thread',W.HANDLE),('pid',W.DWORD),('tid',W.DWORD)]
    k.CreateJobObjectW.argtypes=[W.LPVOID,W.LPCWSTR]; k.CreateJobObjectW.restype=W.HANDLE
    k.SetInformationJobObject.argtypes=[W.HANDLE,C.c_int,W.LPVOID,W.DWORD];k.SetInformationJobObject.restype=W.BOOL
    k.InitializeProcThreadAttributeList.argtypes=[W.LPVOID,W.DWORD,W.DWORD,C.POINTER(C.c_size_t)]
    k.UpdateProcThreadAttribute.argtypes=[W.LPVOID,W.DWORD,C.c_size_t,W.LPVOID,C.c_size_t,W.LPVOID,W.LPVOID]
    k.CreateProcessW.argtypes=[W.LPCWSTR,W.LPWSTR,W.LPVOID,W.LPVOID,W.BOOL,W.DWORD,W.LPVOID,W.LPCWSTR,C.POINTER(SIX),C.POINTER(PI)]
    k.CreateProcessW.restype=W.BOOL
    k.DeleteProcThreadAttributeList.argtypes=[W.LPVOID]
    job=k.CreateJobObjectW(None,None)
    if not job: raise C.WinError(C.get_last_error())
    limits=EXT(); limits.basic.flags=0x2000
    if not k.SetInformationJobObject(job,9,C.byref(limits),C.sizeof(limits)):raise C.WinError(C.get_last_error())
    size=C.c_size_t();k.InitializeProcThreadAttributeList(None,1,0,C.byref(size))
    buf=C.create_string_buffer(size.value)
    if not k.InitializeProcThreadAttributeList(buf,1,0,C.byref(size)):raise C.WinError(C.get_last_error())
    jobs=(W.HANDLE*1)(job)
    if not k.UpdateProcThreadAttribute(buf,0,0x0002000D,C.byref(jobs),C.sizeof(jobs),None,None):raise C.WinError(C.get_last_error())
    sx=SIX();sx.si.cb=C.sizeof(SIX);sx.attrs=C.cast(buf,W.LPVOID);pi=PI()
    argv=[str(FF),'-hide_banner','-nostdin','-v','error','-re','-f','lavfi','-i','testsrc2=size=160x90:rate=10','-f','null','NUL']
    cmd=C.create_unicode_buffer(S.list2cmdline(argv))
    if not k.CreateProcessW(str(FF),cmd,None,None,False,0x08000000|0x00080000,None,str(WORK),C.byref(sx),C.byref(pi)):
        raise C.WinError(C.get_last_error())
    k.DeleteProcThreadAttributeList(buf); k.CloseHandle(pi.thread);k.CloseHandle(pi.process)
    save(result_path,dict(pid=pi.pid,jobListAtCreation=True,killOnJobClose=True))
    time.sleep(300)

def job_test():
    path=WORK/'job-child.json'
    p=S.Popen([sys.executable,__file__,'--job-child',str(path)],stdout=S.PIPE,stderr=S.PIPE,creationflags=S.CREATE_NO_WINDOW)
    deadline=time.monotonic()+15
    while not path.exists() and p.poll() is None and time.monotonic()<deadline:time.sleep(.1)
    if not path.exists():
        p.kill() if p.poll() is None else None
        out,err=p.communicate();record('job-list-create-kill-parent',False,error=err.decode('utf-8','replace'));return
    data=json.loads(path.read_text('utf-8'));k=kernel();h=k.OpenProcess(0x00100000,False,data['pid'])
    alive=bool(h) and k.WaitForSingleObject(h,0)==258
    p.kill();p.communicate(timeout=10)
    exited=bool(h) and k.WaitForSingleObject(h,10000)==0
    if h:k.CloseHandle(h)
    record('job-list-create-kill-parent',alive and exited,**data,aliveBeforeParentKill=alive,exitedAfterParentKill=exited)

def db_child(path):
    db=sqlite3.connect(path);db.execute('PRAGMA synchronous=FULL')
    db.execute("UPDATE items SET state='Succeeded' WHERE id=2")
    os._exit(75)

def database_tests():
    path=WORK/'catalog.sqlite';db=sqlite3.connect(path)
    mode=db.execute('PRAGMA journal_mode=DELETE').fetchone()[0]
    db.execute('PRAGMA synchronous=FULL');db.execute('PRAGMA foreign_keys=ON')
    db.execute('CREATE TABLE items(id INTEGER PRIMARY KEY,state TEXT NOT NULL,revision INTEGER NOT NULL)')
    db.executemany('INSERT INTO items VALUES(?,?,0)',[(1,'Succeeded'),(2,'Encoding'),(3,'Ready')]);db.execute('PRAGMA user_version=1');db.commit();db.close()
    child=S.run([sys.executable,__file__,'--db-child',str(path)],creationflags=S.CREATE_NO_WINDOW)
    db=sqlite3.connect(path);states=db.execute('SELECT id,state FROM items ORDER BY id').fetchall()
    check=db.execute('PRAGMA integrity_check').fetchone()[0]
    record('sqlite-crash-rollback-recovery',states==[(1,'Succeeded'),(2,'Encoding'),(3,'Ready')] and check=='ok',
           sqliteVersion=sqlite3.sqlite_version,journalMode=mode,childExit=child.returncode,states=states,integrity=check,
           limitation='Python SQLite experiment; JVM driver is validated separately')
    backup=sqlite3.connect(WORK/'catalog-backup.sqlite');db.backup(backup)
    same=backup.execute('SELECT * FROM items').fetchall()==db.execute('SELECT * FROM items').fetchall();backup.close()
    db.execute('ALTER TABLE items ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 0');db.execute('PRAGMA user_version=2');db.commit()
    record('sqlite-backup-migration',same and db.execute('PRAGMA user_version').fetchone()[0]==2)
    db.close()

def main():
    global SOURCE_INFO
    for p in [LOG,WORK,REPORT]:p.mkdir(parents=True,exist_ok=True)
    save(RUN/'reports'/'latest.json',dict(runId=RUN_ID,report=str(REPORT),logs=str(LOG),work=str(WORK)))
    before=dict(bytes=SOURCE.stat().st_size,sha256=digest(SOURCE),mtimeNs=SOURCE.stat().st_mtime_ns)
    save(REPORT/'source-before.json',before)
    for name,option in [('ffmpeg-version','-version'),('ffmpeg-buildconf','-buildconf'),('encoders','-encoders'),
                        ('filters','-filters'),('muxers','-muxers'),('hwaccels','-hwaccels')]:
        command(name,[FF,'-hide_banner',option])
    for encoder in ['libx264','libx265','hevc_nvenc','av1_nvenc','h264_qsv','hevc_amf','libsvtav1']:
        command('help-'+encoder,[FF,'-hide_banner','-h','encoder='+encoder])
    SOURCE_INFO=probe('source-probe',SOURCE)
    record('source-probe-unicode-path',len(SOURCE_INFO['streams'])==2,bytes=before['bytes'],duration=SOURCE_INFO['format']['duration'])
    native_tests();job_test();database_tests()
    cpu=encode('cpu-720p24-aac', ['-vf','scale=-2:720,fps=24','-c:v','libx264','-preset','veryfast','-crf','23',
                               '-pix_fmt','yuv420p','-c:a','aac','-b:a','160k','-ar','48000'])
    gpu=encode('nvenc-1080p30-copy', ['-c:v','hevc_nvenc','-preset','p5','-tune','hq','-rc','vbr','-cq','28','-b:v','0',
                                    '-pix_fmt','yuv420p','-fps_mode','passthrough','-c:a','copy'])
    qout=WORK/'stopped.partial.mp4'
    rc,out,err,meta=command('stop-q',[FF,'-hide_banner','-n','-nostats','-progress','pipe:1','-re','-ss','60','-i',SOURCE,
        '-t','20','-map','0:V:0','-map','0:a?','-c:v','libx264','-preset','ultrafast','-crf','28','-c:a','copy',qout],q_after=2)
    stopped_info=probe('stop-q-probe',qout) if qout.exists() and qout.stat().st_size else None
    record('q-exit-zero-is-not-success',meta['stopRequested'] and stopped_info is not None
           and float(stopped_info['format']['duration'])<19,**meta,duration=stopped_info['format']['duration'] if stopped_info else None)
    if gpu:
        full=encode('full-nvenc-copy',['-c:v','hevc_nvenc','-preset','p5','-tune','hq','-rc','vbr','-cq','28','-b:v','0',
                    '-pix_fmt','yuv420p','-fps_mode','passthrough','-c:a','copy'],seconds=None,start=0)
        if full:
            d=WORK/'media-safe-commit';d.mkdir()
            shutil.copyfile(SOURCE,d/'source.mp4');shutil.copyfile(full,d/'candidate.mp4')
            ok,error=replace(d/'source.mp4',d/'candidate.mp4',d/'backup.mp4')
            record('real-media-copy-safe-replace',ok and digest(d/'backup.mp4')==before['sha256']
                   and digest(d/'source.mp4')==digest(full),win32Error=error)
    samples=[]
    for index,start in enumerate([60,600,1150]):
        path=encode('estimate-sample-'+str(index),['-c:v','hevc_nvenc','-preset','p5','-tune','hq','-rc','vbr','-cq','28',
                    '-b:v','0','-pix_fmt','yuv420p','-an'],seconds=10,start=start) if gpu else None
        if path:samples.append(path.stat().st_size/10)
    if samples and (WORK/'full-nvenc-copy.mp4').exists():
        dur=float(SOURCE_INFO['format']['duration']);audio=next(x for x in SOURCE_INFO['streams'] if x['codec_type']=='audio')
        predicted=sum(samples)/len(samples)*dur+int(audio['bit_rate'])*dur/8
        actual=(WORK/'full-nvenc-copy.mp4').stat().st_size
        record('sample-estimate-observation',True,predictedBytes=round(predicted),actualBytes=actual,
               relativeError=abs(predicted-actual)/actual,limitation='One source, sample mux overhead included; not a general accuracy guarantee')
    after=dict(bytes=SOURCE.stat().st_size,sha256=digest(SOURCE),mtimeNs=SOURCE.stat().st_mtime_ns)
    save(REPORT/'source-after.json',after);record('supplied-source-unchanged',before==after,**after)
    print('PRELIGHT_COMPLETE',flush=True)
    if any(item['status'] != 'PASS' for item in RESULTS): raise SystemExit(1)

if __name__=='__main__':
    if len(sys.argv)>1 and sys.argv[1]=='--replace-child':replace_child(sys.argv[2],sys.argv[3])
    elif len(sys.argv)>1 and sys.argv[1]=='--job-child':job_child(sys.argv[2])
    elif len(sys.argv)>1 and sys.argv[1]=='--db-child':db_child(sys.argv[2])
    else:main()

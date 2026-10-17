"""Archive verified evidence; prepare an explicit cleanup plan without deleting anything."""
import hashlib,json,re,zipfile,time
from pathlib import Path
from urllib.parse import unquote
ROOT=Path(__file__).resolve().parents[2];RUN=ROOT/'validation/2026-10-17'
EVIDENCE=RUN/'evidence';EVIDENCE.mkdir(exist_ok=True)
archive=EVIDENCE/'acceptance-evidence.zip'
if archive.exists(): archive=EVIDENCE/('acceptance-evidence-'+time.strftime('%Y%m%d-%H%M%S')+f'-{time.time_ns()%1000000:06d}.zip')
manifest_path=RUN/'reports/evidence-manifest.json'

def digest(path):
    with path.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
def safe_file(file):
    assert file.resolve().is_relative_to(ROOT.resolve()),file
    assert not file.is_symlink(),file
    return file
linked=set()
for doc in list((ROOT/'docs').rglob('*.md'))+[ROOT/'README.md',ROOT/'tools/preflight/README.md']:
    for match in re.finditer(r'\[[^\]\n]*\]\((<[^>]+>|[^)\s]+)(?:\s+"[^"]*")?\)',doc.read_text('utf-8-sig')):
        value=unquote(match[1].strip('<>')).split('#')[0]
        if not re.match(r'^[a-z]+:',value,re.I):linked.add((doc.parent/value).resolve())
files=set()
for folder in [RUN/'logs',RUN/'reports']:
    files.update(safe_file(f) for f in folder.rglob('*') if f.is_file() and f not in [manifest_path,RUN/'reports/cleanup-report.json'])
for module in ['sharedLogic','sharedUI','validation/2026-10-17/build-probe']:
    files.update(safe_file(f) for f in (ROOT/module/'build/test-results').rglob('*.xml'))
for folder in (RUN/'work',RUN/'build-probe/portable-dev'):
    for f in folder.rglob('*'):
        if not f.is_file() or any(part.startswith('portable-moved-') for part in f.parts):continue
        if f.stat().st_size<=131072 and f.suffix in ['.json','.log','.txt','.sqlite','.bin']:
            files.add(safe_file(f))
for folder in [ROOT/'tools/preflight',RUN/'build-probe/src']:
    files.update(safe_file(f) for f in folder.rglob('*') if f.is_file() and f.suffix in ['.py','.ps1','.kt','.sq','.sqm'])
files.update((RUN/'build-probe').glob('*.kts'));files.update((RUN/'build-probe').glob('*.lockfile'))
files.add(RUN/'build-probe/gradle/verification-metadata.xml')
entries=[]
with zipfile.ZipFile(archive,'x',compression=zipfile.ZIP_DEFLATED,compresslevel=9) as z:
    for file in sorted(files):
        safe_file(file);name=file.relative_to(ROOT).as_posix()
        entries.append(dict(path=name,bytes=file.stat().st_size,sha256=digest(file)))
        z.write(file,name)
    z.writestr('ARCHIVE_INDEX.json',json.dumps(entries,ensure_ascii=False,indent=2))
with zipfile.ZipFile(archive) as z:
    assert z.testzip() is None
    for item in entries:
        with z.open(item['path']) as f:assert hashlib.file_digest(f,'sha256').hexdigest()==item['sha256'],item['path']
large=[]
for item in entries:
    file=ROOT/item['path']
    if file.is_relative_to(RUN/'logs') or file.is_relative_to(RUN/'reports'):
        if item['bytes']>524288 and file.resolve() not in linked:large.append(item)
roots=['validation/2026-10-17/work','validation/2026-10-17/build-probe/build',
    'validation/2026-10-17/build-probe/portable-dev','validation/2026-10-17/build-probe/.gradle',
    'desktopApp/build','sharedLogic/build','sharedUI/build','tools/preflight/__pycache__']
temporary=set()
for folder in [RUN/'reports']:
    for file in folder.rglob('*.json'):
        if file.stat().st_size>524288:continue
        try:value=json.loads(file.read_text('utf-8-sig'))
        except (ValueError,OSError):continue
        def scan(node):
            if isinstance(node,dict):
                if isinstance(node.get('alternateRoot'),str):temporary.add(node['alternateRoot'])
                for v in node.values():scan(v)
            elif isinstance(node,list):
                for v in node:scan(v)
        scan(value)
source=ROOT/'test_resources/[餅月なこ][2026-05-13][_YouTube非公開動画.mp4'
identity=dict(bytes=source.stat().st_size,mtimeNs=source.stat().st_mtime_ns,sha256=digest(source))
assert identity['sha256']=='c14cee8fbafbfd5b0a70d7b26672f93dd2263605e2ac34fbce6e0398a1249e82'
manifest=dict(status='VERIFIED',archive=archive.relative_to(ROOT).as_posix(),archiveBytes=archive.stat().st_size,
    archiveSha256=digest(archive),entries=entries,cleanupRoots=roots,largeEvidenceToRemove=large,
    temporaryRoots=sorted(temporary),source=identity)
manifest_path.write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(dict(status=manifest['status'],archiveBytes=manifest['archiveBytes'],entries=len(entries),
    largeRawBytes=sum(x['bytes'] for x in large),temporaryRoots=len(temporary)),ensure_ascii=False))

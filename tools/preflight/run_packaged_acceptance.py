"""Run bounded, non-input acceptance modes of the generated EXE with bundled Java."""
import hashlib,json,os,subprocess,time
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2];RUN=ROOT/'validation/2026-10-17'
EXE=RUN/'build-probe/build/compose/binaries/main/app/ErgouPreflight/ErgouPreflight.exe'
WORK=RUN/'work/s0-acceptance/packaged';WORK.mkdir(parents=True,exist_ok=True)
LOG=RUN/'logs/packaged-acceptance';LOG.mkdir(parents=True,exist_ok=True)
env=os.environ.copy();env['JAVA_HOME']=r'D:\no-external-java';env['PATH']=r'C:\Windows\System32'
rows=[]
for name,args,report in [
 ('drop-event',['--drop-event-acceptance',str(WORK/'drop'),str(RUN/'reports/drop-packaged-acceptance.json')],RUN/'reports/drop-packaged-acceptance.json'),
 ('commit',['--commit-acceptance',str(WORK/'commit'),str(RUN/'reports/commit-packaged-acceptance.json')],RUN/'reports/commit-packaged-acceptance.json'),
 ('self-test',['--self-test'],None)]:
 started=time.monotonic()
 result=subprocess.run([str(EXE),*args],cwd=ROOT/'test_resources',env=env,capture_output=True,timeout=150,creationflags=subprocess.CREATE_NO_WINDOW)
 (LOG/(name+'.stdout.txt')).write_bytes(result.stdout);(LOG/(name+'.stderr.txt')).write_bytes(result.stderr)
 values=json.loads(report.read_text('utf-8')) if report and report.exists() else None
 ok=result.returncode==0 and (values is None or bool(values) and all(x['status']=='PASS' for x in values))
 rows.append(dict(test=name,status='PASS' if ok else 'FAIL',exitCode=result.returncode,seconds=round(time.monotonic()-started,3),cases=len(values) if values else None))
with EXE.open('rb') as f:exe_hash=hashlib.file_digest(f,'sha256').hexdigest()
record=dict(status='PASS' if all(x['status']=='PASS' for x in rows) else 'FAIL',exe=str(EXE),exeSha256=exe_hash,
    cwd=str(ROOT/'test_resources'),externalJavaHome=env['JAVA_HOME'],rows=rows)
(RUN/'reports/packaged-acceptance.json').write_text(json.dumps(record,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(record,ensure_ascii=False,indent=2))
raise SystemExit(0 if record['status']=='PASS' else 1)

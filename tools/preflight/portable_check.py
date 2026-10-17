"""Run the generated EXE with no usable external Java and from another CWD."""
import json, os, shutil, subprocess, time
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
RUN=ROOT/'validation'/'2026-10-17'
source=RUN/'build-probe'/'build'/'compose'/'binaries'/'main'/'app'/'ErgouPreflight'
target=RUN/'work'/('portable-moved-中文 空格-'+time.strftime('%H%M%S'))/'ErgouPreflight'
assert target.resolve().is_relative_to((RUN/'work').resolve())
shutil.copytree(source,target)
env=os.environ.copy();env['JAVA_HOME']=r'D:\no-external-java';env['PATH']=r'C:\Windows\System32'
exe=target/'ErgouPreflight.exe'
result=subprocess.run([str(exe),'--self-test'],cwd=ROOT/'test_resources',env=env,
    capture_output=True,timeout=90,creationflags=subprocess.CREATE_NO_WINDOW)
data=target/'ErgouCompressData'
native=list((data/'native').glob('*')) if (data/'native').exists() else []
report=dict(exitCode=result.returncode,exe=str(exe),cwd=str(ROOT/'test_resources'),
    dataRoot=str(data),nativeArtifacts=[p.name for p in native],javaHome=env['JAVA_HOME'],
    selfTestLog=(data/'self-test.log').read_text('utf-8') if (data/'self-test.log').exists() else None,
    bootstrap=(data/'bootstrap.log').read_text('utf-8') if (data/'bootstrap.log').exists() else None,
    stderr=result.stderr.decode('utf-8','replace'))
report['status']='PASS' if result.returncode==0 and report['selfTestLog'] and 'PASS' in report['selfTestLog'] else 'FAIL'
(RUN/'reports'/'portable-check.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(report,ensure_ascii=False,indent=2))

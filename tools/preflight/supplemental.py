"""Additional codec/remux/validation observations on copies; no source mutation."""
import json, sys
from pathlib import Path
import preflight as p

for directory in (p.LOG,p.WORK,p.REPORT): directory.mkdir(parents=True,exist_ok=True)
latest=json.loads((p.RUN/'reports'/'latest.json').read_text('utf-8'))
prior=Path(latest['work']); full=prior/'full-nvenc-copy.mp4'; sample=prior/'nvenc-1080p30-copy.mp4'

def packet_hash(name,path,stream):
    rc,out,err,meta=p.command(name,[p.FF,'-hide_banner','-nostdin','-v','error','-i',path,
        '-map',stream,'-c','copy','-f','hash','-hash','sha256','pipe:1'])
    if rc:raise RuntimeError(err.decode('utf-8','replace'))
    return out.decode().strip()

original_hash=p.digest(p.SOURCE)
source_audio=packet_hash('source-audio-packets',p.SOURCE,'0:a:0')
output_audio=packet_hash('full-output-audio-packets',full,'0:a:0')
p.record('full-audio-copy-packet-identity',source_audio==output_audio,source=source_audio,output=output_audio)

remux=p.WORK/'remux.mkv'
rc,out,err,meta=p.command('remux-mkv',[p.FF,'-hide_banner','-n','-nostdin','-v','error',
    '-i',p.SOURCE,'-map','0','-c','copy','-map_metadata','0','-map_chapters','0',remux])
info=p.probe('remux-probe',remux)
p.record('full-mkv-remux-packet-identity',rc==0 and len(info['streams'])==2
    and packet_hash('source-video-packets',p.SOURCE,'0:V:0')==packet_hash('remux-video-packets',remux,'0:V:0')
    and source_audio==packet_hash('remux-audio-packets',remux,'0:a:0'),**meta,bytes=remux.stat().st_size)

corrupt=p.WORK/'truncated.partial.mp4'
with full.open('rb') as src: corrupt.write_bytes(src.read(2*1024*1024))
rc,out,err,meta=p.command('truncated-decode',[p.FF,'-hide_banner','-nostdin','-v','error','-xerror','-err_detect','explode',
    '-i',corrupt,'-map','0:V?','-map','0:a?','-f','null','NUL'])
p.record('truncated-candidate-rejected',rc!=0,**meta,stderrTail=err.decode('utf-8','replace')[-600:])

graph='[0:V:0]trim=duration=12,setpts=PTS-STARTPTS[ref];[1:V:0]setpts=PTS-STARTPTS[dist];[dist][ref]libvmaf=model=version=vmaf_v0.6.1:log_fmt=json:log_path=vmaf-sample.json'
rc,out,err,meta=p.command('vmaf-sample',[p.FF,'-hide_banner','-nostdin','-v','error','-ss','60','-t','12','-i',p.SOURCE,
    '-i',sample,'-filter_complex',graph,'-an','-t','12','-f','null','NUL'],timeout=180)
metric=json.loads((p.WORK/'vmaf-sample.json').read_text()) if (p.WORK/'vmaf-sample.json').exists() else None
p.record('vmaf-filter-and-model-smoke',rc==0 and metric is not None,**meta,
    vmaf=metric.get('pooled_metrics',{}).get('vmaf') if metric else None,
    limitation='One 12-second SDR interval using explicitly selected v0.6.1; no whole-video or audio quality guarantee')

# The updated wrapper must ignore stale GetLastError when ReplaceFile returns success.
p.native_tests()
p.record('source-still-unchanged',original_hash==p.digest(p.SOURCE),sha256=original_hash)
p.save(p.RUN/'reports'/'supplemental-latest.json',dict(runId=p.RUN_ID,report=str(p.REPORT),work=str(p.WORK)))
if any(item['status'] != 'PASS' for item in p.RESULTS): raise SystemExit(1)

"""Recheck initial-seek effects without altering historical evidence or the source."""
import json
import preflight as p

for directory in (p.LOG, p.WORK, p.REPORT): directory.mkdir(parents=True, exist_ok=True)
p.SOURCE_INFO = p.probe('source-probe', p.SOURCE)
before = p.digest(p.SOURCE)

def packets(name, path):
    rc, out, err, meta = p.command(name, [p.FP, '-v', 'error', '-select_streams', 'a:0',
        '-show_packets', '-show_data_hash', 'sha256', '-show_entries',
        'packet=pts,dts,duration,size,data_hash,side_data_list', '-of', 'json', path])
    if rc: raise RuntimeError(err.decode('utf-8', 'replace'))
    value = json.loads(out)['packets']
    p.save(p.REPORT / (name + '.json'), value)
    return value

old = json.loads((p.RUN / 'reports' / 'latest.json').read_text('utf-8'))
old_file = p.Path(old['work']) / 'full-nvenc-copy.mp4'
source = packets('source-audio-packets', p.SOURCE)
prior = packets('prior-audio-packets', old_file)
source_hashes = [x['data_hash'] for x in source]
prior_hashes = [x['data_hash'] for x in prior]
p.record('seek-zero-audio-diagnosis', source_hashes[1:] == prior_hashes,
    sourcePackets=len(source), priorPackets=len(prior), sourceFirst=source[:2], priorFirst=prior[:2],
    explanation='Previous command used input -ss 0; the first source AAC packet is missing. Do not infer the decoder priming mechanism from this observation alone.')
full = p.encode('full-nvenc-no-seek', ['-c:v', 'hevc_nvenc', '-preset', 'p5', '-tune', 'hq',
    '-rc', 'vbr', '-cq', '28', '-b:v', '0', '-pix_fmt', 'yuv420p', '-fps_mode', 'passthrough',
    '-c:a', 'copy'], seconds=None, start=0)
current = packets('fixed-audio-packets', full)
p.record('no-seek-full-audio-payload-identity', source_hashes == [x['data_hash'] for x in current],
    sourcePackets=len(source), outputPackets=len(current), outputFirst=current[:2])
p.record('source-unchanged', before == p.digest(p.SOURCE), sha256=before)
p.save(p.RUN / 'reports' / 'refinements-latest.json', dict(runId=p.RUN_ID, report=str(p.REPORT), work=str(p.WORK)))
if any(x['status'] != 'PASS' for x in p.RESULTS): raise SystemExit(1)

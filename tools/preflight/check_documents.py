"""Check local documentation links, heading fragments, UTF-8 and requirements coverage."""
import argparse, json, re, unicodedata
from pathlib import Path
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parents[2]
DOCS = sorted((ROOT / 'docs').rglob('*.md'))
FILES = DOCS + [ROOT / 'README.md', ROOT / 'tools/preflight/README.md']
errors = []
links_checked = 0

def prose(value):
    return re.sub(r'(?ms)^```[^\n]*\n.*?^```[ \t]*$', '', value)

def anchors(value):
    result, counts = set(), {}
    for line in prose(value).splitlines():
        match = re.match(r'^#{1,6}\s+(.+?)\s*#*$', line)
        if not match: continue
        title = re.sub(r'\[([^]]+)\]\([^)]+\)', r'\1', match[1]).lower()
        title = ''.join(c for c in title if c.isspace() or c in '-_' or unicodedata.category(c)[0] in 'LN')
        base = re.sub(r'\s', '-', title)
        count = counts.get(base, 0); counts[base] = count + 1
        result.add(base + (f'-{count}' if count else ''))
    return result

for path in FILES:
    data = path.read_text('utf-8-sig')
    if '\ufffd' in data: errors.append(f'{path}: replacement character')
    if len(re.findall(r'^```', data, re.M)) % 2: errors.append(f'{path}: unpaired code fence')
    for match in re.finditer(r'\[[^\]\n]*\]\((<[^>]+>|[^)\s]+)(?:\s+"[^"]*")?\)', prose(data)):
        url = match[1].strip('<>')
        if re.match(r'^(https?|mailto|app|plugin|codex|data):', url, re.I): continue
        local, _, fragment = unquote(url).partition('#')
        target = (path.parent / local).resolve() if local else path
        links_checked += 1
        if not target.exists(): errors.append(f'{path.relative_to(ROOT)}: missing {url}')
        elif fragment and target.suffix == '.md' and fragment not in anchors(target.read_text('utf-8-sig')):
            errors.append(f'{path.relative_to(ROOT)}: missing fragment {url}')

requirements = (ROOT/'docs/function/01-product-requirements.md').read_text('utf-8')
trace = (ROOT/'docs/planning/04-requirements-traceability.md').read_text('utf-8')
for number in range(1, 21):
    key = f'FR-{number:03}'
    if key not in requirements or key not in trace: errors.append(f'{key}: missing requirement or trace')
report = dict(status='FAIL' if errors else 'PASS', docs=len(DOCS), checkedFiles=len(FILES),
    localLinksChecked=links_checked, requirements='FR-001..FR-020', errors=errors)
parser = argparse.ArgumentParser()
parser.add_argument('--report', type=Path, default=ROOT/'validation/2026-10-17/reports/document-check.json')
out = parser.parse_args().report
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(report, ensure_ascii=False, indent=2))
raise SystemExit(1 if errors else 0)

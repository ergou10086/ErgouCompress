"""Read Windows process integrity labels only; no UI or security setting changes."""
import ctypes as c, json
from ctypes import wintypes as w
from pathlib import Path
root = Path(__file__).resolve().parents[2] / 'validation/2026-10-17/reports'
k = c.WinDLL('kernel32', use_last_error=True)
a = c.WinDLL('advapi32', use_last_error=True)
k.OpenProcess.argtypes = [w.DWORD, w.BOOL, w.DWORD]; k.OpenProcess.restype = w.HANDLE
k.CloseHandle.argtypes = [w.HANDLE]
a.OpenProcessToken.argtypes = [w.HANDLE, w.DWORD, c.POINTER(w.HANDLE)]; a.OpenProcessToken.restype = w.BOOL
a.GetTokenInformation.argtypes = [w.HANDLE, c.c_int, w.LPVOID, w.DWORD, c.POINTER(w.DWORD)]
a.GetSidSubAuthorityCount.argtypes = [w.LPVOID]; a.GetSidSubAuthorityCount.restype = c.POINTER(w.BYTE)
a.GetSidSubAuthority.argtypes = [w.LPVOID, w.DWORD]; a.GetSidSubAuthority.restype = c.POINTER(w.DWORD)
class Label(c.Structure): _fields_ = [('sid', w.LPVOID), ('attributes', w.DWORD)]
results = []
for item in json.loads((root/'process-ids.json').read_text('utf-8-sig')):
    process = k.OpenProcess(0x1000, False, item['Id']); token = w.HANDLE()
    try:
        if not process or not a.OpenProcessToken(process, 8, c.byref(token)): raise c.WinError(c.get_last_error())
        size = w.DWORD(); a.GetTokenInformation(token, 25, None, 0, c.byref(size))
        buf = c.create_string_buffer(size.value)
        if not a.GetTokenInformation(token, 25, buf, size, c.byref(size)): raise c.WinError(c.get_last_error())
        sid = c.cast(buf, c.POINTER(Label)).contents.sid
        rid = a.GetSidSubAuthority(sid, a.GetSidSubAuthorityCount(sid)[0]-1)[0]
        item.update(integrityRid=rid, integrity={4096:'Low',8192:'Medium',12288:'High',16384:'System'}.get(rid,str(rid)))
    except OSError as ex: item['error'] = str(ex)
    finally:
        if token: k.CloseHandle(token)
        if process: k.CloseHandle(process)
    results.append(item)
(root/'process-integrity.json').write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(results, ensure_ascii=False, indent=2))

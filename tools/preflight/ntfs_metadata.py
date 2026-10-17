"""Real NTFS ADS/security/timestamp tests on small generated files only."""
import ctypes as c, json, os, time, traceback
from ctypes import wintypes as w
import preflight as p

for folder in (p.LOG,p.WORK,p.REPORT):folder.mkdir(parents=True,exist_ok=True)
k=p.kernel();a=c.WinDLL('advapi32',use_last_error=True)
a.GetFileSecurityW.argtypes=[w.LPCWSTR,w.DWORD,w.LPVOID,w.DWORD,c.POINTER(w.DWORD)]
a.SetFileSecurityW.argtypes=[w.LPCWSTR,w.DWORD,w.LPVOID]
a.ConvertStringSecurityDescriptorToSecurityDescriptorW.argtypes=[w.LPCWSTR,w.DWORD,c.POINTER(w.LPVOID),c.POINTER(w.DWORD)]
a.OpenProcessToken.argtypes=[w.HANDLE,w.DWORD,c.POINTER(w.HANDLE)]
a.GetTokenInformation.argtypes=[w.HANDLE,c.c_int,w.LPVOID,w.DWORD,c.POINTER(w.DWORD)]
a.ConvertSidToStringSidW.argtypes=[w.LPVOID,c.POINTER(w.LPWSTR)]
a.GetSecurityDescriptorDacl.argtypes=[w.LPVOID,c.POINTER(w.BOOL),c.POINTER(w.LPVOID),c.POINTER(w.BOOL)]
a.GetSecurityDescriptorControl.argtypes=[w.LPVOID,c.POINTER(w.WORD),c.POINTER(w.DWORD)]
k.GetCurrentProcess.restype=w.HANDLE;k.LocalFree.argtypes=[w.LPVOID]

def security(file):
    size=w.DWORD();a.GetFileSecurityW(str(file),4,None,0,c.byref(size))
    buf=c.create_string_buffer(size.value)
    if not a.GetFileSecurityW(str(file),4,buf,size,c.byref(size)):raise c.WinError(c.get_last_error())
    return bytes(buf[:size.value])

def current_sid():
    token=w.HANDLE();assert a.OpenProcessToken(k.GetCurrentProcess(),8,c.byref(token))
    try:
        size=w.DWORD();a.GetTokenInformation(token,1,None,0,c.byref(size));buf=c.create_string_buffer(size.value)
        assert a.GetTokenInformation(token,1,buf,size,c.byref(size))
        sid=c.cast(buf,c.POINTER(w.LPVOID))[0];text=w.LPWSTR();assert a.ConvertSidToStringSidW(sid,c.byref(text))
        try:return text.value
        finally:k.LocalFree(c.cast(text,w.LPVOID))
    finally:k.CloseHandle(token)

def case(name,callback):
    try:details=callback();p.record(name,True,**details)
    except Exception as ex:p.record(name,False,error=repr(ex),traceback=traceback.format_exc())

def security_facts(raw):
    buf=c.create_string_buffer(raw);present=w.BOOL();defaulted=w.BOOL();acl=w.LPVOID();control=w.WORD();revision=w.DWORD()
    assert a.GetSecurityDescriptorDacl(buf,c.byref(present),c.byref(acl),c.byref(defaulted))
    assert a.GetSecurityDescriptorControl(buf,c.byref(control),c.byref(revision))
    size=c.cast(acl.value+2,c.POINTER(w.WORD))[0] if acl.value else 0
    return dict(dacl=c.string_at(acl,size).hex() if size else None,protected=bool(control.value&0x1000))

def apply_security(file,raw):
    facts=security_facts(raw);buf=c.create_string_buffer(raw)
    flags=4|(0x80000000 if facts['protected'] else 0x20000000)
    if not a.SetFileSecurityW(str(file),flags,buf):raise c.WinError(c.get_last_error())

def ads_security():
    folder=p.WORK/'ntfs-metadata';folder.mkdir()
    source=folder/'中文 original.mp4';candidate=folder/'candidate.mp4';backup=folder/'original.backup'
    source.write_bytes(b'original'*1000);candidate.write_bytes(b'encoded'*100)
    p.Path(str(source)+':ergou-note').write_bytes(b'original named stream')
    p.Path(str(candidate)+':ergou-new').write_bytes(b'candidate named stream')
    # A protected DACL differentiates the source from the inherited candidate ACL.
    descriptor=w.LPVOID();size=w.DWORD();sddl=f'D:P(A;;FA;;;{current_sid()})(A;;FA;;;SY)'
    assert a.ConvertStringSecurityDescriptorToSecurityDescriptorW(sddl,1,c.byref(descriptor),c.byref(size))
    try:assert a.SetFileSecurityW(str(source),4|0x80000000,descriptor)
    finally:k.LocalFree(descriptor)
    before=security(source);assert before!=security(candidate)
    creation_before=os.stat(source).st_birthtime_ns
    before_hash=p.digest(source);candidate_hash=p.digest(candidate)
    ok,error=p.replace(source,candidate,backup);assert ok,(ok,error)
    assert p.digest(source)==candidate_hash and p.digest(backup)==before_hash
    assert p.Path(str(source)+':ergou-note').read_bytes()==b'original named stream'
    assert p.Path(str(source)+':ergou-new').read_bytes()==b'candidate named stream'
    assert p.Path(str(backup)+':ergou-note').read_bytes()==b'original named stream'
    after_native=security(source)
    # ReplaceFile merges security. Strict policy restores the captured DACL/protection
    # and verifies semantic ACL data, rather than assuming descriptor byte identity.
    apply_security(source,before)
    assert security_facts(security(source))==security_facts(before), 'explicit security policy differs'
    assert os.stat(source).st_birthtime_ns==creation_before, 'creation time differs'
    return dict(adsMerged=True,backupAds=True,explicitDaclPolicyVerified=True,creationTimePreserved=True,win32Error=error,
        nativeDescriptorIdentical=after_native==before,nativeDaclMatches=security_facts(after_native)==security_facts(before))

def preapply_security():
    folder=p.WORK/'preapply-security';folder.mkdir();source=folder/'source.bin';candidate=folder/'candidate.bin';backup=folder/'backup.bin'
    source.write_bytes(b'original'*100);candidate.write_bytes(b'new'*10)
    raw=security(source);apply_security(candidate,raw)
    ok,error=p.replace(source,candidate,backup);assert ok
    assert security_facts(security(source))==security_facts(raw)
    return dict(preappliedCandidateDacl=True,postverifiedSourceDacl=True,win32Error=error)

def timestamp_policy():
    folder=p.WORK/'timestamp-policy';folder.mkdir()
    source=folder/'original.bin';candidate=folder/'candidate.bin';backup=folder/'original.backup'
    source.write_bytes(b'old'*300);candidate.write_bytes(b'new'*30)
    os.utime(source,ns=(1_600_000_000_000_000_000,1_600_000_000_000_000_000))
    expected=source.stat().st_mtime_ns;digest=p.digest(candidate)
    ok,error=p.replace(source,candidate,backup);assert ok
    native_mtime=source.stat().st_mtime_ns
    os.utime(source,ns=(source.stat().st_atime_ns,expected))
    assert source.stat().st_mtime_ns==expected and p.digest(source)==digest
    return dict(originalMtimeNs=expected,nativeMtimeNs=native_mtime,explicitRestoreVerified=True)

case('ntfs-ads-and-explicit-dacl-policy',ads_security)
case('candidate-dacl-preapply-and-postverify',preapply_security)
case('mtime-policy-restores-without-content-change',timestamp_policy)
# Repeat genuine locking and cross-volume tests; no FFmpeg encode is performed.
p.native_tests()
p.save(p.RUN/'reports'/'ntfs-metadata-acceptance.json',p.RESULTS)
p.save(p.RUN/'reports'/'ntfs-metadata-latest.json',dict(runId=p.RUN_ID,report=str(p.REPORT),work=str(p.WORK)))
raise SystemExit(1 if any(x['status']!='PASS' for x in p.RESULTS) else 0)

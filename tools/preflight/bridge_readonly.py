"""Read only the dedicated test app through Oracle JAB; never send UI input/actions."""
import ctypes as c, hashlib, json, os, subprocess, time
from ctypes import wintypes as w
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RUN = ROOT/'validation/2026-10-17'
WORK = RUN/'work/s0-acceptance/bridge'
REPORT = RUN/'reports/bridge-readonly.json'
READY = WORK/'ready.json'
JDK = Path(r'D:\OtherLanguageSetting\Gradle\gradle_repository\jdks\azul_systems__inc_-21-amd64-windows.2')
EXE = RUN/'build-probe/build/compose/binaries/main/app/ErgouPreflight/ErgouPreflight.exe'
WORK.mkdir(parents=True, exist_ok=True)
for file in (READY, WORK/'done'):
    if file.exists(): file.unlink()
global_settings = Path(r'C:\Users\wind\.accessibility.properties')
def settings_hash():
    return hashlib.sha256(global_settings.read_bytes()).hexdigest() if global_settings.exists() else None
before = settings_hash()
client_dll_path = JDK/'bin/windowsaccessbridge-64.dll'
dll_directory = os.add_dll_directory(str(JDK/'bin'))
bridge = c.WinDLL(str(client_dll_path))
obj = c.c_longlong
class Context(c.Structure):
    _fields_ = [('name', c.c_wchar*1024), ('description', c.c_wchar*1024),
        ('role', c.c_wchar*256), ('role_en', c.c_wchar*256), ('states', c.c_wchar*256), ('states_en', c.c_wchar*256),
        ('index', c.c_int), ('children', c.c_int), ('x', c.c_int), ('y', c.c_int), ('width', c.c_int), ('height', c.c_int),
        ('component', w.BOOL), ('action', w.BOOL), ('selection', w.BOOL), ('text', w.BOOL), ('interfaces', w.BOOL)]
class Action(c.Structure): _fields_ = [('name', c.c_wchar*256)]
class Actions(c.Structure): _fields_ = [('count', c.c_int), ('actions', Action*256)]
class TextInfo(c.Structure): _fields_ = [('count',c.c_int),('caret',c.c_int),('atPoint',c.c_int)]

def api(name, args, result):
    method = getattr(bridge,name); method.argtypes=args; method.restype=result; return method
start = api('Windows_run',[],None)
is_java = api('isJavaWindow',[w.HWND],w.BOOL)
get_root = api('getAccessibleContextFromHWND',[w.HWND,c.POINTER(w.LONG),c.POINTER(obj)],w.BOOL)
get_info = api('getAccessibleContextInfo',[w.LONG,obj,c.POINTER(Context)],w.BOOL)
child = api('getAccessibleChildFromContext',[w.LONG,obj,c.c_int],obj)
release = api('releaseJavaObject',[w.LONG,obj],None)
get_actions = api('getAccessibleActions',[w.LONG,obj,c.POINTER(Actions)],w.BOOL)
text_info = api('getAccessibleTextInfo',[w.LONG,obj,c.POINTER(TextInfo),c.c_int,c.c_int],w.BOOL)
text_range = api('getAccessibleTextRange',[w.LONG,obj,c.c_int,c.c_int,w.LPWSTR,c.c_short],w.BOOL)
user = c.WinDLL('user32')
user.PeekMessageW.argtypes=[c.POINTER(w.MSG),w.HWND,w.UINT,w.UINT,w.UINT]
user.DispatchMessageW.argtypes=[c.POINTER(w.MSG)]
def pump():
    message=w.MSG()
    while user.PeekMessageW(c.byref(message),None,0,0,1):
        user.TranslateMessage(c.byref(message));user.DispatchMessageW(c.byref(message))

tree=[];process=None;errors=[]
start()
out=(WORK/'server.stdout.txt').open('wb');err=(WORK/'server.stderr.txt').open('wb')
try:
    process=subprocess.Popen([str(EXE),'--bridge-server',str(WORK),str(READY)],
        stdout=out,stderr=err,creationflags=subprocess.CREATE_NO_WINDOW)
    deadline=time.monotonic()+35
    while not READY.exists() and time.monotonic()<deadline:
        pump();time.sleep(.05)
        if process.poll() is not None:raise RuntimeError('Bridge server exited before ready')
    if not READY.exists():raise RuntimeError('No server ready record')
    ready=json.loads(READY.read_text('utf-8'));hwnd=int(ready['hwnd'])
    vm=w.LONG();context=obj()
    while time.monotonic()<deadline:
        pump()
        if is_java(hwnd) and get_root(hwnd,c.byref(vm),c.byref(context)):break
        time.sleep(.1)
    else:raise RuntimeError('JAB did not expose dedicated server HWND')
    def walk(handle, depth=0):
        try:
            info=Context()
            if not get_info(vm,handle,c.byref(info)):raise RuntimeError('Cannot read accessible context')
            row=dict(depth=depth,name=info.name,description=info.description,role=info.role_en,states=info.states_en,
                children=info.children,action=bool(info.action),text=bool(info.text),width=info.width,height=info.height)
            if info.action:
                actions=Actions()
                if get_actions(vm,handle,c.byref(actions)):row['actions']=[actions.actions[i].name for i in range(min(actions.count,256))]
            if info.text:
                count=TextInfo()
                if text_info(vm,handle,c.byref(count),info.x,info.y) and 0<count.count<4096:
                    text=c.create_unicode_buffer(count.count+1)
                    if text_range(vm,handle,0,count.count-1,text,count.count+1):row['value']=text.value
            tree.append(row)
            if depth>20 or len(tree)>300:raise RuntimeError('Unexpected unbounded accessibility tree')
            for index in range(max(0,min(info.children,100))):
                value=child(vm,handle,index)
                if value:walk(value,depth+1)
        finally:release(vm,handle)
    walk(context)
    assert any(x['name']=='输出帧率输入框' and x['role']=='text' and x.get('value')=='30' for x in tree), 'Missing named editable FPS text'
    assert any(x['name']=='输出帧率帮助' and x['role']=='push button' and x.get('actions') for x in tree), 'Missing actionable named help button'
    assert any(x['name']=='文件导入区域' for x in tree), 'Missing drop target accessible name'
except Exception as ex:errors.append(str(ex))
finally:
    (WORK/'done').write_text('done',encoding='utf-8')
    if process:
        shutdown_deadline=time.monotonic()+8
        while process.poll() is None and time.monotonic()<shutdown_deadline:
            pump();time.sleep(.05)
        if process.poll() is None:process.kill();process.wait();errors.append('Server timeout; terminated only this test process')
    out.close();err.close()
    after=settings_hash()
    if before!=after:errors.append('Global accessibility settings changed')
    report=dict(status='FAIL' if errors else 'PASS',layer='external read-only Windows JAB client / packaged EXE',
        globalSettingsBefore=before,globalSettingsAfter=after,contextBytes=c.sizeof(Context),tree=tree,errors=errors,
        serverStderr=(WORK/'server.stderr.txt').read_text('utf-8',errors='replace'))
    REPORT.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(dict(status=report['status'],nodes=len(tree),errors=errors),ensure_ascii=False))
raise SystemExit(1 if errors else 0)

import subprocess,json,urllib.request,urllib.error,re,time,sys
import os,shutil
ADB=os.environ.get('ADB') or shutil.which('adb') or 'adb'; PHONE=os.environ['BRIDGIT_TEST_PHONE'];DEV=os.environ.get('BRIDGIT_TEST_ADB',PHONE+':5555'); PKG='net.finleg.bridget.planned'; BASE=f'http://{PHONE}:8736/v1'
def adb(*args):
 return subprocess.run([ADB,'-s',DEV,*args],capture_output=True,text=True,timeout=15).stdout
if len(sys.argv) == 2 and sys.argv[1] == '--isolated-review-app':
 PKG='net.finleg.bridget.review'; BASE=f'http://{PHONE}:8737/v1'
elif len(sys.argv) != 2 or sys.argv[1] != '--isolated-planned-app':
 print('Usage: python3 tools/planned_acceptance.py --isolated-planned-app or --isolated-review-app (dedicated test builds only, Pixel unlocked)');sys.exit(2)
name='PlannedTestA'
r=adb('shell','am','broadcast','--include-stopped-packages','-n',PKG+'/net.finleg.bridget.DebugSetupReceiver','-a','net.finleg.bridget.debug.SETUP','--es','agent',name,'--ez','phone','true','--ez','files','true','--ez','clipboard','true','--ez','sensors','true')
m=re.search(r'data="?(\d{6})',r)
if not m: print('BLOCKED: test hook did not open pairing:',r.strip());sys.exit(1)
def call(path,body=None,key=None,expected=200,method=None):
 data=None if body is None else json.dumps(body).encode(); headers={'Content-Type':'application/json'}
 if key: headers['Authorization']='Bearer '+key
 req=urllib.request.Request(BASE+path,data=data,headers=headers,method=method or ('POST' if body is not None else 'GET'))
 try:
  with urllib.request.urlopen(req,timeout=20) as response: status=response.status;raw=response.read();typ=response.headers.get('Content-Type','')
 except urllib.error.HTTPError as e: status=e.code;raw=e.read();typ=e.headers.get('Content-Type','')
 assert status==expected,(path,status,expected,raw[:300])
 return json.loads(raw) if 'json' in typ else raw
try:
 # The receiver starts the listener asynchronously; pairing must wait for its socket.
 deadline=time.monotonic()+15
 while True:
  try:
   urllib.request.urlopen(BASE+'/health',timeout=1).close();break
  except urllib.error.HTTPError:
   break # 401 means the authenticated API is listening.
  except (urllib.error.URLError,TimeoutError):
   if time.monotonic()>=deadline: raise RuntimeError('Listener did not become ready within 15 seconds')
   time.sleep(0.2)
 time.sleep(2) # Avoid accepting an old listener while a previous service stop is draining.
 paired=call('/pair',{'code':m.group(1)})
 key=paired.get('key') or paired.get('token');assert key
 passed=[]
 def check(label,f): f();passed.append(label);print('PASS',label,flush=True)
 check('health authentication',lambda:call('/health',key=key))
 check('missing key rejected',lambda:call('/phone/status',expected=401))
 check('phone status',lambda:call('/phone/status',key=key))
 check('phone context',lambda:call('/phone/context',key=key))
 check('sensors response',lambda:call('/sensors?which=light,pressure',key=key))
 check('sensor input validation',lambda:call('/sensors?which=invalid',key=key,expected=422))
 check('disabled OCR refused',lambda:call('/ocr',{},key=key,expected=403))
 check('disabled video refused',lambda:call('/camera/video',{},key=key,expected=403))
 payload={'name':'acceptance.txt','mime':'text/plain','data_base64':'QnJpZGdpdCBhY2NlcHRhbmNl'}
 item=call('/files',payload,key=key,expected=201);id=item['id']
 check('file exact-byte roundtrip',lambda: None if call('/files/'+id,key=key)==b'Bridgit acceptance' else (_ for _ in ()).throw(AssertionError('bytes')))
 check('filename traversal rejected',lambda:call('/files',dict(payload,name='../escape'),key=key,expected=422))
 check('file delete',lambda:call('/files/'+id,key=key,method='DELETE'))
 check('removed file not found',lambda:call('/files/'+id,key=key,expected=404))
 pending=call('/clipboard',{'text':'Bridgit acceptance text'},key=key,expected=202)
 check('clipboard waits for owner approval',lambda: None if call('/clipboard/'+pending['id'],key=key)['status']=='pending' else (_ for _ in ()).throw(AssertionError('not pending')))
 check('generated OpenAPI',lambda:call('/openapi.json',key=key))
 check('MCP discovery',lambda:call('/mcp',{'jsonrpc':'2.0','id':1,'method':'tools/list','params':{}},key=key))
 # A second paired agent must not be able to fetch the first agent's file.
 second=adb('shell','am','broadcast','-n',PKG+'/net.finleg.bridget.DebugSetupReceiver','-a','net.finleg.bridget.debug.SETUP','--es','agent','PlannedTestB')
 code2=re.search(r'data="?(\d{6})',second)
 assert code2,'Second pairing window missing'
 key2=call('/pair',{'code':code2.group(1)})['key']
 item=call('/files',payload,key=key,expected=201)
 check('file access isolated between agents',lambda:call('/files/'+item['id'],key=key2,expected=404))
 check('file delete isolated between agents',lambda:call('/files/'+item['id'],key=key2,method='DELETE',expected=404))
 call('/files/'+item['id'],key=key,method='DELETE')
 adb('shell','am','broadcast','-n',PKG+'/net.finleg.bridget.DebugSetupReceiver','-a','net.finleg.bridget.debug.SETUP','--es','caps',name+'=phone')
 check('HTTP enforces changed per-agent grants',lambda:call('/files',key=key,expected=403))
 rpc=call('/mcp',{'jsonrpc':'2.0','id':2,'method':'tools/list','params':{}},key=key)
 check('MCP hides ungranted tools',lambda:None if not any(t['name']=='files_list' for t in rpc['result']['tools']) else (_ for _ in ()).throw(AssertionError('files tool visible')))
 rpc=call('/mcp',{'jsonrpc':'2.0','id':3,'method':'tools/call','params':{'name':'files_list','arguments':{}}},key=key)
 check('MCP refuses direct call to ungranted tool',lambda:None if rpc.get('error') or rpc.get('result',{}).get('isError') else (_ for _ in ()).throw(AssertionError('MCP call allowed')))
 adb('shell','am','broadcast','-n',PKG+'/net.finleg.bridget.DebugSetupReceiver','-a','net.finleg.bridget.debug.SETUP','--es','caps',name+'=phone,files,clipboard,screen_share','--ez','screen_share','true')
 check('missing screen grant is actionable 403',lambda:call('/screen/share/capture',{},key=key,expected=403))
 adb('shell','am','broadcast','-n',PKG+'/net.finleg.bridget.DebugSetupReceiver','-a','net.finleg.bridget.debug.SETUP','--es','clipboard_decide',pending['id']+':deny')
 check('clipboard denial closes request',lambda:None if call('/clipboard/'+pending['id'],key=key)['status']=='denied' else (_ for _ in ()).throw(AssertionError('denial missing')))
 print('TOTAL',len(passed),'passed')
finally:
 adb('shell','am','broadcast','-n',PKG+'/net.finleg.bridget.DebugSetupReceiver','-a','net.finleg.bridget.debug.SETUP','--es','remove','PlannedTestB')
 print(adb('shell','am','broadcast','-n',PKG+'/net.finleg.bridget.DebugSetupReceiver','-a','net.finleg.bridget.debug.SETUP','--es','remove',name,'--ez','phone','false','--ez','files','false','--ez','clipboard','false','--ez','sensors','false','--ez','screen_share','false','--ez','listener','false').strip())

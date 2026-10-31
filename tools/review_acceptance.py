import subprocess,json,urllib.request,urllib.error,re,time,base64,sys
from pathlib import Path
import os,shutil
ADB=os.environ.get('ADB') or shutil.which('adb') or 'adb';PHONE=os.environ['BRIDGIT_TEST_PHONE'];DEV=os.environ.get('BRIDGIT_TEST_ADB',PHONE+':5555');PKG='net.finleg.bridget.review';base=f'http://{PHONE}:8737/v1';name='ReviewPositive'
def adb(*args):
 p=subprocess.run([ADB,'-s',DEV,*args],capture_output=True,text=True,timeout=25);assert p.returncode==0,p.stderr;return p.stdout
def hook(*args):return adb('shell','am','broadcast','--include-stopped-packages','-n',PKG+'/net.finleg.bridget.DebugSetupReceiver','-a','net.finleg.bridget.debug.SETUP',*args)
def call(path,body=None,expected=200,method=None,headers=None):
 h={'Content-Type':'application/json'};h.update(headers or {})
 if key:h['Authorization']='Bearer '+key
 req=urllib.request.Request(base+path,data=None if body is None else json.dumps(body).encode(),headers=h,method=method or ('POST' if body is not None else 'GET'))
 try:
  with urllib.request.urlopen(req,timeout=55) as r: status=r.status;raw=r.read();ct=r.headers.get('Content-Type','')
 except urllib.error.HTTPError as e:status=e.code;raw=e.read();ct=e.headers.get('Content-Type','')
 assert status==expected,(path,status,expected,raw[:200]);return json.loads(raw) if 'json' in ct else raw
if sys.argv[1:] != ['--isolated-review-app']:
 raise SystemExit('Use --isolated-review-app; never targets the release or ordinary debug package')
key=None
try:
 adb('shell','am','start','-n',PKG+'/net.finleg.bridget.ui.MainActivity')
 r=hook('--es','agent',name,'--ez','ocr','true','--ez','phone','true','--ez','files','true')
 code=re.search(r'data="?(\d{6})',r);assert code,'No pairing code'
 time.sleep(4);key=call('/pair',{'code':code.group(1)})['key']
 for fixture in ['ocr-small.png','ocr-wide.png']:
  encoded=base64.b64encode(Path(__file__).parent.joinpath('fixtures',fixture).read_bytes()).decode()
  o=call('/ocr',{'data_base64':encoded});assert 'BRIDGIT' in o['text'].upper() and '2026' in o['text'],o
  print('PASS positive on-device OCR',fixture,flush=True)
 payload={'name':'roundtrip.pdf','mime':'application/pdf','data_base64':base64.b64encode(b'%PDF-test-Bridgit').decode()}
 file=call('/files',payload,201)
 rpc=call('/mcp',{'jsonrpc':'2.0','id':1,'method':'tools/call','params':{'name':'files_get','arguments':{'id':file['id']}}})
 res=rpc['result']['content'][0]['resource'];assert res['mimeType']=='application/pdf' and base64.b64decode(res['blob'])==b'%PDF-test-Bridgit'
 print('PASS MCP binary PDF exact bytes',flush=True)
 call('/files',dict(payload,name='malicious.apk'),422)
 print('PASS APK transfer rejected',flush=True)
 call('/files/'+file['id'],method='DELETE')
 # This safe write must replay after rebuilding the listener rather than creating a second file.
 headers={'Idempotency-Key':'review-rebind-file-20261029'}
 first=call('/files',dict(payload,name='rebind.txt',mime='text/plain'),201,headers=headers)
 hook('--ei','port','8738');base=f'http://{PHONE}:8738/v1'
 for _ in range(40):
  try: call('/health');break
  except urllib.error.URLError: time.sleep(.5)
 replay=call('/files',dict(payload,name='rebind.txt',mime='text/plain'),201,headers=headers)
 assert first['id']==replay['id'],(first['id'],replay['id'])
 print('PASS idempotency replay survives real listener port rebind',flush=True)
 call('/files/'+first['id'],method='DELETE')
finally:
 hook('--ei','port','8737','--es','remove',name,'--ez','ocr','false','--ez','phone','false','--ez','files','false','--ez','listener','false')

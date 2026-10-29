#!/usr/bin/env python3
"""Exercise a dedicated .agentictest APK on a selected USB device; no real messages or sensor recording.
Requires an owner-authorized local debug build with ControlFixtureActivity and the DUMP-only hook.
Credentials stay in process memory. Existing production/debug packages and pairings are untouched.
"""
import argparse,base64,http.client,importlib.machinery,importlib.util,io,json,os,pathlib,re,subprocess,time,uuid
p=argparse.ArgumentParser();p.add_argument('--device',required=True);p.add_argument('--apk',required=True);p.add_argument('--report',required=True);p.add_argument('--host',default='100.94.27.79');a=p.parse_args()
root=pathlib.Path(__file__).resolve().parents[2]
loader=importlib.machinery.SourceFileLoader('bridgit_transport',str(root/'tools/bridgit')); spec=importlib.util.spec_from_loader(loader.name,loader); transport=importlib.util.module_from_spec(spec);loader.exec_module(transport)
pkg='net.finleg.bridget.agentictest'; checks=[]
def adb(*args,check=True):
 r=subprocess.run(['adb','-s',a.device,*args],capture_output=True,text=True,timeout=90)
 if check and r.returncode: raise AssertionError('ADB failed: '+r.stderr[:200])
 return r.stdout

def hook(**extras):
 args=['shell','am','broadcast','-n',pkg+'/net.finleg.bridget.DebugSetupReceiver','-a','net.finleg.bridget.debug.SETUP']
 for key,value in extras.items(): args.extend(['--es',key,str(value)])
 return adb(*args)
class MemorySocket:
 def __init__(self,data): self.data=data
 def makefile(self,*args,**kwargs): return io.BytesIO(self.data)
def request(token,method,path,body=None,key=None):
 data=json.dumps(body,separators=(',',':')).encode() if body is not None else b''
 idem='Idempotency-Key: '+(key or str(uuid.uuid4()))+'\r\n' if method=='POST' else ''
 req=(f'{method} {path} HTTP/1.1\r\nHost: {a.host}:8740\r\nAuthorization: Bearer {token}\r\nContent-Type: application/json\r\nContent-Length: {len(data)}\r\n{idem}Connection: close\r\n\r\n').encode()+data
 raw=transport.send({},req,a.host,8740); response=http.client.HTTPResponse(MemorySocket(raw));response.begin(); payload=response.read()
 return response.status,json.loads(payload)
def call(token,name,args=None,key=None,expect=200):
 status,result=request(token,'POST','/v2/call',{'tool':name,'arguments':args or {}},key)
 assert status==expect,(name,status,{k:v for k,v in result.items() if k in ('error','message')})
 return result
def finished(token,op):
 end=time.monotonic()+25
 while time.monotonic()<end:
  status,result=request(token,'GET','/v2/operations/'+op['id'])
  assert status==200
  if result['status'] in ('completed','failed','cancelled','unknown_outcome'): return result
  time.sleep(.15)
 raise AssertionError('Operation did not reach terminal state')
def success(token,op):
 result=finished(token,op); assert result['status']=='completed',(result['status'],result.get('error'),result.get('message')); return result['result']
def paired(name):
 result=hook(agent=name)
 match=re.search(r'data="?(\d{6})',result)
 assert match,'Pairing hook did not return a code'
 for attempt in range(30):
  try:
   status,response=request('unused','POST','/v1/pair',{'code':match.group(1)});break
  except ConnectionRefusedError:
   if attempt==29:raise
   time.sleep(.3)
 assert status==200
 return response.get('key') or response.get('token')
def record(label): checks.append(label);print('PASS:',label,flush=True)
previous=adb('shell','settings','get','secure','enabled_accessibility_services').strip()
previous_enabled=adb('shell','settings','get','secure','accessibility_enabled').strip()
component=pkg+'/net.finleg.bridget.platform.DeviceControlService'
try:
 assert pkg in subprocess.check_output([str(pathlib.Path.home()/'Library/Android/sdk/build-tools/35.0.0/aapt'),'dump','badging',a.apk],text=True).splitlines()[0]
 adb('install','-r',a.apk); adb('shell','pm','grant',pkg,'android.permission.POST_NOTIFICATIONS')
 adb('shell','am','start','-n',pkg+'/net.finleg.bridget.DebugControlCenter');time.sleep(1)
 token=paired('AgenticSmoke')
 scopes='diagnostics,interactions,events.read,files,workflows,intents,screen.observe,screen.control,images.ocr,images.barcode'
 hook(platform_grants='AgenticSmoke:'+scopes)
 status,health=request(token,'GET','/v1/health');assert status==200
 record('Installed isolated test package and preserved v1 health')
 status,cap=request(token,'GET','/v2/capabilities');assert status==200 and cap['mode']=='private';assert len(cap['capabilities'])>=24
 record('Device-specific v2 readiness and scoped capability discovery')
 status,legacy=request(token,'GET','/v1/capabilities');assert status==200
 status,result=request('invalid','GET','/v2/capabilities');assert status==401
 record('Legacy discovery retained and invalid keys refused')
 status,rpc=request(token,'POST','/mcp',{'jsonrpc':'2.0','id':1,'method':'initialize','params':{'protocolVersion':'2025-06-18','capabilities':{},'clientInfo':{'name':'fixture','version':'1'}}});assert status==200 and rpc['result']['protocolVersion']=='2025-06-18'
 status,rpc=request(token,'POST','/mcp',{'jsonrpc':'2.0','id':2,'method':'tools/list'});assert len(rpc['result']['tools'])>=40
 record('Stateless MCP initialize and tool discovery')
 key=str(uuid.uuid4()); args={'kind':'question','title':'Choose fixture value','text':'Synthetic test only','choices':['A','B']}
 one=call(token,'interaction.create',args,key);two=call(token,'interaction.create',args,key);assert one['id']==two['id']
 bad=call(token,'interaction.create',dict(args,title='Changed'),key,409);assert bad['error']=='idempotency_conflict'
 question=success(token,one);hook(platform_answer=question['id']+':A');answer=call(token,'interaction.get',{'id':question['id']});assert answer['answer']=='A'
 events=call(token,'events',{'after':0});assert 'Synthetic test only' not in json.dumps(events)
 record('Durable idempotency, conflict refusal, phone answers and redacted events')
 file=success(token,call(token,'file.put',{'name':'fixture.txt','mime':'text/plain','data_base64':base64.b64encode(b'Synthetic fixture content').decode()}))
 fetched=call(token,'file.get',{'id':file['id']});assert base64.b64decode(fetched['data_base64'])==b'Synthetic fixture content'
 beta=paired('IsolationSmoke');hook(platform_grants='IsolationSmoke:diagnostics,files')
 call(beta,'file.get',{'id':file['id']},expect=404);assert request(beta,'GET','/v2/operations/'+one['id'])[0]==404
 record('File transfer and cross-agent resource isolation')
 definition={'name':'Synthetic local panel','trigger':{'type':'button'},'steps':[{'type':'event','text':'fixture'}],'ui':{'template':'button','title':'Run fixture','text':'Local event only'}}
 assert call(token,'workflow.validate',definition)['valid'];call(token,'workflow.validate',dict(definition,steps=[{'type':'shell'}]),expect=422)
 workflow=success(token,call(token,'workflow.propose',definition));hook(platform_workflow_activate=workflow['id']);ran=success(token,call(token,'workflow.run',{'id':workflow['id']}));assert ran['status']=='completed'
 record('Approved bounded local workflow, native template and code rejection')
 enabled=[x for x in previous.split(':') if x and x!='null'] if previous_enabled=='1' else [];enabled.append(component)
 adb('shell','settings','put','secure','enabled_accessibility_services',':'.join(dict.fromkeys(enabled)));adb('shell','settings','put','secure','accessibility_enabled','1');time.sleep(2)
 adb('shell','am','start','-n',pkg+'/net.finleg.bridget.ControlFixtureActivity');time.sleep(.6)
 session=success(token,call(token,'session.request',{'apps':[pkg],'purpose':'Reversible local form fixture','duration_seconds':300}));hook(platform_session_approve=session['id']);time.sleep(.4)
 observation=call(token,'screen.observe',{'session_id':session['id'],'screenshot':False})
 def nodes(n):
  yield n
  for child in n.get('children',[]):yield from nodes(child)
 field=next(n for n in nodes(observation['tree']) if n.get('editable'))
 action={'session_id':session['id'],'fence':observation['fence'],'observation_id':observation['observation_id'],'type':'set_text','ref':field['ref'],'text':'42 Test Street','expect':{'text':'42 Test Street'}}
 op=call(token,'ui.action',action);assert op['status']=='awaiting_owner';hook(platform_operation_approve=op['id']);result=success(token,op);assert result['dispatched'] and result['effect_observed']
 record('Owner-approved semantic text entry with observed postcondition')
 call(token,'ui.action',action,expect=409)
 observation=call(token,'screen.observe',{'session_id':session['id'],'screenshot':False});button=next(n for n in nodes(observation['tree']) if n.get('label','').casefold()=='save draft locally')
 action={'session_id':session['id'],'fence':observation['fence'],'observation_id':observation['observation_id'],'type':'tap','ref':button['ref'],'expect':{'text':'Saved locally: 42 Test Street'}}
 op=call(token,'ui.action',action);hook(platform_operation_approve=op['id']);result=success(token,op);assert result['effect_observed']
 record('Stale-target refusal and verified local form save')
 observation=call(token,'screen.observe',{'session_id':session['id'],'screenshot':True});assert observation['image']['width_px']>0 and len(base64.b64decode(observation['image']['data_base64']))>1000
 record('Fresh screenshot of isolated fixture through the private capture service')
 ocr=call(token,'images.ocr',{'image_base64':observation['image']['data_base64']});assert 'control test' in ocr['text'].lower()
 record('On-device OCR reads the synthetic fixture without a cloud fallback')
 observed=call(token,'screen.observe',{'session_id':session['id'],'screenshot':False})
 label=next(n['label'] for n in nodes(observed['tree']) if n.get('label','').casefold()=='save draft locally')
 sequence={'session_id':session['id'],'fence':observed['fence'],'steps':[{'type':'set_text','package':pkg,'label':'Delivery address','text':'84 Example Avenue','expect':{'text':'84 Example Avenue'}},{'type':'tap','package':pkg,'label':label,'expect':{'text':'Saved locally: 84 Example Avenue'}}]}
 op=call(token,'ui.sequence',sequence);hook(platform_operation_approve=op['id']);result=success(token,op);assert result['completed_steps']==2
 record('One approved bounded sequence verifies both local form steps')
 stopped=success(token,call(token,'session.stop',{'id':session['id']}));assert stopped['status']=='stopped'
 call(token,'screen.observe',{'session_id':session['id'],'screenshot':False},expect=409)
 record('Stopping the session invalidates further control')
 adb('shell','am','start','-n',pkg+'/net.finleg.bridget.DebugControlCenter');time.sleep(.5)
 crashes=adb('shell','dumpsys','activity','exit-info',pkg)
 assert 'REASON_CRASH' not in crashes
 record('Owner control center opens after the complete flow without a recorded crash')
finally:
 current=adb('shell','settings','get','secure','enabled_accessibility_services',check=False).strip()
 kept=[s for s in current.split(':') if s and s!='null' and s!=component]
 adb('shell','settings','put','secure','enabled_accessibility_services',':'.join(kept),check=False)
 if not kept or set(kept)==set(x for x in previous.split(':') if x and x!='null'):
  adb('shell','settings','put','secure','enabled_accessibility_services',previous if previous!='null' else '',check=False)
  adb('shell','settings','put','secure','accessibility_enabled',previous_enabled if previous_enabled in ('0','1') else '0',check=False)
 pathlib.Path(a.report).parent.mkdir(parents=True,exist_ok=True)
 pathlib.Path(a.report).write_text(json.dumps({'device':a.device,'package':pkg,'checks':checks,'passed':len(checks)},indent=2)+'\n')
print('Completed',len(checks),'device checks; production package was not modified.',flush=True)

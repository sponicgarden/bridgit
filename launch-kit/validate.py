from pathlib import Path
from html.parser import HTMLParser
from urllib.parse import urlsplit,unquote
import json
ROOT=Path(__file__).resolve().parent
SITE=ROOT/'site/dist'
class Page(HTMLParser):
 def __init__(self,text):
  super().__init__();self.refs=[];self.ids=set();self.h1=0;self.lang=False;self.feed(text)
 def handle_starttag(self,t,attrs):
  a=dict(attrs)
  if 'id' in a:self.ids.add(a['id'])
  if t=='h1':self.h1+=1
  if t=='html':self.lang=a.get('lang')=='en'
  for k in ['href','src']:
   if k in a:self.refs.append(a[k])
parsed={p:Page(p.read_text()) for p in SITE.rglob('*.html')}
errors=[];refs=0
for p,doc in parsed.items():
 if doc.h1!=1 or not doc.lang:errors.append(f'{p}: heading/lang')
 for ref in doc.refs:
  u=urlsplit(ref)
  if u.scheme or u.netloc:continue
  target=SITE/unquote(u.path).lstrip('/') if u.path.startswith('/') else p.parent/unquote(u.path)
  if not u.path:target=p
  if target.is_dir():target=target/'index.html'
  if not target.is_file():errors.append(f'{p}: missing {ref}')
  if u.fragment and target in parsed and u.fragment not in parsed[target].ids:errors.append(f'{p}: missing anchor {ref}')
  refs+=1
for p in (ROOT/'play-store').glob('listing-*.json'):
 d=json.loads(p.read_text());lengths={k:len(d[k]) for k in ['title','short_description','full_description']}
 for k,maximum in [('title',30),('short_description',80),('full_description',4000)]:
  if lengths[k]>maximum:errors.append(f'{p}: {k} too long')
 print(p.name,lengths)
from PIL import Image
for name,dimensions,mode in [('play-icon-512.png',(512,512),'RGBA'),('play-feature-1024x500.png',(1024,500),'RGB'),('social-card-1200x630.png',(1200,630),'RGB')]:
 p=ROOT/'brand'/name
 with Image.open(p) as im:
  if im.size!=dimensions or im.mode!=mode:errors.append(f'{name}: dimensions/mode')
 if name=='play-icon-512.png' and p.stat().st_size>1024*1024:errors.append('icon too large')
 print(name,dimensions,mode)
print(f'{len(parsed)} HTML pages; {refs} local references checked')
if errors:raise SystemExit('\n'.join(errors))
print('PASS: links, anchors, page headings, language, listing limits and brand asset formats')

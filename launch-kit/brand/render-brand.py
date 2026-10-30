from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
import shutil
root=Path(__file__).resolve().parents[1];b=root/'brand';assets=root/'site/dist/assets'
ink='#142C32';paper='#F5F2E9';lime='#D8F36A';teal='#1B6A64';orange='#E58D57'
def font(size,serif=False,bold=False):return ImageFont.truetype('/System/Library/Fonts/Supplemental/'+('Georgia.ttf' if serif else ('Arial Bold.ttf' if bold else 'Arial.ttf')),size)
def mark(im,x,y,s,color=ink):
 d=ImageDraw.Draw(im);scale=s/100;w=max(1,round(9*scale))
 def pt(a,z):return(round(x+a*scale),round(y+z*scale))
 for xx in (24,76):
  d.line([pt(xx,39),pt(xx,75)],fill=color,width=w)
  for yy in (39,75):
   a,z=pt(xx,yy);rad=w/2;d.ellipse((a-rad,z-rad,a+rad,z+rad),fill=color)
 pts=[]
 for i in range(101):
  t=i/100;u=1-t;xx=u**3*24+3*u*u*t*34+3*u*t*t*66+t**3*76;yy=u**3*64+3*u*u*t*38+3*u*t*t*38+t**3*64;pts.append(pt(xx,yy))
 d.line(pts,fill=color,width=w,joint='curve');cx,cy=pt(76,22);rad=7*scale;d.ellipse((cx-rad,cy-rad,cx+rad,cy+rad),fill=color)
def save(im,n):im.save(b/n,optimize=True)
im=Image.new('RGBA',(1536,1536),lime);mark(im,115,75,1300);save(im.resize((512,512),Image.Resampling.LANCZOS),'play-icon-512.png')
im=Image.new('RGB',(1024,500),paper);d=ImageDraw.Draw(im);d.rectangle((715,0,1024,500),fill=lime)
d.text((58,68),'BRIDGIT',font=font(17,bold=True),fill=teal)
d.text((55,132),'Turn any Android device',font=font(42,True),fill=ink);d.text((55,191),'into an agentic',font=font(42,True),fill=ink);d.text((55,250),'appliance.',font=font(42,True),fill=ink)
d.text((59,338),'Connect your agent to your Android.',font=font(22),fill=ink)
d.text((59,376),'Your phone. Your agent. Your permissions.',font=font(16),fill=teal)
# Abstract two endpoints connected by a bridge, no duplicated app-icon tile.
d.rounded_rectangle((765,126,952,402),radius=23,outline=ink,width=6);d.line((821,144,896,144),fill=ink,width=6)
d.ellipse((823,211,895,283),fill=teal);d.line((798,333,920,333),fill=ink,width=4)
save(im,'play-feature-1024x500.png')
im=Image.new('RGB',(1200,630),ink);d=ImageDraw.Draw(im);mark(im,900,125,230,lime)
d.text((65,67),'BRIDGIT',font=font(33,bold=True),fill=lime)
d.text((60,168),'Turn any Android device',font=font(60,True),fill=paper);d.text((60,247),'into an agentic',font=font(60,True),fill=paper);d.text((60,326),'appliance.',font=font(60,True),fill=paper)
d.text((65,473),'A new purpose for your Android.',font=font(29),fill=lime)
save(im,'social-card-1200x630.png');shutil.copy(b/'social-card-1200x630.png',assets/'social-card-1200x630.png')
im=Image.new('RGB',(1440,1080),paper);d=ImageDraw.Draw(im)
d.text((64,47),'BRIDGIT / BRAND DIRECTION',font=font(17,bold=True),fill=teal)
mark(im,40,125,195);d.text((265,153),'BRIDGIT',font=font(110,bold=True),fill=ink)
d.text((68,350),'Turn any Android device',font=font(55,True),fill=ink);d.text((68,414),'into an agentic appliance.',font=font(55,True),fill=ink)
d.text((71,505),'Turn a spare Android into something useful.',font=font(30),fill=teal)
colors=[('INK',ink),('WARM WHITE',paper),('SIGNAL LIME',lime),('TEAL',teal),('ORANGE',orange)]
for i,(label,c) in enumerate(colors):
 x=72+i*262;d.rounded_rectangle((x,583,x+235,735),radius=6,fill=c,outline='#C7CEC1',width=1);d.text((x,755),label,font=font(14,bold=True),fill=ink);d.text((x,783),c,font=font(16),fill=teal)
d.line((72,857,1370,857),fill='#C7CEC1',width=2)
d.text((72,897),'YOUR PHONE. YOUR AGENT. YOUR PERMISSIONS.',font=font(23,bold=True),fill=ink)
d.text((72,956),'System sans + editorial serif / Original bridge and beacon / 28 October 2026',font=font(19),fill=teal)
save(im,'brand-board.png')
for name in ['play-icon-512.png','play-feature-1024x500.png','social-card-1200x630.png','brand-board.png']:
 im=Image.open(b/name);print(name,im.size,im.mode,(b/name).stat().st_size)

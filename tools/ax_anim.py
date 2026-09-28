"""Blockbench idle animation -> the keyframe segments ArcartX (AX-Fabric 2.6.72) actually plays.

Two stages are emulated exactly, because Bloom must reproduce AX's pose frame for frame:
 1. Orbit (bbmodel -> Bedrock JSON, AnimationBuilder.processChannel/handle*): Float.toString time keys,
    pre/post only after a step keyframe, catmullrom kept as lerp_mode, single t=0 keyframe -> constant.
 2. AX 2.6.72's parser: one segment per key, easing chosen once
    for all three axes (catmullrom / step when pre!=post or the next key jumps back / linear), an extra
    zero-length jump segment when pre!=post, 1e-4 equality on the parsed values (radians for rotation).
The v5 sign flips of Orbit and AX cancel, so every value here is the raw Blockbench number
(degrees / pixels / scale factor). Segment lengths are AX ticks (60 per second).
"""
import math,struct
TPS=60.0
LINEAR,STEP,CATMULLROM=0,1,2
def f32(v):return struct.unpack('<f',struct.pack('<f',float(v)))[0]
def java_float_key(t):
 """Double.parseDouble(String.valueOf((float)t)); Java prints the shortest decimal that round-trips the float."""
 f=f32(t)
 if f==0:return 0.0
 for p in range(1,10):
  s='%.*g'%(p,f)
  if f32(float(s))==f:return float(s)
 return float(repr(f))
def number(v,where):
 if isinstance(v,(int,float)):r=float(v)
 else:
  s=str(v).strip()
  if s=='':return 0.0
  try:r=float(s)
  except ValueError:raise ValueError(f'{where}: molang keyframe value {v!r} is not supported')
 if not math.isfinite(r):raise ValueError(f'{where}: non-finite keyframe value')
 return r
def vec(dp,where):return [number(dp.get(a,0),where) for a in 'xyz']
def orbit_channel(frames,where):
 """Returns None (channel absent), ('const',v) or an ordered list of (time, pre, post, lerp_mode)."""
 if not frames:return None
 frames=sorted(frames,key=lambda k:f32(k['time'])) # Comparator.comparing(time), stable
 if len(frames)==1 and f32(frames[0]['time'])==0:
  dps=frames[0].get('data_points') or []
  return ('const',vec(dps[0],where)) if dps else None
 out={} # insertion-ordered like Jackson's ObjectNode; a repeated key overwrites in place
 for i,cur in enumerate(frames):
  prev=frames[i-1] if i else None;dps=cur.get('data_points') or []
  if not dps:continue
  interp=cur.get('interpolation') or 'linear'
  if interp=='bezier':raise ValueError(f'{where}: bezier keyframes are resampled by Orbit; not modelled')
  prev_step=prev is not None and prev.get('interpolation')=='step' and prev.get('data_points')
  key=java_float_key(cur['time'])
  if interp=='catmullrom':
   out[key]=(vec(prev['data_points'][-1],where) if prev_step else None,vec(dps[0],where),'catmullrom')
  elif len(dps)==1:
   out[key]=(vec(prev['data_points'][-1],where) if prev_step else None,vec(dps[0],where),'')
  else:
   out[key]=(vec(dps[0],where),vec(dps[1],where),'')
 return [(t,pre if pre is not None else post,post,lerp) for t,(pre,post,lerp) in out.items()]
def ax_segments(channel,rotation):
 """AX segment list: [length_ticks, start, end, easing, p0, p3] (p0/p3 only meaningful for catmullrom)."""
 if channel is None:return None
 if channel[0]=='const':v=channel[1];return [[0.0,v,v,LINEAR,v,v]]
 k=channel;scale=math.pi/180 if rotation else 1
 same=lambda a,b:all(abs(a[i]*scale-b[i]*scale)<1e-4 for i in range(3))
 segs=[]
 for a,(t,pre,post,lerp) in enumerate(k):
  length=(t-(k[a-1][0] if a else 0.0))*TPS
  bl=not same(pre,post)
  bl2=a+1<len(k) and not same(k[a+1][1],k[a+1][2]) and same(k[a+1][1],post)
  start=k[a-1][2] if a else pre
  p0=p3=pre
  if lerp.lower()=='catmullrom' and a>0:
   easing=CATMULLROM;p0=k[a-2][2] if a>=2 else k[a-1][2];p3=k[a+1][1] if a+1<len(k) else pre
  elif bl or bl2:easing=STEP
  else:easing=LINEAR
  segs.append([length,start,pre,easing,p0,p3])
  if bl:segs.append([0.0,pre,post,LINEAR,pre,pre])
 return segs
def channel_length(segs):return sum(s[0] for s in segs) if segs else 0.0
def animation_length(anim,channels):
 """animation_length*60, +1 when shorter than the longest channel (AX parser); -1 -> longest channel."""
 longest=max([channel_length(s) for s in channels if s] or [0.0])
 if anim.get('length') is None:return longest
 d=java_float_key(anim['length'])*TPS
 return d+1 if d<longest else d
def ease(seg,t):
 length,s,e,mode,p0,p3=seg
 if length<=0 or t>=length:return list(e)
 x=t/length
 if mode==STEP:return list(s)
 if mode==CATMULLROM:return [0.5*(2*s[i]+(e[i]-p0[i])*x+(2*p0[i]-5*s[i]+4*e[i]-p3[i])*x*x+(3*s[i]-p0[i]-3*e[i]+p3[i])*x*x*x) for i in range(3)]
 return [s[i]+(e[i]-s[i])*x for i in range(3)]
def sample(segs,tick):
 """GeckoLib 4 getAnimationPointAtTick: first segment whose running end is past tick, else the last one."""
 total=0.0
 for seg in segs:
  total+=seg[0]
  if total>tick:return ease(seg,tick-(total-seg[0]))
 return ease(segs[-1],tick)
def start_value(segs):
 """Value the 5-tick transition blends toward: start value of the point found at tick 0."""
 total=0.0
 for seg in segs:
  total+=seg[0]
  if total>0:return list(seg[1])
 return list(segs[-1][1])

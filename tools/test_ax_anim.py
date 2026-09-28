"""Offline checks for the AX animation rig (tools/ax_anim.py + export_gallery ax_rig).
usage: test_ax_anim.py <anim models.json> <rigid models.json> <bbmodel>
Checks legacy v4 conversion data. Since 0.3.1 the runtime consumes AX draw poses, not these clocks."""
import json,sys,os,math
sys.path.insert(0,os.path.dirname(os.path.abspath(__file__)))
import ax_anim as A
# Same helpers as export_gallery.py (importing it would run the export).
def mul(a,b):
 x,y,z,w=a; X,Y,Z,W=b
 return [w*X+x*W+y*Z-z*Y,w*Y-x*Z+y*W+z*X,w*Z+x*Y-y*X+z*W,w*W-x*X-y*Y-z*Z]
def quat(v):
 q=[0,0,0,1]
 for axis,degrees in enumerate(v):
  a=[0,0,0,math.cos(math.radians(degrees)/2)];a[axis]=math.sin(math.radians(degrees)/2);q=mul(a,q)
 return q
def mat_mul(a,b):return [[sum(a[r][k]*b[k][c] for k in range(4)) for c in range(4)] for r in range(4)]
def mat_t(x,y,z):return [[1,0,0,x],[0,1,0,y],[0,0,1,z],[0,0,0,1]]
def mat_s(x,y,z):return [[x,0,0,0],[0,y,0,0],[0,0,z,0],[0,0,0,1]]
def mat_q(q):
 x,y,z,w=q
 return [[1-2*(y*y+z*z),2*(x*y-z*w),2*(x*z+y*w),0],[2*(x*y+z*w),1-2*(x*x+z*z),2*(y*z-x*w),0],[2*(x*z-y*w),2*(y*z+x*w),1-2*(x*x+y*y),0],[0,0,0,1]]
def mat_apply(M,p):return [M[r][0]*p[0]+M[r][1]*p[1]+M[r][2]*p[2]+M[r][3] for r in range(3)]
def unflat(c):return None if c is None else [[c[i],c[i+1:i+4],c[i+4:i+7],int(c[i+7]),c[i+8:i+11],c[i+11:i+14]] for i in range(0,len(c),14)]
def pose(rig,tick,blend=None):
 """Bone matrices at animation tick; blend in [0,1) = 5-tick transition from rest toward the start value."""
 mats=[]
 for b in rig['bones']:
  vals={}
  for ch,rest in (('rot',[0,0,0]),('pos',[0,0,0]),('scale',[1,1,1])):
   s=unflat(b[ch])
   if s is None:vals[ch]=rest
   elif blend is not None:t=A.start_value(s);vals[ch]=[rest[i]+(t[i]-rest[i])*blend for i in range(3)]
   else:vals[ch]=A.sample(s,tick)
  r=[b['rest'][i]+vals['rot'][i] for i in range(3)];p=b['pivot']
  M=mat_mul(mat_mul(mat_mul(mat_mul(mat_t(*[v/16 for v in vals['pos']]),mat_t(*p)),mat_q(quat(r))),mat_s(*vals['scale'])),mat_t(*[-v for v in p]))
  mats.append(M if b['parent']<0 else mat_mul(mats[b['parent']],M))
 return mats
def skin(rig,entry,mats):
 C=[rig['base'][i*4:i*4+4] for i in range(4)];v=entry['vertices'];out=[]
 for bone,first,count in entry['boneRanges']:
  M=mat_mul(C,mats[bone])
  for k in range(first,first+count):out.append(mat_apply(M,v[k*5:k*5+3])+v[k*5+3:k*5+5])
 return out
def main(anim_path,rigid_path,bbmodel):
 an=json.load(open(anim_path,encoding='utf-8'));ri=json.load(open(rigid_path,encoding='utf-8'))
 assert an['version']==4 and ri['version']==3
 fails=0
 for e in [x for x in an['models'] if x.get('rig')=='anim']:
  rig=an['rigs'][e['animRig']]
  twin=[x for x in ri['models'] if x.get('rig')=='rigid' and x['texture']==e['texture'] and x['preset']==e['preset']]
  assert len(twin)==1,'no rigid twin'
  # 1) all animation zeroed == rigid export (same triangles, order differs: compare as sorted multiset)
  rest={**rig,'bones':[{**b,'rot':None,'pos':None,'scale':None} for b in rig['bones']]}
  got=sorted(tuple(round(v,5) for v in p) for p in skin(rest,e,pose(rest,0)))
  tv=twin[0]['vertices'];want=sorted(tuple(round(v,5) for v in tv[i:i+5]) for i in range(0,len(tv),5))
  worst=max(max(abs(a-b) for a,b in zip(g,w)) for g,w in zip(got,want))
  print(f"rest==rigid {e['texture']}: {len(got)} vertices, max diff {worst:.2e}")
  if len(got)!=len(want) or worst>2e-5:fails+=1
  # 2) animation actually moves something, and stays finite over one loop
  base=skin(rig,e,pose(rig,0));moved=0;finite=True
  for tick in range(0,int(rig['length'])+1,5):
   p=skin(rig,e,pose(rig,tick));finite=finite and all(math.isfinite(c) for q in p for c in q)
   moved=max(moved,max(abs(p[i][j]-base[i][j]) for i in range(len(p)) for j in range(3)))
  print(f"  loop sweep: max displacement vs t=0 {moved:.4f} blocks, finite={finite}")
  if not finite or moved<1e-4:fails+=1
 # 3) animators AX would bind by name must all exist as groups
 m=json.load(open(bbmodel,encoding='utf-8-sig'));names={g.get('name') for g in m.get('groups',[])} # v5 keeps names in groups[]
 def walk(n):
  if isinstance(n,dict):names.add(n.get('name'));[walk(c) for c in n.get('children',[])]
 [walk(n) for n in m.get('outliner',[])]
 for a in m.get('animations',[]):
  orphan=[x.get('name') for x in (a.get('animators') or {}).values() if x.get('type')!='effect' and x.get('name') not in names]
  print(f"animation {a['name']}: {len(orphan)} animators without a group {orphan[:8]}")
 # 4) segment semantics (hand-computed cases)
 lin=A.ax_segments(A.orbit_channel([{'time':0,'data_points':[{'x':0,'y':0,'z':0}]},{'time':1,'data_points':[{'x':10,'y':0,'z':0}]}],'t'),False)
 assert A.sample(lin,30)==[5,0,0] and A.sample(lin,60)==[10,0,0] and A.sample(lin,99)==[10,0,0]
 st=A.ax_segments(A.orbit_channel([{'time':0,'interpolation':'step','data_points':[{'x':1,'y':0,'z':0}]},{'time':1,'data_points':[{'x':5,'y':0,'z':0}]}],'t'),False)
 assert A.sample(st,30)==[1,0,0] and A.sample(st,60.01)==[5,0,0],st # step holds, then jumps at the key
 q=A.ax_segments(A.orbit_channel([{'time':0,'data_points':[{'x':0,'y':0,'z':0}]},{'time':1,'data_points':[{'x':1,'y':0,'z':0}]},{'time':2,'interpolation':'step','data_points':[{'x':2,'y':0,'z':0}]},{'time':3,'data_points':[{'x':3,'y':0,'z':0}]}],'t'),False)
 assert A.sample(q,90)==[1,0,0],A.sample(q,90) # AX quirk: A(linear)->B(step) segment is step
 cr=A.ax_segments(A.orbit_channel([{'time':0,'data_points':[{'x':0,'y':0,'z':0}]},{'time':1,'interpolation':'catmullrom','data_points':[{'x':1,'y':0,'z':0}]},{'time':2,'data_points':[{'x':2,'y':0,'z':0}]}],'t'),False)
 assert abs(A.sample(cr,30)[0]-0.4375)<1e-9,A.sample(cr,30) # p0=p1=0 (clamped), p2=1, p3=2: 0.5*(0.5+0.5-0.125)
 assert A.java_float_key(3.16667)==3.16667 and A.java_float_key(0.1)==0.1
 print('segment semantics: ok')
 print('FAIL' if fails else 'PASS',fails)
 sys.exit(1 if fails else 0)
if __name__=='__main__':main(*sys.argv[1:4])

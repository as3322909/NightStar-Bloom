import pathlib,json,math,base64,gzip,struct,hashlib,os,tempfile,shutil,sys
sys.path.insert(0,os.path.dirname(os.path.abspath(__file__)))
import ax_anim
ROOT=pathlib.Path(__file__).resolve().parents[1]
rigs={}
R=pathlib.Path(os.environ.get('NSB_OUTPUT',ROOT/'generated/gallery'))
SAMPLES=pathlib.Path(os.environ.get('NSB_SAMPLES',ROOT/'generated/crystal-source'))
pending={}
manifest=[]
def put(p,s):
 pending[str(p)]=s
def js(p,v):put(p,json.dumps(v,ensure_ascii=False,indent=2))
def add(a,b):return [a[i]+b[i] for i in range(3)]
def sub(a,b):return [a[i]-b[i] for i in range(3)]
def mul(a,b):
 x,y,z,w=a; X,Y,Z,W=b
 return [w*X+x*W+y*Z-z*Y,w*Y-x*Z+y*W+z*X,w*Z+x*Y-y*X+z*W,w*W-x*X-y*Y-z*Z]
def inv(q):return [-q[0],-q[1],-q[2],q[3]]
def rot(q,v):return mul(mul(q,v+[0]),inv(q))[:3]
def quat(v):
 q=[0,0,0,1]
 for axis,degrees in enumerate(v):
  a=[0,0,0,math.cos(math.radians(degrees)/2)];a[axis]=math.sin(math.radians(degrees)/2);q=mul(a,q)
 return q
def box_corners(x,y,z,X,Y,Z):
 # Vanilla/Blockbench face winding; paired with uv order (u,v),(u,V),(U,V),(U,v).
 return {
  'north':[(X,Y,z),(X,y,z),(x,y,z),(x,Y,z)],
  'south':[(x,Y,Z),(x,y,Z),(X,y,Z),(X,Y,Z)],
  'west':[(x,Y,z),(x,y,z),(x,y,Z),(x,Y,Z)],
  'east':[(X,Y,Z),(X,y,Z),(X,y,z),(X,Y,z)],
  'up':[(x,Y,z),(x,Y,Z),(X,Y,Z),(X,Y,z)],
  'down':[(x,y,Z),(x,y,z),(X,y,z),(X,y,Z)]}
def check_settings(settings,preset,fname,elem):
 where=f"element={elem['uuid']} group={elem['_group']}"
 if set(settings)-{'color','strength','motion','period','amplitude','preset'}:raise ValueError(f"{fname}: unknown bloom settings {where}")
 for key,default,low,high in [('strength',1,0,4),('period',3,.25,60),('amplitude',.1,0,.5)]:
  value=settings.get(key,default)
  if not isinstance(value,(int,float)) or not math.isfinite(value) or not low<=value<=high:raise ValueError(f"{fname}: invalid {key} {where}")
 color=settings.get('color',[1,1,1])
 if not isinstance(color,list) or len(color)!=3 or any(not isinstance(v,(int,float)) or not math.isfinite(v) or not 0<=v<=4 for v in color):raise ValueError(f"{fname}: invalid color {where}")
 if settings.get('preset',preset) not in ('crystal','core','rim','aura') or settings.get('motion','inherit') not in ('inherit','static','breath','flow'):raise ValueError(f"{fname}: invalid preset/motion {where}")
def mat_mul(a,b):return [[sum(a[r][k]*b[k][c] for k in range(4)) for c in range(4)] for r in range(4)]
def mat_t(x,y,z):return [[1,0,0,x],[0,1,0,y],[0,0,1,z],[0,0,0,1]]
def mat_s(x,y,z):return [[x,0,0,0],[0,y,0,0],[0,0,z,0],[0,0,0,1]]
def mat_q(q):
 x,y,z,w=q
 return [[1-2*(y*y+z*z),2*(x*y-z*w),2*(x*z+y*w),0],[2*(x*y+z*w),1-2*(x*x+z*z),2*(y*z-x*w),0],[2*(x*z-y*w),2*(y*z+x*w),1-2*(x*x+y*y),0],[0,0,0,1]]
def mat_apply(M,p):return [M[r][0]*p[0]+M[r][1]*p[1]+M[r][2]*p[2]+M[r][3] for r in range(3)]
def clamp(v,a):return max(-a,min(a,v))
def ax_chain(m,f):
 """Matrix from Blockbench space /16 to the PoseStack ArcartX receives for the FIXED context.
 Calibrated live (AX-Fabric 2.6.72 client / ArcartX 2.7.81 server, 2026-09-27, evidence/ax-compat/server/probe):
 AX applies display.fixed as T · rotationXYZ · S · T(0,-.5,0).
 MC 1.21.11 Transformation.apply ends with T(-.5,-.5,-.5), cancelled by AX's
 T(.5,.5,.5); AX then applies its non-player item Y offset. An absent fixed
 entry skips that entire branch. The original crystal fixture covers both cases."""
 C=mat_t(0,0.01,0) # renderer: S(item_scale=1) then T(0,0.01,0); the samples carry no axmeta
 meta=f.with_suffix('.axmeta.json')
 if meta.exists():raise ValueError(f'{f.name}: axmeta (item_scale/player_skin) is not modelled by the exporter')
 fixed=m.get('display',{}).get('fixed')
 if fixed is None:return C,1,'absent: chain skipped'
 # AX's vec3 wrapper substitutes 1.0 per component for a missing array (not 0).
 r=fixed.get('rotation',[1,1,1]);t=fixed.get('translation',[1,1,1]);s=fixed.get('scale',[1,1,1])
 t=[clamp(v/16,5) for v in t];s=[clamp(v,4) for v in s]
 if not(s[0]==s[1]==s[2]) or s[0]<=0:raise ValueError(f'{f.name}: non-uniform or mirrored display.fixed scale is not supported for flow')
 qx=quat([r[0],0,0]);qy=quat([0,r[1],0]);qz=quat([0,0,r[2]])
 D=mat_mul(mat_mul(mat_t(*t),mat_q(mul(mul(qx,qy),qz))),mat_s(*s)) # T · rotationXYZ · S; no centring (measured)
 return mat_mul(mat_mul(D,mat_t(0,-.5,0)),C),s[0],{'rotation':r,'translation_blocks':t,'scale':s}
def ax_rig(m,f,bloom_elements,group_info,C,rig_id):
 """Version 4 bone mapping with legacy idle channels (tools/ax_anim.py), or None when
 the glow cubes never move. Runtime 0.3.1 reads AX draw poses by name; channel data is retained for v4 compatibility. Only the groups between root and glow cubes are kept."""
 idle=[a for a in m.get('animations') or [] if a.get('name')=='idle']
 if not idle or os.environ.get('NSB_AX_RIG')=='rigid':return None # NSB_AX_RIG=rigid: old static output (tests / fallback)
 idle=idle[0];order=[];index={}
 for e in bloom_elements:
  for key in e['_chain']:
   if key not in index:index[key]=len(order);order.append(key)
 animators={}
 for an in (idle.get('animators') or {}).values():
  if an.get('type')=='effect':continue # Orbit skips effect animators
  if an.get('name') in animators:raise ValueError(f"{f.name}: two idle animators named {an.get('name')!r}")
  animators[an.get('name')]=an
 names=[g.get('name') for g in group_info.values()]
 channels={};every=[]
 for an in animators.values():
  for ch in ('rotation','position','scale'):
   segs=ax_anim.ax_segments(ax_anim.orbit_channel([k for k in an.get('keyframes') or [] if k.get('channel')==ch],f"{f.name}: idle {an.get('name')}.{ch}"),ch=='rotation')
   channels[(an.get('name'),ch)]=segs;every.append(segs)
 bones=[];moving=False
 for key in order:
  g=group_info[key];name=g.get('name','')
  if names.count(name)>1:raise ValueError(f'{f.name}: AX binds animation by group name; duplicate group name {name!r} on a glow bone chain')
  segs={ch:channels.get((name,ch)) for ch in ('rotation','position','scale')}
  moving=moving or any(segs.values())
  flat=lambda s:None if s is None else [v for seg in s for v in [seg[0],*seg[1],*seg[2],seg[3],*seg[4],*seg[5]]]
  bones.append({'name':name,'pivot':[v/16 for v in g.get('origin',[0,0,0])],'rest':list(g.get('rotation',[0,0,0])),'rot':flat(segs['rotation']),'pos':flat(segs['position']),'scale':flat(segs['scale'])})
 for i,key in enumerate(order):
  e_chain=next(e['_chain'] for e in bloom_elements if key in e['_chain']);at=e_chain.index(key)
  bones[i]['parent']=index[e_chain[at-1]] if at else -1
 if not moving:return None
 loop={'loop':'loop','hold':'hold'}.get(idle.get('loop'))
 if loop is None:raise ValueError(f"{f.name}: idle loop mode {idle.get('loop')!r} is not modelled (only loop / hold)")
 return {'id':rig_id,'base':[v for row in C for v in row],'length':ax_anim.animation_length(idle,every),'loop':loop,'bones':bones},index
def element_vertex(e,corner):
 """Blockbench-space (/16) vertex with the cube's own rotation only; group transforms come from the rig."""
 eq=quat(e.get('rotation',[0,0,0]));origin=e.get('origin',[0,0,0]);center=[(a+b)/2 for a,b in zip(e['from'],e['to'])]
 return [w/16 for w in add(add(origin,rot(eq,sub(center,origin))),rot(eq,list(corner)))]
AX_PREFIX=os.environ.get('NSB_AX_PREFIX','nightstar_lab/')
# Give separate model sets unique names so textures, item models and AX keys do not collide.
NAME_PREFIX=os.environ.get('NSB_NAME_PREFIX','sample_')
# Gallery yaw (degrees) on top of right_rotation's 180°: 70 shows the original samples three-quarter; flat blades in the XY plane need 0 to face the camera.
DISPLAY_YAW=float(os.environ.get('NSB_DISPLAY_YAW','70'))
# Dedicated test-world gallery; no external pedestal intersects authored geometry.
gallery=['fill -18 0 -18 18 0 14 minecraft:gray_concrete','fill -18 1 -17 18 20 14 minecraft:air','fill -18 1 -18 18 20 -18 minecraft:black_concrete','kill @e[tag=nightstar_lab_model]']
ax_commands=list(gallery);ax_report=[]
P='resourcepacks/NightstarModelLab/'
js(P+'pack.mcmeta',{'pack':{'description':'Nightstar Bloom model examples','min_format':75,'max_format':75}})
commands=list(gallery)
stats=[]
if not list(SAMPLES.glob('*.bbmodel')):raise ValueError(f'No .bbmodel inputs in {SAMPLES}; run tools/make_crystals.py or set NSB_SAMPLES')
for idx,f in enumerate(sorted(SAMPLES.glob('*.bbmodel'))):
 m=json.loads(f.read_text(encoding='utf-8-sig'));name=NAME_PREFIX+str(idx)
 if not m.get('textures'):raise ValueError(f'{f.name}: missing textures')
 if len({e['uuid'] for e in m['elements']})!=len(m['elements']):raise ValueError(f'{f.name}: duplicate element UUID')
 for elem in m['elements']:
  for side,face in elem.get('faces',{}).items():
   if face.get('texture') is not None:
    ti=int(face['texture'])
    if ti<0 or ti>=len(m['textures']):raise ValueError(f"{f.name}: missing texture element={elem['uuid']} face={side}")
 tex={'particle':'#0'}
 for ti,t in enumerate(m['textures']):
  target=P+'assets/nightstar/textures/item/'+name+'_'+str(ti)+'.png'
  try:
   image=base64.b64decode(t['source'].split(',',1)[1],validate=True)
   if image[:8]!=b'\x89PNG\r\n\x1a\n':raise ValueError('not an embedded PNG')
   width,height=struct.unpack('>II',image[16:24])
   if width<1 or height<1:raise ValueError('invalid PNG dimensions')
  except Exception as error:raise ValueError(f'{f.name}: invalid texture index={ti}: {error}') from error
  pending[target]=image;tex[str(ti)]='nightstar:item/'+name+'_'+str(ti)
 elements={e['uuid']:e for e in m['elements']}; groupdefs={g['uuid']:g for g in m.get('groups',[])};resolved=[];seen=set();flow_markers={}
 group_info={}
 def visit(node,pq=[0,0,0,1],pt=[0,0,0],bloom=False,path='root',preset='crystal',settings=None,chain=()):
  if isinstance(node,str):
   if node not in elements:raise ValueError(f'{f.name}: unknown element {node} group={path}')
   if node in seen:raise ValueError(f'{f.name}: repeated element UUID={node} group={path}')
   seen.add(node)
   e=elements[node].copy();e['_settings']=settings or {};e['_bloom']=bloom;e['_preset']=preset;e['_group']=path;e['_chain']=chain
   if e.get('visibility',True)==False:return
   if any(not math.isfinite(v) for key in ['from','to','origin','rotation'] for v in e.get(key,[])):raise ValueError(f'{f.name}: non-finite coordinate element={node} group={path}')
   if e.get('type','cube')!='cube':raise ValueError(f'{f.name}: unsupported element {node} group={path}')
   if any(len(e.get(k,[0,0,0]))!=3 for k in ['from','to','origin','rotation']):raise ValueError(f'{f.name}: invalid vector element={node} group={path}')
   eq=quat(e.get('rotation',[0,0,0]));origin=e.get('origin',[0,0,0]);center=[(a+b)/2 for a,b in zip(e['from'],e['to'])]
   center=add(rot(pq,add(origin,rot(eq,sub(center,origin)))),pt)
   resolved.append((e,center,mul(pq,eq)))
  else:
   g={**groupdefs.get(node.get('uuid'),{}),**node};name=g.get('name','');explicit=next((p for p in ('core','rim','aura') if name.endswith('__bloom_'+p)),None)
   marker='start' if name.endswith('__flow_start') or name.endswith('flow_start') or name.endswith('__flow_1') or name.endswith('flow_1') else ('end' if name.endswith('__flow_end') or name.endswith('flow_end') or name.endswith('__flow_2') or name.endswith('flow_2') else None)
   if marker is not None:
    if g.get('children'):raise ValueError(f'{f.name}: flow marker must be an empty group group={path}/{name}')
    o=g.get('origin',[0,0,0]);flow_markers[marker]=add(rot(pq,o),pt);return
   if explicit is None and name.endswith('__bloom'):explicit='crystal'
   bloom=bloom or explicit is not None;preset=explicit or preset;settings={**(settings or {}),**g.get('bloom',{})};path=path+'/'+g.get('name',g.get('uuid','?'))
   if g.get('visibility',True)==False:return
   if any(not math.isfinite(v) for key in ['origin','rotation'] for v in g.get(key,[])):raise ValueError(f"{f.name}: invalid group transform UUID={g.get('uuid')} group={path}")
   q=quat(g.get('rotation',[0,0,0]));o=g.get('origin',[0,0,0]);t=sub(o,rot(q,o))
   key=g.get('uuid',path);group_info[key]=g
   for child in g.get('children',[]):visit(child,mul(pq,q),add(rot(pq,t),pt),bloom,path,preset,settings,chain+(key,))
 for node in m.get('outliner',list(elements)):visit(node)
 allpoints=[]
 for e,c,q in resolved:
  half=[abs(e['to'][i]-e['from'][i])/2+e.get('inflate',0) for i in range(3)]
  for x in [-1,1]:
   for y in [-1,1]:
    for z in [-1,1]:allpoints.append(add(c,rot(q,[half[0]*x,half[1]*y,half[2]*z])))
 lo=[min(p[i] for p in allpoints) for i in range(3)];hi=[max(p[i] for p in allpoints) for i in range(3)];mid=[(lo[i]+hi[i])/2 for i in range(3)];fit=3.5*16/max(hi[i]-lo[i] for i in range(3))
 buckets={}
 for e,c,q in resolved:buckets.setdefault((tuple(round(v,7) for v in q),e['_bloom'],e['_preset'],json.dumps(e['_settings'],sort_keys=True)),[]).append((e,rot(inv(q),c)))
 for part,((qt,is_bloom,preset,settings_json),items) in enumerate(buckets.items()):
  q=list(qt);bounds=[]
  for e,c in items:
   h=[abs(e['to'][i]-e['from'][i])/2+e.get('inflate',0) for i in range(3)];bounds.extend([sub(c,h),add(c,h)])
  low=[min(p[i] for p in bounds) for i in range(3)];high=[max(p[i] for p in bounds) for i in range(3)];center=[(low[i]+high[i])/2 for i in range(3)];factor=min(1,40/max(max(high[i]-low[i] for i in range(3)),0.01));out=[]
  for e,c in items:
   h=[abs(e['to'][i]-e['from'][i])/2+e.get('inflate',0) for i in range(3)];faces={}
   for side,face in e.get('faces',{}).items():
    ti=face.get('texture')
    if ti is None:continue
    ti=int(ti)
    if ti<0 or ti>=len(m['textures']):raise ValueError(f"{f.name}: invalid texture={ti} element={e['uuid']} group={e['_group']}")
    if face.get('rotation',0) not in (0,90,180,270):raise ValueError(f"{f.name}: invalid UV rotation element={e['uuid']} group={e['_group']}")
    texture=m['textures'][ti];tw=texture.get('uv_width',m['resolution']['width']);th=texture.get('uv_height',m['resolution']['height']);uv=face['uv']
    if side not in ('north','south','west','east','up','down') or tw<=0 or th<=0 or len(uv)!=4 or any(not math.isfinite(v) for v in uv):raise ValueError(f"{f.name}: invalid UV element={e['uuid']} group={e['_group']}")
    faces[side]={'uv':[uv[0]*16/tw,uv[1]*16/th,uv[2]*16/tw,uv[3]*16/th],'texture':'#'+str(ti)}
    if face.get('rotation'):faces[side]['rotation']=face['rotation']
   out.append({'from':[8+(c[i]-h[i]-center[i])*factor for i in range(3)],'to':[8+(c[i]+h[i]-center[i])*factor for i in range(3)],'faces':faces})
  partname=name+'_'+str(part)
  js(P+'assets/nightstar/models/item/'+partname+'.json',{'textures':tex,'elements':out,'display':{'fixed':{'rotation':[0,0,0],'translation':[0,0,0],'scale':[1,1,1]}}})
  js(P+'assets/nightstar/items/'+partname+'.json',{'model':{'type':'minecraft:model','model':'nightstar:item/'+partname}})
  if is_bloom:
   by_texture={}
   for elem in out:
    a=[(v-8)/16 for v in elem['from']];b=[(v-8)/16 for v in elem['to']]
    x,y,z=a;X,Y,Z=b
    corners=box_corners(x,y,z,X,Y,Z)
    for side,face in elem['faces'].items():
     vertices=by_texture.setdefault(face['texture'][1:],[])
     u,v,U,V=[n/16 for n in face['uv']];uv=[(u,v),(u,V),(U,V),(U,v)]
     shift=face.get('rotation',0)//90;uv=uv[shift:]+uv[:shift]
     for j in [0,1,2,0,2,3]:vertices.extend([*corners[side][j],*uv[j]])
   settings=json.loads(settings_json);check_settings(settings,preset,f.name,items[0][0])
   flow=None
   if flow_markers:
    if set(flow_markers)!= {'start','end'}:raise ValueError(f'{f.name}: both __flow_start and __flow_end are required')
    start,end=flow_markers['start'],flow_markers['end'];direction=sub(end,start);length=math.sqrt(sum(v*v for v in direction))
    if not math.isfinite(length) or length<1e-5:raise ValueError(f'{f.name}: flow markers are coincident')
    flow={'start':start,'dir':[v/length for v in direction],'invLength':1/length}
   local_flow=None
   if flow:
    invq=inv(q);local_start=rot(invq,sub(flow['start'],center));local_dir=rot(invq,flow['dir'])
    local_flow={'start':[v*factor/16 for v in local_start],'dir':local_dir,'invLength':flow['invLength']*16/factor}
   # One entry per texture; every entry of a split mesh shares the same id and flow frame.
   for ti,vertices in by_texture.items():
    entry={'id':'nightstar:'+partname,'texture':'nightstar:textures/item/'+name+'_'+ti+'.png','preset':preset,'source':f.name,'elements':[e['uuid'] for e,c in items],'groups':sorted(set(e['_group'] for e,c in items)),'vertices':vertices,**settings}
    if local_flow:entry['flow']=local_flow
    manifest.append(entry)
  displayq=quat([0,DISPLAY_YAW,0]);pos=rot(displayq,sub(rot(q,center),mid));q=mul(displayq,q);pos=[pos[i]*fit/16 for i in range(3)];pos=add(pos,[(idx-1)*4,3.2,0]);scale=fit/factor
  fl=lambda v:str(round(v,7))+'f'
  nbt='{Tags:["nightstar_lab_model"],item:{id:"minecraft:stick",count:1,components:{"minecraft:item_model":"nightstar:'+partname+'"}},item_display:"fixed",brightness:{block:15,sky:15},transformation:{translation:[0f,0f,0f],left_rotation:['+','.join(map(fl,q))+'],scale:['+','.join([fl(scale)]*3)+'],right_rotation:[0f,1f,0f,0f]}}'
  commands.append('summon minecraft:item_display '+' '.join(str(round(v,6)) for v in pos)+' '+nbt)
 # AX model space: the chain ArcartX 2.6.72 applies after the PoseStack it receives (live-calibrated, see ax_chain / docs/AX-ADAPTATION.md).
 ax_id=AX_PREFIX+name;C,disp_scale,ax_note=ax_chain(m,f)
 ax_buckets={}
 for e,c,q in resolved:
  if e['_bloom']:ax_buckets.setdefault((e['_preset'],json.dumps(e['_settings'],sort_keys=True)),[]).append((e,c,q))
 ax_flow=None
 if flow_markers:
  if set(flow_markers)!={'start','end'}:raise ValueError(f'{f.name}: both __flow_start and __flow_end are required')
  s0=mat_apply(C,[v/16 for v in flow_markers['start']]);s1=mat_apply(C,[v/16 for v in flow_markers['end']]);d=sub(s1,s0);ln=math.sqrt(sum(v*v for v in d))
  if ln<1e-6:raise ValueError(f'{f.name}: flow markers are coincident')
  ax_flow={'start':s0,'dir':[v/ln for v in d],'invLength':1/ln}
 rig=ax_rig(m,f,[e for items in ax_buckets.values() for e,c,q in items],group_info,C,'nightstar:ax/'+name)
 if rig:rig,bone_index=rig;rigs[rig['id']]={k:v for k,v in rig.items() if k!='id'}
 for (preset,settings_json),items in ax_buckets.items():
  settings=json.loads(settings_json);check_settings(settings,preset,f.name,items[0][0])
  by_texture={}
  if rig:
   # Animated: raw per-cube vertices grouped by the cube's own bone; the runtime skins them every frame.
   for e,c,q in items:
    h=[abs(e['to'][i]-e['from'][i])/2+e.get('inflate',0) for i in range(3)];x,y,z=[-v for v in h];X,Y,Z=h;bone=bone_index[e['_chain'][-1]]
    for side,face in e.get('faces',{}).items():
     ti=face.get('texture')
     if ti is None:continue
     ti=int(ti);texture=m['textures'][ti];tw=texture.get('uv_width',m['resolution']['width']);th=texture.get('uv_height',m['resolution']['height'])
     u,v,U,V=face['uv'];u,U=u/tw,U/tw;v,V=v/th,V/th;uv=[(u,v),(u,V),(U,V),(U,v)]
     shift=face.get('rotation',0)//90;uv=uv[shift:]+uv[:shift]
     corners=box_corners(x,y,z,X,Y,Z)[side];per_bone=by_texture.setdefault(str(ti),{}).setdefault(bone,[])
     for j in [0,1,2,0,2,3]:per_bone.extend([*element_vertex(e,corners[j]),*uv[j]])
   for ti,per_bone in by_texture.items():
    vertices=[];ranges=[]
    for bone in sorted(per_bone):ranges.append([bone,len(vertices)//5,len(per_bone[bone])//5]);vertices.extend(per_bone[bone])
    entry={'id':'nightstar:ax/'+name,'space':'ax','rig':'anim','animRig':rig['id'],'boneRanges':ranges,'aliases':[ax_id],'texture':'nightstar:textures/item/'+name+'_'+ti+'.png','preset':preset,'source':f.name,'groups':sorted(set(e['_group'] for e,c,q in items)),'vertices':vertices,**settings}
    if ax_flow:entry['flow']=ax_flow
    manifest.append(entry)
   continue
  for e,c,q in items:
   h=[abs(e['to'][i]-e['from'][i])/2+e.get('inflate',0) for i in range(3)]
   x,y,z=[-v for v in h];X,Y,Z=h
   for side,face in e.get('faces',{}).items():
    ti=face.get('texture')
    if ti is None:continue
    ti=int(ti);texture=m['textures'][ti];tw=texture.get('uv_width',m['resolution']['width']);th=texture.get('uv_height',m['resolution']['height'])
    u,v,U,V=face['uv'];u,U=u/tw,U/tw;v,V=v/th,V/th;uv=[(u,v),(u,V),(U,V),(U,v)]
    shift=face.get('rotation',0)//90;uv=uv[shift:]+uv[:shift]
    corners=box_corners(x,y,z,X,Y,Z)[side]
    vertices=by_texture.setdefault(str(ti),[])
    for j in [0,1,2,0,2,3]:vertices.extend([*mat_apply(C,[w/16 for w in add(c,rot(q,list(corners[j])))]),*uv[j]])
  for ti,vertices in by_texture.items():
   entry={'id':'nightstar:ax/'+name,'space':'ax','rig':'rigid','aliases':[ax_id],'texture':'nightstar:textures/item/'+name+'_'+ti+'.png','preset':preset,'source':f.name,'groups':sorted(set(e['_group'] for e,c,q in items)),'vertices':vertices,**settings}
   if ax_flow:entry['flow']=ax_flow
   manifest.append(entry)
 # Test copy of the source model for the AX client; AX indexes resource/model/<id>.bbmodel at startup.
 put('resourcepacks/ArcartX/resource/model/'+ax_id+'.bbmodel',f.read_bytes())
 pts=[mat_apply(C,[w/16 for w in p]) for p in allpoints];alo=[min(p[i] for p in pts) for i in range(3)];ahi=[max(p[i] for p in pts) for i in range(3)];amid=[(alo[i]+ahi[i])/2 for i in range(3)]
 ax_scale=3.5/max(ahi[i]-alo[i] for i in range(3));yaw=quat([0,DISPLAY_YAW,0]);apos=sub([(idx-1)*4,3.2,0],[v*ax_scale for v in rot(yaw,amid)])
 ax_commands.append('summon minecraft:item_display '+' '.join(str(round(v,6)) for v in apos)+' {Tags:["nightstar_lab_model","nightstar_lab_ax"],item:{id:"minecraft:stick",count:1,components:{"minecraft:custom_data":{model:"'+ax_id+'"}}},item_display:"fixed",brightness:{block:15,sky:15},transformation:{translation:[0f,0f,0f],left_rotation:['+','.join(str(round(v,7))+'f' for v in yaw)+'],scale:['+','.join([str(round(ax_scale,7))+'f']*3)+'],right_rotation:[0f,1f,0f,0f]}}')
 ax_report.append({'model':ax_id,'display_fixed':ax_note,'display_scale':disp_scale,'bloom_cubes':sum(len(v) for v in ax_buckets.values()),'rig':'anim' if rig else 'rigid','rig_bones':len(rig['bones']) if rig else 0,'rig_length_ticks':rig['length'] if rig else None})
 if idx==0:
  # Same item_model gallery entities, but tagged with an AX model that does not exist: AX leaves them to vanilla.
  for command in commands:
   if command.startswith('summon minecraft:item_display ') and 'nightstar:'+name+'_' in command:
    tokens=command.split(' ',5);tokens[2]=str(float(tokens[2])+12)
    ax_commands.append(' '.join(tokens).replace('"minecraft:item_model":','"minecraft:custom_data":{model:"'+AX_PREFIX+'missing"},"minecraft:item_model":').replace('"nightstar_lab_model"','"nightstar_lab_model","nightstar_lab_fallback"'))
 stats.append({'name':f.name,'cubes':len(resolved),'display_entities':len(buckets),'animations':'static pose only'})
commands+=['tp @s 0 1 9 180 -5','tag @s add nightstar_lab_ready','tellraw @s {text:"Nightstar Bloom: original model gallery ready.",color:"aqua"}']
D='saves/ModelLab/datapacks/model_lab/'
js(D+'pack.mcmeta',{'pack':{'description':'Isolated AX model gallery','min_format':[94,1],'max_format':[94,1]}})
put(D+'data/nightstar/function/setup.mcfunction','\n'.join(commands)+'\n')

# Twelve models: four translated copies of the three-model gallery.
stress=list(gallery)
for row in range(4):
 for command in commands:
  if command.startswith('summon minecraft:item_display '):
   tokens=command.split(' ',5);tokens[4]=str(float(tokens[4])-row*4)
   stress.append(' '.join(tokens))
stress.append('tp @s 0 3 13 180 5')
put(D+'data/nightstar/function/stress.mcfunction','\n'.join(stress)+'\n')
ax_commands+=['tp @s 2 1 9 180 -5','tellraw @s {text:"Nightstar Model Lab: AX row (custom_data.model) + fallback item (missing AX model) at x=8.",color:"aqua"}']
put(D+'data/nightstar/function/ax.mcfunction','\n'.join(ax_commands)+'\n')
# Twelve AX-rendered models for load tests.
ax_stress=list(gallery)
for row in range(4):
 for command in ax_commands:
  if command.startswith('summon minecraft:item_display ') and 'nightstar_lab_fallback' not in command:
   tokens=command.split(' ',5);tokens[4]=str(float(tokens[4])-row*4);ax_stress.append(' '.join(tokens))
ax_stress.append('tp @s 0 3 13 180 5')
put(D+'data/nightstar/function/ax_stress.mcfunction','\n'.join(ax_stress)+'\n')
# Version 4 only when an AX animation rig is present, so static galleries stay byte-identical to v3 output.
js(P+'assets/nightstar_bloom/bloom/models.json',{'version':4,'models':manifest,'rigs':rigs} if rigs else {'version':3,'models':manifest})
js('conversion-report.json',{'item':stats,'ax':ax_report})
# Stage every validated file before swapping directories; retain previous valid generation.
R.parent.mkdir(parents=True,exist_ok=True)
stage=pathlib.Path(tempfile.mkdtemp(prefix='gallery-stage-',dir=R.parent))
try:
 for name,data in pending.items():
  p=stage/name;p.parent.mkdir(parents=True,exist_ok=True)
  if isinstance(data,bytes):p.write_bytes(data)
  else:p.write_text(data,encoding='utf-8')
 previous=R.with_name(R.name+'-previous')
 if previous.exists():shutil.rmtree(previous)
 if R.exists():R.rename(previous)
 try:stage.rename(R)
 except Exception:
  if previous.exists():previous.rename(R)
  raise
finally:
 if stage.exists():shutil.rmtree(stage)
print('Exported',len(manifest),'bloom meshes;',sum(s['cubes'] for s in stats),'cubes')

"""Build original crystal examples using Python 3.10+ standard library only.

Run from any directory. Outputs generated/crystals and a ready-to-distribute ZIP.
The authored geometry, texture and animations are original Nightstar examples.
"""
from pathlib import Path
import base64, json, os, struct, subprocess, sys, uuid, zipfile, zlib

ROOT = Path(__file__).resolve().parents[1]

def png(colors):
    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))
    # Four texture bands: metal, saturated crystal, light facet, dark facet.
    raw = b''.join(b'\x00' + b''.join(bytes(colors[x // 4]) for x in range(16)) for _ in range(16))
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB',16,16,8,6,0,0,0)) + chunk(b'IDAT', zlib.compress(raw)) + chunk(b'IEND', b'')

def model(index=0):
    palettes = [((12,29,39,255),(23,178,213,255),(125,235,249,255),(12,86,126,255)),
                ((30,19,43,255),(131,62,228,255),(215,173,255,255),(57,22,119,255)),
                ((39,28,17,255),(228,139,30,255),(255,225,139,255),(124,66,16,255))]
    uid = lambda name: str(uuid.uuid5(uuid.NAMESPACE_URL, 'nightstar-crystal-'+str(index)+'/'+name))
    elements=[]
    def cube(name,low,high,origin,rotation,glow):
        faces={}
        for side in ('north','south','east','west','up','down'):
            band=(2 if side=='up' else 3 if side in ('west','down') else 1) if glow else 0
            faces[side]={'uv':[band*4,0,band*4+4,16],'texture':0}
        elements.append({'uuid':uid(name),'name':name,'type':'cube','export':True,'visibility':True,'box_uv':False,'from':low,'to':high,'origin':origin,'rotation':rotation,'faces':faces})
        return uid(name)
    pedestal=cube('base',[-6,0,-6],[6,2,6],[0,0,0],[0,0,0],False)
    collar=cube('collar',[-4,2,-4],[4,3,4],[0,0,0],[0,0,0],False)
    gem=cube('cut_crystal',[-3,5.6,-3],[3,11.6,3],[0,8.6,0],[0,45,45],True)
    group={'uuid':uid('crystal'),'name':'crystal__bloom','origin':[0,8.6,0],'rotation':[0,0,0],'children':[gem]}
    result={'meta':{'format_version':'5.0','model_format':'bedrock','box_uv':False},'name':'Nightstar Crystal '+str(index),
            'resolution':{'width':16,'height':16},'elements':elements,
            'outliner':[{'uuid':uid('base_group'),'name':'pedestal','origin':[0,0,0],'children':[pedestal,collar]},group,
                        {'uuid':uid('start'),'name':'__flow_start','origin':[0,2,0],'children':[]},
                        {'uuid':uid('end'),'name':'__flow_end','origin':[0,13,0],'children':[]}],
            'textures':[{'uuid':uid('texture'),'name':'crystal.png','id':'0','width':16,'height':16,'uv_width':16,'uv_height':16,
                         'source':'data:image/png;base64,'+base64.b64encode(png(palettes[index])).decode()}],
            'display':{'fixed':{'rotation':[0,0,0],'translation':[0,0,0],'scale':[1,1,1]}}}
    # AX-only motion; vanilla gallery retains the authored rest pose.
    if index==2:
        frames=[]
        for time,rotation,scale in [(0,0,1),(1.5,180,1.12),(3,360,1)]:
            for channel,values in [('rotation',(0,rotation,0)),('scale',(scale,scale,scale))]:
                frames.append({'uuid':uid(channel+str(time)),'channel':channel,'time':time,'interpolation':'linear',
                               'data_points':[dict(zip('xyz',map(str,values)))]})
        result['animations']=[{'uuid':uid('idle'),'name':'idle','loop':'loop','length':3,'animators':{
            uid('crystal'):{'name':'crystal__bloom','type':'bone','keyframes':frames}}}]
    result['groups']=[]
    def collect(g):
        g.update(export=True,visibility=True,mirror_uv=False)
        g.setdefault('rotation',[0,0,0])
        result['groups'].append({k:v for k,v in g.items() if k!='children'})
        return {'uuid':g['uuid'],'children':[collect(c) if isinstance(c,dict) else c for c in g['children']]}
    result['outliner']=[collect(g) for g in result['outliner']]
    return result

def main():
    samples=ROOT/'generated/crystal-source';samples.mkdir(parents=True,exist_ok=True)
    for i in range(3):
        (samples/f'crystal_{i}.bbmodel').write_text(json.dumps(model(i),ensure_ascii=False,indent=2),encoding='utf-8')
    output=ROOT/'generated/crystals'
    env={**os.environ,'NSB_SAMPLES':str(samples),'NSB_OUTPUT':str(output),'NSB_NAME_PREFIX':'crystal_',
         'NSB_AX_PREFIX':'nightstar_crystals/','NSB_DISPLAY_YAW':'20'}
    subprocess.run([sys.executable,str(ROOT/'tools/export_gallery.py')],env=env,check=True)
    readme='''# Nightstar Bloom Crystal Examples 0.3.0

Original models and textures by 阿肆 / as3322909. No third-party weapon assets.

1. Install Fabric 1.21.11, Fabric API and Nightstar Bloom 0.3.0 in your client.
2. Copy resourcepacks/NightstarModelLab into your resourcepacks folder and enable it.
3. In a dedicated creative test world, install saves/ModelLab/datapacks/model_lab as a datapack.
4. Run /reload, then /function nightstar:setup. This builds a gallery near world origin;
   use a test world because it places blocks and removes entities tagged nightstar_lab_model.
5. Try /nsbloom motion static, /nsbloom motion breath, /nsbloom motion flow.
6. /function nightstar:stress creates twelve gallery models. /nsbloom off compares the original.

AX (optional): import resourcepacks/ArcartX/resource/model/nightstar_crystals/*.bbmodel
through your existing AX resource workflow. After handshake/resource load, use
/function nightstar:ax. Amber crystal_2 has a 3-second idle rotation/scale animation.
AX resources cannot be activated merely by copying them into a vanilla resource pack.

BB source is in models/. Edit __bloom groups and __flow_start / __flow_end pivots.
The mod is client-only and does not add real block lighting.
'''
    dist=ROOT/'build/release';dist.mkdir(parents=True,exist_ok=True)
    target=dist/'Nightstar-Bloom-Crystal-Examples-0.3.0.zip'
    with zipfile.ZipFile(target,'w',zipfile.ZIP_DEFLATED) as z:
        for file in sorted(output.rglob('*')):
            if file.is_file() and file.name!='conversion-report.json':z.write(file,file.relative_to(output))
        for file in sorted(samples.glob('*.bbmodel')):z.write(file,'models/'+file.name)
        z.writestr('README.md',readme)
        for name in ('LICENSE','THIRD-PARTY-NOTICES.md'):
            if (ROOT/name).exists():z.write(ROOT/name,name)
        if (ROOT/'licenses/Apache-2.0.txt').exists():z.write(ROOT/'licenses/Apache-2.0.txt','licenses/Apache-2.0.txt')
    print(target)

if __name__=='__main__':main()

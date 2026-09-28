import pathlib,json,math
root=pathlib.Path(__file__).resolve().parents[1]
source=(root/'tools/export_gallery.py').read_text(encoding='utf-8')
# Exercise exporter math without writing output.
ns={'__file__':str(root/'tools/export_gallery.py')};exec(source[:source.index("P='resourcepacks")],ns)
mul,rot,quat=ns['mul'],ns['rot'],ns['quat']
# Independent axis fixtures plus composition (former per-bucket half-turn regression).
for axis,point,expected in [([0,90,0],[1,0,0],[0,0,-1]),([90,0,0],[0,1,0],[0,0,1]),([0,0,90],[1,0,0],[0,1,0])]:
 actual=rot(quat(axis),point)
 assert max(abs(a-b) for a,b in zip(actual,expected))<1e-6
half=quat([0,180,0]);p=[.4,.7,-.2]
actual=rot(mul(half,half),p)
assert max(abs(a-b) for a,b in zip(actual,p))<1e-6
# ax_chain (live-calibrated): display.fixed applies T · rotationXYZ · S, without the centring translates.
chain,_,_=ns['ax_chain']({'display':{'fixed':{'rotation':[0,90,0],'translation':[0,-7.75,0],'scale':[.4,.4,.4]}}},root/'none.bbmodel')
actual=ns['mat_apply'](chain,[1,0,0])
assert max(abs(a-b) for a,b in zip(actual,[0,0.004-.2-7.75/16,-.4]))<1e-6,actual
chain,_,_=ns['ax_chain']({},root/'none.bbmodel')
assert max(abs(a-b) for a,b in zip(ns['mat_apply'](chain,[1,2,3]),[1,2.01,3]))<1e-9
manifest=json.loads((root/'generated/crystals/resourcepacks/NightstarModelLab/assets/nightstar_bloom/bloom/models.json').read_text(encoding='utf-8'))
assert manifest['version']==4
items=[m for m in manifest['models'] if m.get('space','item')=='item'];axs=[m for m in manifest['models'] if m.get('space')=='ax']
assert items and len(axs)==3 and all(m['rig'] in ('rigid','anim') and m['aliases'] for m in axs)
assert any(m['rig']=='anim' for m in axs)
for m in manifest['models']:
 assert m['groups'] and all('__bloom' in g for g in m['groups'])
 assert len(m['vertices'])%15==0 and all(math.isfinite(v) for v in m['vertices'])
 assert len(m['vertices'])>0
 assert 'flow' in m and abs(sum(v*v for v in m['flow']['dir'])-1)<1e-5 and m['flow']['invLength']>0
print('PASS: axis transforms, half-turn compensation, AX display.fixed T-R-S, v4 crystal meshes and animation, finite vertices')

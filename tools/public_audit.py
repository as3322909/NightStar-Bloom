"""Fail-closed public repository audit. Never deletes data or rewrites history.

Preflight checks the index and HEAD. After EACH push run all three remote-* modes.
Unknown paths/assets fail until explicitly reviewed; renaming an asset is not approval.
Requires Python 3.10+, git and authenticated gh. Evidence stays under ignored docs/.
"""
from pathlib import Path, PurePosixPath
import argparse, hashlib, io, json, re, subprocess, tempfile, zipfile

ROOT = Path(__file__).resolve().parents[1]
REPO = 'as3322909/NightStar-Bloom'
ALLOW = json.loads((ROOT/'tools/public-assets.json').read_text(encoding='utf-8'))
TOP = {'.gitignore','AGENTS.md','LICENSE','README.md','THIRD-PARTY-NOTICES.md',
       'build.gradle','settings.gradle','gradle.properties','config-example.json','manifest-v3-example.json'}
TOOLS = {'ax_anim.py','check_export.py','export_gallery.py','make_crystals.py',
         'test_ax_anim.py','test_export.py','public_audit.py','public-assets.json','test_public_audit.py'}
RES = {'fabric.mod.json','nightstar_bloom.json','nightstar_bloom.mixins.json'}
SHADERS = {'blur.fsh','combine.fsh','mask.fsh','mask.vsh','screen.vsh'}

def run(*args, cwd=ROOT):
    return subprocess.check_output(args, cwd=cwd)

def sha(data): return hashlib.sha256(data).hexdigest()

def safe_path(name):
    p=PurePosixPath(name)
    if '\\' in name or p.is_absolute() or '..' in p.parts or ':' in name:
        raise ValueError('Unsafe archive/tree path: '+name)

def source_file(name, data):
    safe_path(name)
    if name in ALLOW['readme']:
        if sha(data)!=ALLOW['readme'][name]: raise ValueError('Unapproved image content: '+name)
        return
    allowed=(name in TOP or name in {'assets/readme/SOURCES.md','licenses/Apache-2.0.txt'}
        or name.startswith('tools/') and name[6:] in TOOLS
        or re.fullmatch(r'src/(main|test)/java/com/nightstar/bloom/(?:[A-Za-z0-9_]+/)*[A-Za-z0-9_]+\.java',name)
        or name.startswith('src/main/resources/') and name[19:] in RES
        or name.startswith('src/main/resources/assets/nightstar_bloom/shaders/core/') and name.rsplit('/',1)[-1] in SHADERS)
    if not allowed: raise ValueError('Unapproved public path: '+name)
    if len(data)>180000: raise ValueError('Oversized source/data: '+name)
    text=data.decode('utf-8-sig')
    if '\x00' in text or re.search(r'[A-Za-z0-9+/]{1024,}={0,2}',text):
        raise ValueError('Binary/encoded payload in source: '+name)
    # JSON model payloads are detected by content even if renamed to a source extension.
    try: obj=json.loads(text)
    except ValueError: obj=None
    if isinstance(obj,dict) and any(k in obj for k in ('outliner','elements','vertices','models','rigs')):
        if name!='manifest-v3-example.json' or sha(data)!=ALLOW['manifestExample']:
            raise ValueError('Unapproved model/geometry payload: '+name)

def archive(data):
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        infos=[i for i in z.infolist() if not i.is_dir()]
        if len({i.filename for i in infos})!=len(infos): raise ValueError('Duplicate archive entries')
        if sum(i.file_size for i in infos)>100_000_000: raise ValueError('Oversized archive')
        for i in infos: safe_path(i.filename)
        return {i.filename:z.read(i) for i in infos}

def artifact(path):
    entries=archive(Path(path).read_bytes())
    if Path(path).suffix=='.jar':
        for n,b in entries.items():
            ok=(n.startswith('com/nightstar/bloom/') and n.endswith('.class')
                or n in RES or n in {'META-INF/MANIFEST.MF','LICENSE','THIRD-PARTY-NOTICES.md','licenses/Apache-2.0.txt'}
                or n.startswith('assets/nightstar_bloom/shaders/core/') and n.rsplit('/',1)[-1] in SHADERS)
            if not ok: raise ValueError('Unapproved JAR entry: '+n)
            if n.endswith('.class') and not b.startswith(b'\xca\xfe\xba\xbe'): raise ValueError('Invalid class: '+n)
            if n=='fabric.mod.json' and json.loads(b)['id']!='nightstar_bloom': raise ValueError('Wrong mod')
    else:
        expected=ALLOW['crystalExamples030']
        if entries.keys()!=expected.keys(): raise ValueError('Unexpected crystal example file set')
        for n,b in entries.items():
            if sha(b)!=expected[n]: raise ValueError('Unapproved crystal example content: '+n)
    return len(entries)

def tree(ref, cwd=ROOT):
    for line in run('git','ls-tree','-rz',ref,cwd=cwd).split(b'\0'):
        if not line: continue
        meta,name=line.split(b'\t',1); mode,kind,oid=meta.split()
        if mode!=b'100644' or kind!=b'blob': raise ValueError('Unsupported tree entry: '+repr(name))
        yield name.decode('utf-8'),oid.decode()

def check_tree(ref,cwd=ROOT,seen=None):
    count=0
    for name,oid in tree(ref,cwd):
        key=(name,oid)
        if seen is None or key not in seen:
            source_file(name,run('git','cat-file','blob',oid,cwd=cwd))
            if seen is not None: seen.add(key)
        count+=1
    return count

def verify_origin():
    remote=run('git','remote','get-url','origin').decode().strip()
    if remote.lower() not in {f'https://github.com/{REPO}.git'.lower(),f'git@github.com:{REPO}.git'.lower()}:
        raise ValueError('Unexpected origin: '+remote)
    info=json.loads(run('gh','repo','view',REPO,'--json','nameWithOwner,visibility'))
    if info['nameWithOwner'].lower()!=REPO.lower() or info['visibility']!='PUBLIC': raise ValueError('Wrong remote identity/visibility')

def source_zip(data):
    files=archive(data); roots={n.split('/')[0] for n in files}
    if len(roots)!=1: raise ValueError('Invalid source archive root')
    for n,b in files.items(): source_file(n.split('/',1)[1],b)
    return len(files)

def main():
    ap=argparse.ArgumentParser();ap.add_argument('mode',choices=['preflight','artifact','remote-current','remote-history','remote-releases']);ap.add_argument('path',nargs='?');a=ap.parse_args()
    result={'mode':a.mode,'repository':REPO}
    if a.mode=='artifact': result['files']=artifact(a.path)
    elif a.mode=='preflight':
        for line in run('git','ls-files','--stage','-z').split(b'\0'):
            if not line: continue
            meta,name=line.split(b'\t',1);mode,oid,stage=meta.split()
            if stage!=b'0' or mode!=b'100644': raise ValueError('Unmerged or non-regular entry')
            source_file(name.decode(),run('git','cat-file','blob',oid.decode()))
        seen=set();commits=run('git','rev-list','HEAD').decode().split()
        for ref in commits: check_tree(ref,seen=seen)
        result.update(commits=len(commits),uniqueFiles=len(seen))
    else:
        verify_origin()
        if a.mode=='remote-current':
            head=json.loads(run('gh','api',f'repos/{REPO}/commits/master'))['sha']
            result.update(sha=head,files=source_zip(run('gh','api',f'repos/{REPO}/zipball/{head}')))
        elif a.mode=='remote-history':
            with tempfile.TemporaryDirectory(prefix='nsbloom-public-audit-') as td:
                run('git','clone','--mirror',f'https://github.com/{REPO}.git',td)
                commits=run('git','rev-list','--all',cwd=td).decode().split();seen=set()
                for ref in commits: check_tree(ref,cwd=td,seen=seen)
                result.update(commits=len(commits),uniqueFiles=len(seen),refs=run('git','show-ref',cwd=td).decode())
        else:
            pages=json.loads(run('gh','api','--paginate','--slurp',f'repos/{REPO}/releases?per_page=100'))
            checked=[]
            with tempfile.TemporaryDirectory(prefix='nsbloom-release-audit-') as td:
                for release in [r for page in pages for r in page]:
                    for item in release['assets']:
                        name=item['name']
                        if not re.fullmatch(r'(?:Nightstar-Bloom-\d+\.\d+\.\d+\.jar|Nightstar-Bloom-Crystal-Examples-0\.3\.0\.zip)',name): raise ValueError('Unapproved release asset: '+name)
                        data=run('gh','api','-H','Accept: application/octet-stream',f"repos/{REPO}/releases/assets/{item['id']}")
                        path=Path(td)/name;path.write_bytes(data);checked.append({'name':name,'files':artifact(path),'sha256':sha(data)})
                    source_zip(run('gh','api',f"repos/{REPO}/zipball/{release['tag_name']}"))
            result['assets']=checked
    result['passed']=True
    print(json.dumps(result,indent=2,ensure_ascii=False))
    out=ROOT/'docs/evidence/public-audit';out.mkdir(parents=True,exist_ok=True)
    (out/(a.mode+'.json')).write_text(json.dumps(result,indent=2,ensure_ascii=False),encoding='utf-8')

if __name__=='__main__': main()

"""Public gate regression: disallowed paths, disguised payloads and archive contents."""
import io, tempfile, unittest, zipfile
from pathlib import Path
import public_audit as a

class PublicAuditTests(unittest.TestCase):
    def test_source(self):
        a.source_file('src/main/java/com/nightstar/bloom/Example.java',b'package com.nightstar.bloom;')
        for name,data in [('assets/weapon.bbmodel',b'{}'),('docs/debug.md',b'private'),
                          ('src/main/java/com/nightstar/bloom/Example.java',b'{"elements":[]}'),
                          ('README.md',b'PK\x00\x00'),('README.md',b'A'*2048),
                          ('assets/readme/crystal-hero.png',b'other texture')]:
            with self.subTest(name=name),self.assertRaises((ValueError,UnicodeError)): a.source_file(name,data)

    def test_archive(self):
        with tempfile.TemporaryDirectory() as d:
            for entries in [{'priv/other/Model.class':b'\xca\xfe\xba\xbe'},
                            {'assets/nightstar_bloom/models/crystal.json':b'{}'},
                            {'../README.md':b'bad'}, {'com/nightstar/bloom/Model.class':b'model bytes'}]:
                p=Path(d)/'test.jar'
                with zipfile.ZipFile(p,'w') as z:
                    for n,b in entries.items():z.writestr(n,b)
                with self.assertRaises(ValueError):a.artifact(p)
            p=Path(d)/'examples.zip'
            with zipfile.ZipFile(p,'w') as z:z.writestr('models/crystal_0.bbmodel',b'{"outliner":[]}')
            with self.assertRaises(ValueError):a.artifact(p)

if __name__=='__main__':unittest.main()

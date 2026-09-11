"""Combine two ABI-identical gomobile bridges and sign independently downloadable cores."""
import argparse, hashlib, io, json, os, re, zipfile
from pathlib import Path

p=argparse.ArgumentParser()
p.add_argument('--stable',required=True)
p.add_argument('--preview',required=True)
p.add_argument('--keystore')
p.add_argument('--revision',type=int,default=3)
a=p.parse_args()
abis=['arm64-v8a','armeabi-v7a','x86','x86_64']
def classes(aar):
    with zipfile.ZipFile(io.BytesIO(aar.read('classes.jar'))) as z:
        return {n:z.read(n) for n in sorted(z.namelist()) if n.endswith('.class')}

key=None
if a.keystore:
    from cryptography.hazmat.primitives import serialization, hashes
    from cryptography.hazmat.primitives.asymmetric import padding
    from cryptography.hazmat.primitives.serialization import pkcs12
    key,cert,_=pkcs12.load_key_and_certificates(Path(a.keystore).read_bytes(),os.environ['KEYSTORE_PASS'].encode())
    public=cert.public_key().public_bytes(serialization.Encoding.DER,serialization.PublicFormat.SubjectPublicKeyInfo)
    assert public==Path('app/src/main/assets/cores/public-key.der').read_bytes(), 'Core signing key does not match the pinned public key'

with zipfile.ZipFile(a.stable) as stable, zipfile.ZipFile(a.preview) as preview:
    c1,c2=classes(stable),classes(preview)
    for binding in [c1,c2]:
        loader=binding['go/Seq.class']
        assert b'io.nekohasekai.sagernet.update.CoreRuntime' in loader and b'loadLibrary' not in loader, 'Unpatched native core loader'
    if c1!=c2:
        raise SystemExit('Stable and preview JNI bridge classes differ: '+str([n for n in c1.keys()|c2.keys() if c1.get(n)!=c2.get(n)]))
    bridge=hashlib.sha256(b''.join(n.encode()+b'\0'+data for n,data in c1.items())).hexdigest()
    bundled={'bridge':bridge}
    env=Path('buildScript/lib/core/get_source_env.sh').read_text()
    versions=re.findall(r'export CORE_VERSION="([^"]+)"',env)
    assert len(versions)==2
    for channel,aar,version in zip(['stable','preview'],[stable,preview],versions):
        metadata={'schema':1,'channel':channel,'version':version,'revision':a.revision,'bridge':bridge,'assets':{}}
        destination=Path('core-build/dist')/channel
        destination.mkdir(parents=True,exist_ok=True)
        for abi in abis:
            binary=aar.read(f'jni/{abi}/libgojni.so')
            assert binary[:4]==b'\x7fELF'
            assert f'{version}-arcaenbox-{a.revision}'.encode() in binary, f'Wrong version in {channel}/{abi}'
            name=f'ArcaenBox-core-{channel}-{version}-{abi}.so'
            (destination/name).write_bytes(binary)
            metadata['assets'][abi]={'name':name,'size':len(binary),'sha256':hashlib.sha256(binary).hexdigest()}
            if channel=='preview':
                target=Path('app/src/main/jniLibs')/abi/'libgojni_preview.so'
                target.parent.mkdir(parents=True,exist_ok=True); target.write_bytes(binary)
        content=json.dumps(metadata,sort_keys=True,separators=(',',':')).encode()
        (destination/'manifest.json').write_bytes(content)
        if key:
            (destination/'manifest.sig').write_bytes(key.sign(content,padding.PKCS1v15(),hashes.SHA256()))
        bundled[channel]={'version':version,'revision':a.revision}
    target=Path('app/src/main/assets/cores/bundled.json'); target.parent.mkdir(parents=True,exist_ok=True)
    target.write_text(json.dumps(bundled,indent=2)+'\n')
    target=Path('app/libs/libcore.aar'); target.parent.mkdir(parents=True,exist_ok=True)
    target.write_bytes(Path(a.stable).read_bytes())
print(json.dumps(bundled))

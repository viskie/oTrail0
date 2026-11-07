#!/usr/bin/env python3
"""Publish a new host template/source selection without changing any previous toolchain or module."""
import base64,hashlib,importlib.util,json,pathlib,subprocess,sys,zipfile
spec=importlib.util.spec_from_file_location('god',pathlib.Path(__file__).with_name('build-god.py'));god=importlib.util.module_from_spec(spec);spec.loader.exec_module(god)
version=sys.argv[1];code=int(sys.argv[2]);r=god.RELEASES;source,baseline=god.source(version);envelope=json.loads((r/'catalog.json').read_text());payload=base64.b64decode(envelope['payload']);key=pathlib.Path.home()/'.local/share/otrail/module-signing.pem'
pub=god.BUILD/'toolchain-public.pem';pub.write_bytes(subprocess.check_output(['openssl','pkey','-in',str(key),'-pubout']));sig=god.BUILD/'toolchain.sig';sig.write_bytes(base64.b64decode(envelope['signature']));subprocess.run(['openssl','dgst','-sha256','-verify',str(pub),'-signature',str(sig)],input=payload,check=True)
catalog=json.loads(payload);chain=json.loads(json.dumps(catalog['toolchains'][0]));chain['id']='god-java-'+version+'-v1';chain['name']='GOD in-app Java toolchain · host '+version;chain['minApp']=code;chain['sourceVersion']=version;chain['baselineSha256']=baseline
prefix='https://raw.githubusercontent.com/viskie/oTrail0/main/modules/releases/'
chain['sources']=[dict(version=version,url=prefix+source.name,sha256=god.sha(source),sizeBytes=source.stat().st_size)]
apk=god.ROOT/'releases'/('oTraiL-'+version+'-debug.apk');chain['tools']=[t for t in chain['tools'] if t.get('target')!='template.apk'];chain['tools'].append(dict(file='template-'+version+'.apk',url='https://raw.githubusercontent.com/viskie/oTrail0/main/releases/'+apk.name,sha256=god.sha(apk),sizeBytes=apk.stat().st_size,target='template.apk'));chain['toolFiles']['template.apk']=god.sha(apk)
if any(t['id']==chain['id'] for t in catalog['toolchains']):raise SystemExit('Toolchain already exists')
catalog['toolchains'].insert(0,chain);data=json.dumps(catalog,sort_keys=True,separators=(',',':')).encode();signature=subprocess.check_output(['openssl','dgst','-sha256','-sign',str(key)],input=data)
(r/'catalog.json').write_text(json.dumps(dict(payload=base64.b64encode(data).decode(),signature=base64.b64encode(signature).decode()),indent=2)+'\n');(r/'SHA256SUMS').write_text(''.join(god.sha(f)+'  '+f.name+'\n' for f in sorted(r.iterdir()) if f.is_file() and f.name not in {'SHA256SUMS','catalog.json'}))
print('Added',chain['id'])

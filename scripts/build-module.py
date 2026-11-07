#!/usr/bin/env python3
"""Compile a separate module and append its version to the signed catalog, preserving old artifacts."""
import argparse,base64,hashlib,json,os,pathlib,subprocess,tempfile,zipfile
root=pathlib.Path(__file__).resolve().parents[1];releases=root/'modules/releases'
p=argparse.ArgumentParser();p.add_argument('module',choices=['god','cam']);p.add_argument('version');args=p.parse_args()
sdk=pathlib.Path(os.environ['ANDROID_HOME']);java=pathlib.Path(os.environ['JAVA_HOME']);classes=root/'app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes'
name='GOD' if args.module=='god' else 'CAM';mid='mod1' if args.module=='god' else 'mod2';entry='in.otrail.extensions.mod1.SourceStudio' if args.module=='god' else 'in.otrail.extensions.cam.CameraModule'
build=root/f'modules/{mid}/build/package-{args.version}';build.mkdir(parents=True,exist_ok=True);compiled=build/'classes';compiled.mkdir(exist_ok=True)
subprocess.run([str(java/'bin/javac'),'-source','8','-target','8','-Xlint:-options','-classpath',str(sdk/'platforms/android-37.0/android.jar')+os.pathsep+str(classes),'-d',str(compiled),*map(str,(root/f'modules/{mid}/src').rglob('*.java'))],check=True)
jar=build/'module.jar'
with zipfile.ZipFile(jar,'w',zipfile.ZIP_DEFLATED) as z:
 for file in compiled.rglob('*.class'):z.write(file,file.relative_to(compiled).as_posix())
subprocess.run([str(java/'bin/java'),'-Xmx512m','-cp',str(sdk/'build-tools/36.0.0/lib/d8.jar'),'com.android.tools.r8.D8','--min-api','30','--lib',str(sdk/'platforms/android-37.0/android.jar'),'--classpath',str(classes),'--output',str(build),str(jar)],check=True)
manifest=dict(id=mid,name=name,version=args.version,api=1,minApp=23 if args.module=='god' else 24,minSdk=35 if args.module=='god' else 30,entry=entry)
output=releases/f'{name}-{args.version}.oTrail0'
if output.exists():raise SystemExit('Published module versions are immutable; use a new version')
with zipfile.ZipFile(output,'w',zipfile.ZIP_DEFLATED) as z:
 z.write(build/'classes.dex','classes.dex');z.writestr('module.json',json.dumps(manifest));z.write(root/f'modules/{mid}/README.md','README.md')
 if args.module=='god':
  with zipfile.ZipFile(releases/'GOD-1.0.0.oTrail0') as old:z.writestr('studio.json',old.read('studio.json'))
envelope=json.loads((releases/'catalog.json').read_text());payload=base64.b64decode(envelope['payload']);key=pathlib.Path.home()/'.local/share/otrail/module-signing.pem'
public=subprocess.check_output(['openssl','pkey','-in',str(key),'-pubout']);pub=build/'public.pem';pub.write_bytes(public);sig=build/'signature';sig.write_bytes(base64.b64decode(envelope['signature']))
subprocess.run(['openssl','dgst','-sha256','-verify',str(pub),'-signature',str(sig)],input=payload,check=True)
catalog=json.loads(payload)
release=dict(manifest,url='https://raw.githubusercontent.com/viskie/oTrail0/main/modules/releases/'+output.name,sha256=hashlib.sha256(output.read_bytes()).hexdigest(),sizeBytes=output.stat().st_size,notes='Three-column landscape controls; existing GOD settings, drafts, sources and toolchains retained.' if args.module=='god' else 'Camera2 photo capture, geotags/time/stop metadata, photo index, stop albums and configurable public photo folder. Controls follow camera capabilities.')
if args.module=='god':
 previous=next(item for item in catalog['versions'] if item['id']=='mod1');release.update(installSalt=previous['installSalt'],installHash=previous['installHash'])
assert not any((r['id'],r['version'])==(mid,args.version) for r in catalog['versions'])
catalog['versions'].insert(0,release);data=json.dumps(catalog,sort_keys=True,separators=(',',':')).encode();signature=subprocess.check_output(['openssl','dgst','-sha256','-sign',str(key)],input=data)
(releases/'catalog.json').write_text(json.dumps(dict(payload=base64.b64encode(data).decode(),signature=base64.b64encode(signature).decode()),indent=2)+'\n')
(releases/'SHA256SUMS').write_text(''.join(hashlib.sha256(f.read_bytes()).hexdigest()+'  '+f.name+'\n' for f in sorted(releases.iterdir()) if f.is_file() and f.name not in {'SHA256SUMS','catalog.json'}))
print('Packaged',output.name)

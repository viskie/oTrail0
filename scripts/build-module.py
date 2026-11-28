#!/usr/bin/env python3
"""Compile a separate module and append its version to the signed catalog, preserving old artifacts."""
import argparse,base64,hashlib,json,os,pathlib,subprocess,tempfile,zipfile
root=pathlib.Path(__file__).resolve().parents[1];releases=root/'modules/releases'
p=argparse.ArgumentParser();p.add_argument('module',choices=['god','cam','bt','logger','reports','pop3']);p.add_argument('version');args=p.parse_args()
sdk=pathlib.Path(os.environ['ANDROID_HOME']);java=pathlib.Path(os.environ['JAVA_HOME']);classes=root/'app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes'
modules={'god':('GOD','mod1','in.otrail.extensions.mod1.SourceStudio',23,35),
         'cam':('CAM','mod2','in.otrail.extensions.cam.CameraModule',24,30),
         'bt':('BTKEYBOARD','mod4','in.otrail.extensions.btkeyboard.BtKeyboardModule',57,30),
         'logger':('LOGGER','mod6','in.otrail.extensions.logger.LoggerModule',58,30),
         'reports':('REPORTS','mod5','in.otrail.extensions.reports.ReportsModule',53,30),
         'pop3':('POP3','mod7','in.otrail.extensions.pop3.Pop3Module',53,30)}
name,mid,entry,min_app,min_sdk=modules[args.module]
build=root/f'modules/{mid}/build/package-{args.version}';build.mkdir(parents=True,exist_ok=True);compiled=build/'classes';compiled.mkdir(exist_ok=True)
subprocess.run([str(java/'bin/javac'),'-source','8','-target','8','-Xlint:-options','-classpath',str(sdk/'platforms/android-37.0/android.jar')+os.pathsep+str(classes),'-d',str(compiled),*map(str,(root/f'modules/{mid}/src').rglob('*.java'))],check=True)
jar=build/'module.jar'
with zipfile.ZipFile(jar,'w',zipfile.ZIP_DEFLATED) as z:
 for file in compiled.rglob('*.class'):z.write(file,file.relative_to(compiled).as_posix())
subprocess.run([str(java/'bin/java'),'-Xmx512m','-cp',str(sdk/'build-tools/36.0.0/lib/d8.jar'),'com.android.tools.r8.D8','--min-api','30','--lib',str(sdk/'platforms/android-37.0/android.jar'),'--classpath',str(classes),'--output',str(build),str(jar)],check=True)
manifest=dict(id=mid,name=name,version=args.version,api=1,minApp=min_app,minSdk=min_sdk,entry=entry)
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
notes={'god':'Three-column landscape controls; existing GOD settings, drafts, sources and toolchains retained.',
       'cam':'Camera2 photo capture, geotags/time/stop metadata, photo index, stop albums and configurable public photo folder. Controls follow camera capabilities.',
       'bt':'Integrated Bluetooth HID remote with in-app scan, pairing, connection, error reporting, text/key input, trackpad and media keys. No companion APK is bundled or installed.',
       'logger':'Host-observed module lifecycle, task and crash log with searchable UI, error filter and text export.',
       'reports':'Interactive daily, weekly, monthly and custom trail reports with distance cards, a tappable activity chart and derived insights from recorded GPS data.',
       'pop3':'Multi-mailbox POP3 client with an offline inbox per account, encrypted transport and automatic sync whenever the module opens.'}[args.module]
release=dict(manifest,url='https://raw.githubusercontent.com/viskie/oTrail0/main/modules/releases/'+output.name,sha256=hashlib.sha256(output.read_bytes()).hexdigest(),sizeBytes=output.stat().st_size,notes=notes)
if args.module=='god':
 previous=next(item for item in catalog['versions'] if item['id']=='mod1');release.update(installSalt=previous['installSalt'],installHash=previous['installHash'])
assert not any((r['id'],r['version'])==(mid,args.version) for r in catalog['versions'])
catalog['versions'].insert(0,release);data=json.dumps(catalog,sort_keys=True,separators=(',',':')).encode();signature=subprocess.check_output(['openssl','dgst','-sha256','-sign',str(key)],input=data)
(releases/'catalog.json').write_text(json.dumps(dict(payload=base64.b64encode(data).decode(),signature=base64.b64encode(signature).decode()),indent=2)+'\n')
(releases/'SHA256SUMS').write_text(''.join(hashlib.sha256(f.read_bytes()).hexdigest()+'  '+f.name+'\n' for f in sorted(releases.iterdir()) if f.is_file() and f.name not in {'SHA256SUMS','catalog.json'}))
print('Packaged',output.name)

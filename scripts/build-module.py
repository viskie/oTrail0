#!/usr/bin/env python3
"""Compile a separate module and append its version to the signed catalog, preserving old artifacts."""
import argparse,base64,hashlib,json,os,pathlib,subprocess,tempfile,zipfile
root=pathlib.Path(__file__).resolve().parents[1];releases=root/'modules/releases'
p=argparse.ArgumentParser();p.add_argument('module',choices=['god','cam','bt','logger','reports','pop3','files','phone','sms','browser','opay']);p.add_argument('version');args=p.parse_args()
sdk=pathlib.Path(os.environ['ANDROID_HOME']);java=pathlib.Path(os.environ['JAVA_HOME']);classes=root/'app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes'
modules={'god':('GOD','mod1','in.otrail.extensions.mod1.SourceStudio',23,35),
         'cam':('CAM','mod2','in.otrail.extensions.cam.CameraModule',24,30),
         'bt':('BTKEYBOARD','mod4','in.otrail.extensions.btkeyboard.BtKeyboardModule',57,30),
         'logger':('LOGGER','mod6','in.otrail.extensions.logger.LoggerModule',58,30),
         'reports':('REPORTS','mod5','in.otrail.extensions.reports.ReportsModule',53,30),
         'pop3':('POP3','mod7','in.otrail.extensions.pop3.Pop3Module',53,30),
         'files':('FILES','mod8','in.otrail.extensions.files.FileManagerModule',53,30),
         'phone':('PHONE','mod9','in.otrail.extensions.phone.PhoneModule',63,30),
         'sms':('SMS','mod10','in.otrail.extensions.sms.SmsModule',63,30),
         'browser':('BROWSER','mod11','in.otrail.extensions.browser.BrowserModule',53,30),
         'opay':('OPAY','mod12','in.otrail.extensions.opay.OpayModule',64,30)}
name,mid,entry,min_app,min_sdk=modules[args.module]
# Vendored pure-Java libraries (for example the ZXing QR codec) are linked into the
# module DEX because a downloaded module cannot rely on host-only classes.
libs=sorted((root/f'modules/{mid}/libs').glob('*.jar'))
build=root/f'modules/{mid}/build/package-{args.version}';build.mkdir(parents=True,exist_ok=True);compiled=build/'classes';compiled.mkdir(exist_ok=True)
subprocess.run([str(java/'bin/javac'),'-source','8','-target','8','-Xlint:-options','-classpath',os.pathsep.join([str(sdk/'platforms/android-37.0/android.jar'),str(classes),*map(str,libs)]),'-d',str(compiled),*map(str,(root/f'modules/{mid}/src').rglob('*.java'))],check=True)
jar=build/'module.jar'
with zipfile.ZipFile(jar,'w',zipfile.ZIP_DEFLATED) as z:
 for file in compiled.rglob('*.class'):z.write(file,file.relative_to(compiled).as_posix())
 for lib in libs:
  with zipfile.ZipFile(lib) as source:
   for member in source.namelist():
    if member.endswith('.class') and not member.startswith('META-INF/'):z.writestr(member,source.read(member))
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
       'pop3':'Multi-mailbox POP3 client with an offline inbox per account, encrypted transport and automatic sync whenever the module opens.',
       'files':'File manager for the installed Debian root filesystem and phone storage with copy, move, rename, delete, text preview and an offline PDF viewer.',
       'phone':'Car-style large-touch dialer with keypad, contacts, call history and in-module call, contacts and call-log permissions.',
       'sms':'Car-style SMS inbox, conversation threads and compose/reply with in-module read, send and contacts permissions.',
       'browser':'WebView browser with a collapsible top 40% developer panel: console, network timings, JavaScript evaluation and page info.',
       'opay':'Offline-first UPI payment, QR, ledger, statement import, reconciliation and reporting module. No TPAP registration, gateway or NPCI API; payments are handed to installed UPI apps.'}[args.module]
release=dict(manifest,url='https://raw.githubusercontent.com/viskie/oTrail0/main/modules/releases/'+output.name,sha256=hashlib.sha256(output.read_bytes()).hexdigest(),sizeBytes=output.stat().st_size,notes=notes)
if args.module=='god':
 previous=next(item for item in catalog['versions'] if item['id']=='mod1');release.update(installSalt=previous['installSalt'],installHash=previous['installHash'])
assert not any((r['id'],r['version'])==(mid,args.version) for r in catalog['versions'])
catalog['versions'].insert(0,release);data=json.dumps(catalog,sort_keys=True,separators=(',',':')).encode();signature=subprocess.check_output(['openssl','dgst','-sha256','-sign',str(key)],input=data)
(releases/'catalog.json').write_text(json.dumps(dict(payload=base64.b64encode(data).decode(),signature=base64.b64encode(signature).decode()),indent=2)+'\n')
(releases/'SHA256SUMS').write_text(''.join(hashlib.sha256(f.read_bytes()).hexdigest()+'  '+f.name+'\n' for f in sorted(releases.iterdir()) if f.is_file() and f.name not in {'SHA256SUMS','catalog.json'}))
print('Packaged',output.name)

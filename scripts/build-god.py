#!/usr/bin/env python3
"""Build GOD independently; only loader/API enter the main APK. Private keys stay outside Git."""
import argparse,base64,hashlib,io,json,os,pathlib,shutil,subprocess,sys,urllib.request,xml.etree.ElementTree as ET,zipfile
ROOT=pathlib.Path(__file__).resolve().parents[1]
BUILD=ROOT/'modules/mod1/build'; BUILD.mkdir(parents=True,exist_ok=True)
RELEASES=ROOT/'modules/releases'; RELEASES.mkdir(exist_ok=True)
SDK=pathlib.Path(os.environ['ANDROID_HOME']);JAVA=pathlib.Path(os.environ['JAVA_HOME'])
CLASSES=ROOT/'app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes'
D8=SDK/'build-tools/36.0.0/lib/d8.jar'; SIGNER=SDK/'build-tools/36.0.0/lib/apksigner.jar'
LIBS=BUILD/'libraries';LIBS.mkdir(exist_ok=True)
MAVEN={'ecj':('org/eclipse/jdt/ecj/3.38.0','ecj-3.38.0.jar'),
'jgit':('org/eclipse/jgit/org.eclipse.jgit/6.10.0.202406032230-r','org.eclipse.jgit-6.10.0.202406032230-r.jar'),
'jgit-ssh':('org/eclipse/jgit/org.eclipse.jgit.ssh.jsch/6.10.0.202406032230-r','org.eclipse.jgit.ssh.jsch-6.10.0.202406032230-r.jar'),
'jsch':('com/github/mwiede/jsch/0.2.21','jsch-0.2.21.jar'),
'slf4j':('org/slf4j/slf4j-api/1.7.36','slf4j-api-1.7.36.jar'),
'ewah':('com/googlecode/javaewah/JavaEWAH/1.2.3','JavaEWAH-1.2.3.jar'),
'codec':('commons-codec/commons-codec/1.17.0','commons-codec-1.17.0.jar')}
MODULE_MAVEN={'compress':('org/apache/commons/commons-compress/1.28.0','commons-compress-1.28.0.jar'),
'xz':('org/tukaani/xz/1.10','xz-1.10.jar'),
'compress-io':('commons-io/commons-io/2.20.0','commons-io-2.20.0.jar'),
'compress-lang':('org/apache/commons/commons-lang3/3.18.0','commons-lang3-3.18.0.jar'),
'compress-codec':('commons-codec/commons-codec/1.19.0','commons-codec-1.19.0.jar')}
SORA={'sora-editor':('io/github/rosemoe/editor/0.24.7','editor-0.24.7.aar'),
'sora-textmate':('io/github/rosemoe/language-textmate/0.24.7','language-textmate-0.24.7.aar')}
LEGACY_CATALOG_PUBLIC_KEY='MIIBojANBgkqhkiG9w0BAQEFAAOCAY8AMIIBigKCAYEA8R2LWyNyPH3GUwMwa/jT7Mhpm7FbS7UdnzfcV75HHX04iwn6+ziCqwTuN5okMR2fGJFf5D0WwgjTzHxTZVKVEHiblWipd07j9dMZr4mUUt/Zgg5eVe4aH/BfNBTXWoMXPkyh4wnPDMtn/ZYlowijFBKhb16YzSffAEENlupsZW3sxHLyFVw6JNvULFCOwCzU8nl+gJUxSwqgqiGWL0C+VeloZl1UWBpcUHSPtcKUn0Ynkhru6jSDmLvF8XyTWz7/0dVQgCi4Al5HA1nxcoenqKJNFsqgv1QRf74Y1bDXrJ3Lpfa+z6Um01k7YXRQdHtC5y3Kge02bxQn5vfYG9GYm4+J6KyEQJBxyx+/ptB4p9Juap63zjJ3xN6xI7cKKqDP6CiByA0nAFBLzfv3fY2ildKmYIpvK/JYhhnKpUB4Sc8Hhnw6PnZnyvFvA7B/Brsgvv22uVm8/4gkA+kddIkgCAjBHKYRnuL/lm406kD2zkfx9XcIh77ROFlx4xpRwrVRAgMBAAE='
def run(args):subprocess.run([str(x) for x in args],cwd=ROOT,check=True)
def sha(path):
 h=hashlib.sha256()
 with open(path,'rb') as f:
  for block in iter(lambda:f.read(1024*1024),b''):h.update(block)
 return h.hexdigest()
def download(url,path):
 if not path.exists():
  print('Download',path.name,flush=True);subprocess.run(['curl','-L','-f','--retry','3','--connect-timeout','15','--max-time','240','-sS',url,'-o',str(path)],check=True)
 return path
def zipfiles(path,files):
 with zipfile.ZipFile(path,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as out:
  for name,file in files.items():out.write(file,name)
def jar(path,folder):zipfiles(path,{p.relative_to(folder).as_posix():p for p in folder.rglob('*.class')})
def javac(source,out,classpath):
 if out.exists():shutil.rmtree(out)
 out.mkdir(parents=True)
 run([JAVA/'bin/javac','-source','8','-target','8','-Xlint:-options','-encoding','UTF-8','-classpath',os.pathsep.join(map(str,classpath)),'-d',out,*source])
def fingerprint(jars):
 h=hashlib.sha256()
 for file in jars:
  with zipfile.ZipFile(file) as z:
   for name in sorted(z.namelist()):
    if name.endswith('.class'):h.update(name.encode());h.update(z.read(name))
 return h.hexdigest()
def dex(name,jars,classpath=()):
 out=BUILD/name
 signature=fingerprint(jars)
 stamp=out/'.fingerprint'
 if stamp.exists() and stamp.read_text()==signature and list(out.glob('*.dex')):return out
 if out.exists():shutil.rmtree(out)
 out.mkdir()
 args=[JAVA/'bin/java','-Xmx1800m','-cp',D8,'com.android.tools.r8.D8','--min-api','30','--lib',SDK/'platforms/android-37.0/android.jar','--output',out]
 for item in classpath:args+=['--classpath',item]
 run(args+list(jars))
 files=list(out.glob('*.dex'))
 if name=='module-code' and len(files)!=1:raise RuntimeError(name+' exceeds single DEX; split toolchain code before publishing')
 stamp.write_text(signature)
 return out

def prepare():
 for name,(base,file) in MAVEN.items():download('https://repo.maven.apache.org/maven2/'+base+'/'+file,LIBS/(name+'.jar'))
 for name,(base,file) in MODULE_MAVEN.items():download('https://repo.maven.apache.org/maven2/'+base+'/'+file,LIBS/(name+'.jar'))
 for name,(base,file) in SORA.items():
  aar=download('https://repo.maven.apache.org/maven2/'+base+'/'+file,LIBS/file)
  with zipfile.ZipFile(aar) as archive:(LIBS/(name+'.jar')).write_bytes(archive.read('classes.jar'))
 # Public Java compiler APIs are absent on ART; include the OpenJDK java.compiler API under its Classpath Exception.
 api=BUILD/'java-compiler-api.jar'
 with zipfile.ZipFile(JAVA/'jmods/java.compiler.jmod') as source,zipfile.ZipFile(api,'w',zipfile.ZIP_DEFLATED) as out:
  for name in source.namelist():
   if name.startswith('classes/javax/') and name.endswith('.class'):out.writestr(name[len('classes/'):],source.read(name))
 stubs=BUILD/'bootstrap-stubs';stubs.mkdir(exist_ok=True)
 run([JAVA/'bin/javac','--patch-module','java.base='+str(ROOT/'modules/mod1/compiler-stubs'),'-source','17','-target','17','-d',stubs,*list((ROOT/'modules/mod1/compiler-stubs').rglob('*.java'))])
 jar(BUILD/'compiler-stubs.jar',stubs)
 runtime=[]
 for line in (ROOT/'app/build/god-runtime-paths.txt').read_text().splitlines():
  path=pathlib.Path(line)
  if path.suffix=='.aar':
   with zipfile.ZipFile(path) as archive:
    for member in archive.namelist():
     if member=='classes.jar' or member.startswith('libs/') and member.endswith('.jar'):
      dest=BUILD/('runtime-'+hashlib.sha256((line+member).encode()).hexdigest()[:16]+'.jar');dest.write_bytes(archive.read(member));runtime.append(dest)
  elif path.suffix=='.jar':runtime.append(path)
 runtime.append(ROOT/'app/build/intermediates/compile_and_runtime_r_class_jar/debug/processDebugResources/R.jar')
 merged=BUILD/'classpath.jar';seen={}
 with zipfile.ZipFile(merged,'w',zipfile.ZIP_DEFLATED) as out:
  for source in runtime:
   with zipfile.ZipFile(source) as z:
    for name in z.namelist():
     if not name.endswith('.class') or name=='module-info.class' or name.startswith('META-INF/versions/'):continue
     content=z.read(name)
     if name in seen:
      if seen[name]!=hashlib.sha256(content).digest():raise RuntimeError('Conflicting dependency '+name)
      continue
     seen[name]=hashlib.sha256(content).digest();out.writestr(name,content)
 deps=dex('dependency-dex',[merged]);zipfiles(BUILD/'dependency-dex.zip',{p.name:p for p in deps.glob('*.dex')})
 compilerclasses=BUILD/'compiler-classes';javac([ROOT/'modules/mod1/compiler/in/otrail/extensions/god/compiler/CompilerEngine.java'],compilerclasses,[SDK/'platforms/android-37.0/android.jar',CLASSES,LIBS/'ecj.jar',D8,SIGNER]);jar(BUILD/'compiler-engine.jar',compilerclasses)
 compiler=dex('compiler-code',[BUILD/'compiler-engine.jar',LIBS/'ecj.jar',D8,SIGNER,api],[CLASSES])
 pubclasses=BUILD/'publisher-classes';javac([ROOT/'modules/mod1/compiler/in/otrail/extensions/god/compiler/GitPublisher.java'],pubclasses,[SDK/'platforms/android-37.0/android.jar',CLASSES,*[LIBS/(name+'.jar') for name in MAVEN if name!='ecj']]);jar(BUILD/'publisher-engine.jar',pubclasses)
 publisher=dex('publisher-code',[BUILD/'publisher-engine.jar',*[LIBS/(name+'.jar') for name in MAVEN if name!='ecj']],[CLASSES])
 licenses=BUILD/'LICENSES';licenses.mkdir(exist_ok=True)
 (licenses/'README.txt').write_text('GOD toolchain dependencies\nECJ 3.38 EPL-2.0: https://github.com/eclipse-jdt/eclipse.jdt.core\nD8 BSD-3-Clause: https://r8.googlesource.com/r8\nApksig Apache-2.0: https://android.googlesource.com/platform/tools/apksig\nOpenJDK java.compiler GPLv2 + Classpath Exception: https://openjdk.org/legal/gplv2+ce.html\nJGit EDL-1.0: https://www.eclipse.org/jgit/\nJSch BSD: https://github.com/mwiede/jsch\nSLF4J MIT: https://www.slf4j.org/license.html\nJavaEWAH Apache-2.0: https://github.com/lemire/javaewah\nCommons Codec, Compress, IO and Lang Apache-2.0: https://commons.apache.org/\nXZ for Java public domain: https://tukaani.org/xz/java.html\nAndroid platform downloaded directly from Google after license acceptance.\n')
 for name,(base,file) in {**MAVEN,**MODULE_MAVEN}.items():
  with zipfile.ZipFile(LIBS/(name+'.jar')) as z:
   for entry in z.namelist():
    if not entry.endswith('/') and any(x in entry.upper() for x in ['LICENSE','NOTICE','COPYING','ABOUT.HTML']):
     (licenses/(name+'-'+entry.replace('/','_'))).write_bytes(z.read(entry))
 legal=JAVA/'legal/java.compiler'
 if legal.exists():
  for p in legal.iterdir():
   if p.is_file():(licenses/('openjdk-'+p.name)).write_bytes(p.read_bytes())
 zipfiles(BUILD/'compiler.jar',{p.name:p for p in compiler.glob('*.dex')})
 zipfiles(BUILD/'publisher.jar',{p.name:p for p in publisher.glob('*.dex')})
 zipfiles(RELEASES/'god-compiler-1.0.0.zip',{'compiler.jar':BUILD/'compiler.jar',**{'LICENSES/'+p.name:p for p in licenses.iterdir()}})
 zipfiles(RELEASES/'god-publisher-1.0.0.zip',{'publisher.jar':BUILD/'publisher.jar',**{'LICENSES/'+p.name:p for p in licenses.iterdir()}})
 zipfiles(RELEASES/'god-dependencies-0.4.0.zip',{'classpath.jar':merged,'compiler-stubs.jar':BUILD/'compiler-stubs.jar','dependency-dex.zip':BUILD/'dependency-dex.zip',**{'LICENSES/'+p.name:p for p in licenses.iterdir()}})
 # SDK stays at its official distribution URL, not bundled into the app or module.
 xml=ET.fromstring(urllib.request.urlopen('https://dl.google.com/android/repository/repository2-3.xml',timeout=60).read())
 archive=None
 for package in xml.findall('remotePackage'):
  if package.get('path')=='platforms;android-37.0':archive=package.find('archives/archive/complete');break
 if archive is None:raise RuntimeError('Android 37 SDK not found in Google catalog')
 url='https://dl.google.com/android/repository/'+archive.findtext('url');platform=download(url,BUILD/'google-platform.zip')
 with zipfile.ZipFile(platform) as z:entry=next(n for n in z.namelist() if n.endswith('/android.jar'))
 toolFiles={'compiler.jar':sha(BUILD/'compiler.jar'),'publisher.jar':sha(BUILD/'publisher.jar'),'android.jar':sha(SDK/'platforms/android-37.0/android.jar'),'classpath.jar':sha(merged),'compiler-stubs.jar':sha(BUILD/'compiler-stubs.jar'),'dependency-dex.zip':sha(BUILD/'dependency-dex.zip')}
 state={'toolFiles':toolFiles,'sdk':{'file':'google-platform-37.zip','url':url,'sha256':sha(platform),'sizeBytes':platform.stat().st_size,'extractFiles':{entry:'android.jar'}}}
 (BUILD/'prepared.json').write_text(json.dumps(state,indent=2));print('Tool packs prepared',flush=True)

def source(version="0.4.28"):
 paths=subprocess.check_output(['git','ls-files','-z'],cwd=ROOT).decode().split('\0')
 include=[]
 for name in paths:
  if not name or any(part in {'releases','.git','build','.gradle','.idea'} for part in pathlib.PurePosixPath(name).parts) or name=='android-project-sources.txt' or name.endswith(('.apk','.oTrail0','.map','.keystore','.jks')):continue
  file=ROOT/name
  if file.is_file():include.append(name)
 protected={}
 for name in include:
  if name in {'app/build.gradle','settings.gradle','build.gradle','gradle.properties','app/src/main/AndroidManifest.xml'} or name.startswith('app/src/main/res/') or (name.startswith('third_party/brouter/') or name.startswith('modules/mod1/') or name.startswith('modules/mod2/')) and name.endswith('.java'):protected[name]=sha(ROOT/name)
 baseline={'version':version,'sourceRoots':['app/src/main/java','modules/src/main/java','modules/api/src/main/java'],'protected':protected}
 raw=json.dumps(baseline,sort_keys=True,indent=2).encode()
 path=RELEASES/('otrail-source-'+version+'.zip')
 with zipfile.ZipFile(path,'w',zipfile.ZIP_DEFLATED) as out:
  for name in include:out.write(ROOT/name,name)
  out.writestr('.studio-baseline.json',raw)
 return path,hashlib.sha256(raw).hexdigest()

def package(debian_only=False):
 state=json.loads((BUILD/'prepared.json').read_text());sourcezip,baseline=source('0.4.28')
 prefix='https://raw.githubusercontent.com/viskie/oTrail0/main/modules/releases/'
 def artifact(path,**kwargs):return dict(file=path.name,url=prefix+path.name,sha256=sha(path),sizeBytes=path.stat().st_size,**kwargs)
 tools=[artifact(RELEASES/'god-compiler-1.0.0.zip',archive=True),artifact(RELEASES/'god-publisher-1.0.0.zip',archive=True),artifact(RELEASES/'god-dependencies-0.4.0.zip',archive=True),state['sdk']]
 apk=ROOT/'releases/oTraiL-0.4.28-debug.apk'
 tools.append(dict(file='template-0.4.0.apk',url='https://raw.githubusercontent.com/viskie/oTrail0/main/releases/'+apk.name,sha256=sha(apk),sizeBytes=apk.stat().st_size,target='template.apk'))
 checks=state['toolFiles'];checks['template.apk']=sha(apk)
 request=urllib.request.Request('https://api.github.com/meta',headers={'User-Agent':'oTraiL-GOD-build'})
 try:hosts=json.load(urllib.request.urlopen(request,timeout=30))['ssh_keys']
 except Exception:
  oldEnvelope=json.loads((RELEASES/'catalog.json').read_text())
  oldCatalog=json.loads(base64.b64decode(oldEnvelope['payload']))
  hosts=oldCatalog['toolchains'][0]['githubHostKeys']
 rootfs=dict(file='debian-bookworm-arm64-20261117-rootfs.tar.xz',entry='debian/rootfs.tar.xz',url='https://images.linuxcontainers.org/images/debian/bookworm/arm64/default/20261117_05%3A24/rootfs.tar.xz',sha256='125416b51b1272db7abd932bc7cc5f286fd29a190cb5a1b18460500f5448d5fc',sizeBytes=95026816)
 latestFunctions=dict(commit='0.4.28',title='GOD 4.1.4 shell and button UX',functions=['Live shell keystrokes echo inline in the terminal','Shell input refocuses after Enter for the next command','Terminal occupies the top 60 percent of the shell screen','Filled button rows with high-contrast black labels','Module cards collapse by module with expandable versions'])
 metadata=dict(id='god-java-0.4.28-v4.1.4',name='GOD 4.1.4 shell and button UX',version='4.1.4',api=1,minApp=51,minSdk=35,sourceVersion='0.4.28',baselineSha256=baseline,signingCertificate='78cfa67d3bfede47427e03334726faf7bea8908538f162f3554cdbf89b927827',sources=[dict(version='0.4.28',url=prefix+sourcezip.name,sha256=sha(sourcezip),sizeBytes=sourcezip.stat().st_size)],tools=tools,toolFiles=checks,githubHostKeys=hosts,debianRootfs=rootfs,latestFunctions=latestFunctions)
 modulelibs=[LIBS/(name+'.jar') for name in MODULE_MAVEN]
 moduleclasses=BUILD/'module-classes';javac(list((ROOT/'modules/mod1/src').rglob('*.java')),moduleclasses,[SDK/'platforms/android-37.0/android.jar',CLASSES,*modulelibs,*[LIBS/(name+'.jar') for name in SORA]]);jar(BUILD/'god-module.jar',moduleclasses);code=dex('module-code',[BUILD/'god-module.jar',*modulelibs],[CLASSES,*[LIBS/(name+'.jar') for name in SORA]])
 manifest=dict(id='mod1',name='GOD',version='4.1.4',api=1,minApp=51,minSdk=35,entry='in.otrail.extensions.mod1.SourceStudio')
 module=RELEASES/'GOD-4.1.4.oTrail0';rootfsArchive=download(rootfs['url'],BUILD/rootfs['file'])
 if not debian_only:
  with zipfile.ZipFile(module,'w',zipfile.ZIP_DEFLATED) as out:
   out.write(code/'classes.dex','classes.dex');out.writestr('module.json',json.dumps(manifest));out.writestr('studio.json',json.dumps(metadata));out.write(ROOT/'modules/mod1/README.md','README.md')
   for license in (ROOT/'modules/mod1/licenses').iterdir():out.write(license,'LICENSES/'+license.name)
   out.write(ROOT/'app/src/main/assets/licenses/sora/LICENSE','LICENSES/SORA-LGPL-2.1-or-later.txt')
   out.write(ROOT/'third_party/PROOT_COPYING','LICENSES/PROOT-GPL-2.0.txt');out.write(ROOT/'third_party/TALLOC_COPYING','LICENSES/TALLOC-GPL-3.0.txt');out.write(ROOT/'third_party/LIBANDROID_SHMEM_LICENSE.txt','LICENSES/LIBANDROID-SHMEM-BSD-3-Clause.txt')
 debianManifest=dict(id='mod3',name='Debian Shell',version='1.1.7',api=1,minApp=51,minSdk=35,entry='in.otrail.extensions.mod1.DebianShell')
 debian=RELEASES/'DEBIAN-1.1.7.oTrail0'
 with zipfile.ZipFile(debian,'w',zipfile.ZIP_DEFLATED) as out:
  out.write(code/'classes.dex','classes.dex');out.writestr('module.json',json.dumps(debianManifest));out.writestr('studio.json',json.dumps(metadata));out.write(rootfsArchive,rootfs['entry'],compress_type=zipfile.ZIP_STORED);out.write(ROOT/'third_party/PROOT_COPYING','LICENSES/PROOT-GPL-2.0.txt');out.write(ROOT/'third_party/TALLOC_COPYING','LICENSES/TALLOC-GPL-3.0.txt');out.write(ROOT/'third_party/LIBANDROID_SHMEM_LICENSE.txt','LICENSES/LIBANDROID-SHMEM-BSD-3-Clause.txt')
 passwordFile=pathlib.Path.home()/'.local/share/otrail/god-install-password';password=passwordFile.read_bytes();salt=os.urandom(16)
 verifier=dict(installSalt=base64.b64encode(salt).decode(),installHash=base64.b64encode(hashlib.pbkdf2_hmac('sha256',password,salt,120000,32)).decode())
 release=dict(manifest,url=prefix+module.name,sha256=sha(module),sizeBytes=module.stat().st_size,**verifier,notes='Live shell input echo, 60 percent terminal, filled black-labelled button rows, and collapsed module cards.')
 debianRelease=dict(debianManifest,url=prefix+debian.name,sha256=sha(debian),sizeBytes=debian.stat().st_size,**verifier,notes='Standalone Debian Bookworm ARM64 PRoot shell with live input echo, tap-to-focus keyboard on the terminal, 60 percent terminal, and filled button rows.')
 versions=[debianRelease]+([] if debian_only else [release]);chains=[] if debian_only else [metadata]
 old=RELEASES/'catalog.json'
 if old.exists():
  envelope=json.loads(old.read_text());previous=base64.b64decode(envelope['payload']);sig=BUILD/'old-catalog.sig';sig.write_bytes(base64.b64decode(envelope['signature']));public=BUILD/'publisher-public.pem'
  key=pathlib.Path.home()/'.local/share/otrail/module-signing.pem'
  public.write_bytes(subprocess.check_output(['openssl','pkey','-in',str(key),'-pubout']))
  verified=subprocess.run(['openssl','dgst','-sha256','-verify',str(public),'-signature',str(sig)],input=previous,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode==0
  if not verified:
   legacy=BUILD/'legacy-catalog-public.der';legacy.write_bytes(base64.b64decode(LEGACY_CATALOG_PUBLIC_KEY))
   subprocess.run(['openssl','dgst','-sha256','-keyform','DER','-verify',str(legacy),'-signature',str(sig)],input=previous,check=True,stdout=subprocess.DEVNULL)
  catalog=json.loads(previous)
  replaced={(debianRelease['id'],debianRelease['version'])};replaced.update(set() if debian_only else {(release['id'],release['version'])})
  versions += [item for item in catalog.get('versions',[]) if (item['id'],item['version']) not in replaced and item.get('sizeBytes',0)<=240000000]
  chains += [item for item in catalog.get('toolchains',[]) if debian_only or item['id']!=metadata['id']]
 payload=json.dumps(dict(schema=1,repositories=[dict(name='viskie/oTrail0',url=prefix)],versions=versions,toolchains=chains),sort_keys=True,separators=(',',':')).encode()
 (BUILD/'catalog-payload.json').write_bytes(payload)
 key=pathlib.Path.home()/'.local/share/otrail/module-signing.pem';signature=subprocess.check_output(['openssl','dgst','-sha256','-sign',str(key)],input=payload)
 (RELEASES/'catalog.json').write_text(json.dumps(dict(payload=base64.b64encode(payload).decode(),signature=base64.b64encode(signature).decode()),indent=2)+'\n')
 (RELEASES/'SHA256SUMS').write_text(''.join(sha(p)+'  '+p.name+'\n' for p in sorted(RELEASES.iterdir()) if p.is_file() and p.name not in {'SHA256SUMS','catalog.json'}))
 print('Packaged',module.name,module.stat().st_size,'bytes',flush=True)

if __name__=='__main__':
 parser=argparse.ArgumentParser();parser.add_argument('action',choices=['prepare','package','package-debian']);args=parser.parse_args()
 prepare() if args.action=='prepare' else package(args.action=='package-debian')

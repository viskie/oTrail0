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
 # Public Java compiler APIs are absent on ART; include the OpenJDK java.compiler API under its Classpath Exception.
 api=BUILD/'java-compiler-api.jar'
 with zipfile.ZipFile(JAVA/'jmods/java.compiler.jmod') as source,zipfile.ZipFile(api,'w',zipfile.ZIP_DEFLATED) as out:
  for name in source.namelist():
   if name.startswith('classes/javax/') and name.endswith('.class'):out.writestr(name[len('classes/'):],source.read(name))
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
 (licenses/'README.txt').write_text('GOD toolchain dependencies\nECJ 3.38 EPL-2.0: https://github.com/eclipse-jdt/eclipse.jdt.core\nD8 BSD-3-Clause: https://r8.googlesource.com/r8\nApksig Apache-2.0: https://android.googlesource.com/platform/tools/apksig\nOpenJDK java.compiler GPLv2 + Classpath Exception: https://openjdk.org/legal/gplv2+ce.html\nJGit EDL-1.0: https://www.eclipse.org/jgit/\nJSch BSD: https://github.com/mwiede/jsch\nSLF4J MIT: https://www.slf4j.org/license.html\nJavaEWAH Apache-2.0: https://github.com/lemire/javaewah\nCommons Codec Apache-2.0: https://commons.apache.org/proper/commons-codec/\nAndroid platform downloaded directly from Google after license acceptance.\n')
 for name,(base,file) in MAVEN.items():
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
 zipfiles(RELEASES/'god-dependencies-0.4.0.zip',{'classpath.jar':merged,'dependency-dex.zip':BUILD/'dependency-dex.zip',**{'LICENSES/'+p.name:p for p in licenses.iterdir()}})
 # SDK stays at its official distribution URL, not bundled into the app or module.
 xml=ET.fromstring(urllib.request.urlopen('https://dl.google.com/android/repository/repository2-3.xml',timeout=60).read())
 archive=None
 for package in xml.findall('remotePackage'):
  if package.get('path')=='platforms;android-37.0':archive=package.find('archives/archive/complete');break
 if archive is None:raise RuntimeError('Android 37 SDK not found in Google catalog')
 url='https://dl.google.com/android/repository/'+archive.findtext('url');platform=download(url,BUILD/'google-platform.zip')
 with zipfile.ZipFile(platform) as z:entry=next(n for n in z.namelist() if n.endswith('/android.jar'))
 toolFiles={'compiler.jar':sha(BUILD/'compiler.jar'),'publisher.jar':sha(BUILD/'publisher.jar'),'android.jar':sha(SDK/'platforms/android-37.0/android.jar'),'classpath.jar':sha(merged),'dependency-dex.zip':sha(BUILD/'dependency-dex.zip')}
 state={'toolFiles':toolFiles,'sdk':{'file':'google-platform-37.zip','url':url,'sha256':sha(platform),'sizeBytes':platform.stat().st_size,'extractFiles':{entry:'android.jar'}}}
 (BUILD/'prepared.json').write_text(json.dumps(state,indent=2));print('Tool packs prepared',flush=True)

def source():
 paths=subprocess.check_output(['git','ls-files','-z'],cwd=ROOT).decode().split('\0')
 include=[]
 for name in paths:
  if not name or any(part in {'releases','.git','build','.gradle','.idea'} for part in pathlib.PurePosixPath(name).parts) or name=='android-project-sources.txt' or name.endswith(('.apk','.oTrail0','.map','.keystore','.jks')):continue
  file=ROOT/name
  if file.is_file():include.append(name)
 protected={}
 for name in include:
  if name in {'app/build.gradle','settings.gradle','build.gradle','gradle.properties','app/src/main/AndroidManifest.xml'} or name.startswith('app/src/main/res/') or (name.startswith('third_party/brouter/') or name.startswith('modules/mod1/')) and name.endswith('.java'):protected[name]=sha(ROOT/name)
 baseline={'version':'0.4.0','sourceRoots':['app/src/main/java','modules/src/main/java','modules/api/src/main/java'],'protected':protected}
 raw=json.dumps(baseline,sort_keys=True,indent=2).encode()
 path=RELEASES/'otrail-source-0.4.0.zip'
 with zipfile.ZipFile(path,'w',zipfile.ZIP_DEFLATED) as out:
  for name in include:out.write(ROOT/name,name)
  out.writestr('.studio-baseline.json',raw)
 return path,hashlib.sha256(raw).hexdigest()

def package():
 state=json.loads((BUILD/'prepared.json').read_text());sourcezip,baseline=source()
 prefix='https://raw.githubusercontent.com/viskie/oTrail0/main/modules/releases/'
 def artifact(path,**kwargs):return dict(file=path.name,url=prefix+path.name,sha256=sha(path),sizeBytes=path.stat().st_size,**kwargs)
 tools=[artifact(RELEASES/'god-compiler-1.0.0.zip',archive=True),artifact(RELEASES/'god-publisher-1.0.0.zip',archive=True),artifact(RELEASES/'god-dependencies-0.4.0.zip',archive=True),state['sdk']]
 apk=ROOT/'releases/oTraiL-0.4.0-debug.apk'
 tools.append(dict(file='template-0.4.0.apk',url='https://raw.githubusercontent.com/viskie/oTrail0/main/releases/'+apk.name,sha256=sha(apk),sizeBytes=apk.stat().st_size,target='template.apk'))
 checks=state['toolFiles'];checks['template.apk']=sha(apk)
 request=urllib.request.Request('https://api.github.com/meta',headers={'User-Agent':'oTraiL-GOD-build'})
 hosts=json.load(urllib.request.urlopen(request,timeout=30))['ssh_keys']
 metadata=dict(id='god-java-0.4.0-v1',name='GOD in-app Java toolchain',version='1.0.0',api=1,minApp=23,sourceVersion='0.4.0',baselineSha256=baseline,signingCertificate='227e8f5912c78bab9cc3a016290aebf0b21b07aedaa520328cfea5ac0fcec051',sources=[dict(version='0.4.0',url=prefix+sourcezip.name,sha256=sha(sourcezip),sizeBytes=sourcezip.stat().st_size)],tools=tools,toolFiles=checks,githubHostKeys=hosts)
 moduleclasses=BUILD/'module-classes';javac(list((ROOT/'modules/mod1/src').rglob('*.java')),moduleclasses,[SDK/'platforms/android-37.0/android.jar',CLASSES]);jar(BUILD/'god-module.jar',moduleclasses);code=dex('module-code',[BUILD/'god-module.jar'],[CLASSES])
 manifest=dict(id='mod1',name='GOD',version='1.0.0',api=1,minApp=23,entry='in.otrail.extensions.mod1.SourceStudio')
 module=RELEASES/'GOD-1.0.0.oTrail0'
 with zipfile.ZipFile(module,'w',zipfile.ZIP_DEFLATED) as out:
  out.write(code/'classes.dex','classes.dex');out.writestr('module.json',json.dumps(manifest));out.writestr('studio.json',json.dumps(metadata));out.write(ROOT/'modules/mod1/README.md','README.md')
 passwordFile=pathlib.Path.home()/'.local/share/otrail/god-install-password';password=passwordFile.read_bytes();salt=os.urandom(16)
 release=dict(manifest,url=prefix+module.name,sha256=sha(module),sizeBytes=module.stat().st_size,installSalt=base64.b64encode(salt).decode(),installHash=base64.b64encode(hashlib.pbkdf2_hmac('sha256',password,salt,120000,32)).decode(),notes='In-app multi-tab editor and offline Java APK build. Download tools separately; unsupported resources/build-system edits rejected. Phone-specific SSH key, GitHub test and named APK publication. Device validation pending.')
 payload=json.dumps(dict(schema=1,repositories=[dict(name='viskie/oTrail0',url=prefix)],versions=[release],toolchains=[metadata]),sort_keys=True,separators=(',',':')).encode()
 (BUILD/'catalog-payload.json').write_bytes(payload)
 key=pathlib.Path.home()/'.local/share/otrail/module-signing.pem';signature=subprocess.check_output(['openssl','dgst','-sha256','-sign',str(key)],input=payload)
 (RELEASES/'catalog.json').write_text(json.dumps(dict(payload=base64.b64encode(payload).decode(),signature=base64.b64encode(signature).decode()),indent=2)+'\n')
 (RELEASES/'SHA256SUMS').write_text(''.join(sha(p)+'  '+p.name+'\n' for p in sorted(RELEASES.iterdir()) if p.is_file() and p.name not in {'SHA256SUMS','catalog.json'}))
 print('Packaged',module.name,module.stat().st_size,'bytes',flush=True)

if __name__=='__main__':
 parser=argparse.ArgumentParser();parser.add_argument('action',choices=['prepare','package']);args=parser.parse_args()
 prepare() if args.action=='prepare' else package()

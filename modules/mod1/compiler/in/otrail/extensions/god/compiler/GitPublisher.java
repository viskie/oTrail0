package in.otrail.extensions.god.compiler;

import in.otrail.modules.*;
import org.eclipse.jgit.api.*;
import org.eclipse.jgit.dircache.*;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.*;
import org.eclipse.jgit.transport.*;
import org.eclipse.jgit.transport.ssh.jsch.*;
import org.eclipse.jgit.util.FS;
import com.jcraft.jsch.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.util.*;
import org.json.*;

/** Git/SSH libraries execute inside the app; the private key is non-exportable Android Keystore data. */
public final class GitPublisher implements PublisherV1 {
  private static final String KEY="otrail.god.github.ssh";
  @Override public void run(String action,File data,String configuration,ModuleV1.Progress progress)throws Exception{
    File home=new File(data,"git-home");home.mkdirs();System.setProperty("user.home",home.getAbsolutePath());
    JSONObject input=new JSONObject(configuration),config=json(new File(data,"github.json")),chain=input.getJSONObject("toolchain");
    String repo=config.getString("repository"),branch=config.getString("branch");
    if(!repo.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")||!Repository.isValidRefName("refs/heads/"+branch))throw new IOException("Invalid GitHub repository or branch");
    String remote="git@github.com:"+repo+".git";StringBuilder known=new StringBuilder();JSONArray hosts=chain.getJSONArray("githubHostKeys");for(int i=0;i<hosts.length();i++)known.append("github.com ").append(hosts.getString(i)).append('\n');
    final com.jcraft.jsch.Identity identity=identity();final byte[] hostKeys=known.toString().getBytes(StandardCharsets.UTF_8);
    JschConfigSessionFactory factory=new JschConfigSessionFactory(){
      @Override protected JSch createDefaultJSch(FS fs)throws JSchException{JSch ssh=new JSch();ssh.setKnownHosts(new ByteArrayInputStream(hostKeys));ssh.addIdentity(identity,null);return ssh;}
      @Override protected void configure(OpenSshConfig.Host host,Session session){session.setConfig("StrictHostKeyChecking","yes");session.setConfig("PreferredAuthentications","publickey");session.setConfig("PubkeyAcceptedAlgorithms","rsa-sha2-512,rsa-sha2-256");}
    };
    TransportConfigCallback transport=t->{if(!(t instanceof SshTransport))throw new IllegalStateException("SSH transport required");((SshTransport)t).setSshSessionFactory(factory);};
    progress.update("Check GitHub SSH",5,"Authenticating to "+repo+" with the phone key. Host keys are pinned.\n");
    Collection<Ref> refs=Git.lsRemoteRepository().setRemote(remote).setTransportConfigCallback(transport).setTimeout(60).call();
    boolean branchExists=false;for(Ref ref:refs)if(ref.getName().equals("refs/heads/"+branch))branchExists=true;if(!branchExists)throw new IOException("Configured branch does not exist");
    config.put("verified",true);write(new File(data,"github.json"),config.toString().getBytes(StandardCharsets.UTF_8));
    if(action.equals("githubCheck")){progress.update("GitHub key configured",100,"SSH authentication and repository read access verified. Publishing additionally requires write access.\n");return;}
    String filename=input.getString("file"),releaseName=input.getString("releaseName");
    if(!filename.matches("oTraiL-phone-[0-9-]+\\.apk")||!releaseName.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,120}\\.apk"))throw new IOException("Use a unique APK filename containing letters, digits, dots, underscores or hyphens");
    File apk=new File(data,"outputs/"+filename);if(!apk.isFile())throw new IOException("Build APK first");
    File checkout=new File(data,"github-work/"+repo.replace('/','-')+"-"+branch.replace('/','-'));
    StringBuilder log=new StringBuilder("Synchronizing Git repository. First synchronization may download existing APK objects.\n");
    ProgressMonitor monitor=new ProgressMonitor(){String task="Git";int total,done;long updated;
      public void start(int n){}public void beginTask(String name,int count){task=name;total=count;done=0;}public void update(int n){done+=n;long now=System.currentTimeMillis();if(now-updated>1000){updated=now;progress.update(task,total>0?Math.min(80,10+done*60/total):10,log.toString());}}public void endTask(){}public boolean isCancelled(){return progress.cancelled();}public void showDuration(boolean show){}
    };
    Git git;
    if(new File(checkout,".git").isDirectory()){
      git=Git.open(checkout);git.fetch().setRemote(remote).setDepth(1).setRefSpecs("refs/heads/"+branch+":refs/remotes/origin/"+branch).setTransportConfigCallback(transport).setProgressMonitor(monitor).setTimeout(180).call();
    }else{
      checkout.getParentFile().mkdirs();git=Git.cloneRepository().setURI(remote).setDirectory(checkout).setNoCheckout(true).setDepth(1).setBranch("refs/heads/"+branch).setBranchesToClone(Collections.singletonList("refs/heads/"+branch)).setTransportConfigCallback(transport).setProgressMonitor(monitor).setTimeout(180).call();
    }
    Repository repository=git.getRepository();
    try(Git close=git;RevWalk walk=new RevWalk(repository);ObjectInserter objects=repository.newObjectInserter();ObjectReader reader=repository.newObjectReader()){
      ObjectId base=repository.resolve("refs/remotes/origin/"+branch);if(base==null)base=repository.resolve("HEAD");RevCommit parent=walk.parseCommit(base);
      DirCache index=DirCache.read(reader,parent.getTree());String path="releases/"+releaseName;
      if(index.getEntry(path)!=null)throw new IOException("Release filename already exists; choose a new name");
      ObjectId blob;try(InputStream in=new FileInputStream(apk)){blob=objects.insert(Constants.OBJ_BLOB,apk.length(),in);}
      String sum=sha(apk)+"  "+releaseName+"\n";ObjectId checksum=objects.insert(Constants.OBJ_BLOB,sum.getBytes(StandardCharsets.UTF_8));
      DirCacheEditor edits=index.editor();add(edits,path,blob,apk.length());add(edits,path+".sha256",checksum,sum.length());edits.finish();
      CommitBuilder commit=new CommitBuilder();commit.setTreeId(index.writeTree(objects));commit.setParentId(parent);
      PersonIdent author=new PersonIdent("GOD on phone","god@otrail.local");commit.setAuthor(author);commit.setCommitter(author);commit.setMessage("Publish phone-built "+releaseName+"\n\nBuilt inside oTraiL GOD; source journal and map data excluded.\n");
      ObjectId next=objects.insert(commit);objects.flush();RefUpdate local=repository.updateRef("refs/heads/"+branch);local.setNewObjectId(next);local.setForceUpdate(true);local.update();
      if(progress.cancelled())throw new IOException("Publication cancelled before push");
      progress.update("Push APK release",85,log.append("Commit: ").append(next.name()).append('\n').toString());
      Iterable<PushResult> results=git.push().setRemote(remote).setRefSpecs(new RefSpec("refs/heads/"+branch+":refs/heads/"+branch)).setTransportConfigCallback(transport).setProgressMonitor(monitor).setTimeout(240).call();
      boolean pushed=false;for(PushResult result:results)for(RemoteRefUpdate update:result.getRemoteUpdates()){
        if(update.getStatus()!=RemoteRefUpdate.Status.OK&&update.getStatus()!=RemoteRefUpdate.Status.UP_TO_DATE)throw new IOException("GitHub rejected push: "+update.getStatus()+" "+update.getMessage());pushed=true;
      }
      if(!pushed)throw new IOException("No remote branch was updated");progress.update("Published on GitHub",100,"https://github.com/"+repo+"/blob/"+branch+"/"+path+"\nCommit "+next.name()+"\nNo remote force push or GitHub Releases workflow used.\n");
    }
  }
  private static void add(DirCacheEditor edits,String path,ObjectId object,long length){edits.add(new DirCacheEditor.PathEdit(path){public void apply(DirCacheEntry entry){entry.setFileMode(FileMode.REGULAR_FILE);entry.setObjectId(object);entry.setLength(length);}});}
  private static com.jcraft.jsch.Identity identity()throws Exception{
    KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);PrivateKey key=(PrivateKey)store.getKey(KEY,null);if(key==null)throw new IOException("Generate and upload the SSH public key in Tools first");RSAPublicKey pub=(RSAPublicKey)store.getCertificate(KEY).getPublicKey();ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);put(out,"ssh-rsa".getBytes(StandardCharsets.UTF_8));put(out,pub.getPublicExponent().toByteArray());put(out,pub.getModulus().toByteArray());final byte[] publicBlob=bytes.toByteArray();
    return new com.jcraft.jsch.Identity(){public String getName(){return "GOD Android Keystore";}public String getAlgName(){return "ssh-rsa";}public byte[] getPublicKeyBlob(){return publicBlob;}public boolean setPassphrase(byte[] pass){return true;}public boolean isEncrypted(){return false;}public void clear(){}
      public byte[] getSignature(byte[] data){return getSignature(data,"rsa-sha2-256");}
      public byte[] getSignature(byte[] data,String algorithm){try{if(!algorithm.equals("rsa-sha2-256")&&!algorithm.equals("rsa-sha2-512"))return null;java.security.Signature signature=java.security.Signature.getInstance(algorithm.equals("rsa-sha2-512")?"SHA512withRSA":"SHA256withRSA");signature.initSign(key);signature.update(data);ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream output=new DataOutputStream(bytes);put(output,algorithm.getBytes(StandardCharsets.UTF_8));put(output,signature.sign());return bytes.toByteArray();}catch(Exception e){return null;}}
    };
  }
  private static void put(DataOutputStream out,byte[] bytes)throws IOException{out.writeInt(bytes.length);out.write(bytes);}
  private static JSONObject json(File file)throws Exception{return new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));}
  private static void write(File file,byte[] bytes)throws IOException{Files.write(file.toPath(),bytes);}
  private static String sha(File file)throws Exception{MessageDigest d=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)d.update(b,0,n);}StringBuilder out=new StringBuilder();for(byte b:d.digest())out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
}

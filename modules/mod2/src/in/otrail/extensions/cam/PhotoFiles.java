package in.otrail.extensions.cam;

import android.content.*;
import android.net.Uri;
import android.os.*;
import android.provider.*;
import android.media.ExifInterface;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import org.json.*;
import in.otrail.modules.ModuleV1;

/** Public originals plus self-contained sidecars allow recovery after reinstall/folder re-selection. */
final class PhotoFiles {
  static String filename(JSONObject j)throws Exception {
    SimpleDateFormat f=new SimpleDateFormat("yyyyMMdd'T'HHmmssSSS'Z'",Locale.US);f.setTimeZone(TimeZone.getTimeZone("UTC"));
    return "oTrail;"+j.optString("stop_uid","no-stop")+";"+f.format(new Date(j.getLong("captured_ms")))+";"+(j.has("latitude")?String.format(Locale.US,"%.6f;%.6f",j.getDouble("latitude"),j.getDouble("longitude")):"no-gps")+";"+j.getString("id")+".jpg";
  }
  static Uri create(Context c,String folder,String name,String mime)throws Exception {
    if(!folder.isEmpty()){
      Uri tree=Uri.parse(folder);Uri parent=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));
      Uri result=DocumentsContract.createDocument(c.getContentResolver(),parent,mime,name);if(result==null)throw new IOException("Folder did not create file");return result;
    }
    ContentValues v=new ContentValues();v.put(MediaStore.MediaColumns.DISPLAY_NAME,name);v.put(MediaStore.MediaColumns.MIME_TYPE,mime);v.put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/oTraiL/Photos");v.put(MediaStore.MediaColumns.IS_PENDING,1);
    Uri result=c.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,v);if(result==null)throw new IOException("Cannot create photo in Downloads");return result;
  }
  static void publish(Context c,String folder,Uri uri){if(folder.isEmpty()){ContentValues v=new ContentValues();v.put(MediaStore.MediaColumns.IS_PENDING,0);c.getContentResolver().update(uri,v,null,null);}}
  static String rational(double n){long degrees=(long)Math.floor(Math.abs(n));double minutes=(Math.abs(n)-degrees)*60;long min=(long)minutes;return degrees+"/1,"+min+"/1,"+Math.round((minutes-min)*60*10000)+"/10000";}
  static void exif(Context c,Uri uri,JSONObject j)throws Exception {
    try(ParcelFileDescriptor p=c.getContentResolver().openFileDescriptor(uri,"rw")){
      if(p==null)throw new IOException("Cannot open photo metadata");ExifInterface e=new ExifInterface(p.getFileDescriptor());
      SimpleDateFormat f=new SimpleDateFormat("yyyy:MM:dd HH:mm:ss",Locale.US);f.setTimeZone(TimeZone.getTimeZone("UTC"));String t=f.format(new Date(j.getLong("captured_ms")));
      e.setAttribute("DateTime",t);e.setAttribute("DateTimeOriginal",t);e.setAttribute("OffsetTimeOriginal","+00:00");e.setAttribute("SubSecTimeOriginal",String.format(Locale.US,"%03d",j.getLong("captured_ms")%1000));
      e.setAttribute("UserComment",j.toString());e.setAttribute("ImageDescription","oTraiL CAM; stop="+j.optString("stop_uid","no-stop")+"; fix_ms="+j.optLong("fix_ms",0)+"; accuracy_m="+j.optDouble("accuracy_m",-1));
      if(j.has("latitude")){double lat=j.getDouble("latitude"),lon=j.getDouble("longitude");e.setAttribute("GPSLatitude",rational(lat));e.setAttribute("GPSLatitudeRef",lat<0?"S":"N");e.setAttribute("GPSLongitude",rational(lon));e.setAttribute("GPSLongitudeRef",lon<0?"W":"E");e.setAttribute("GPSProcessingMethod","oTraiL logged GPS; see fix_ms");}
      e.saveAttributes();
    }
  }
  static JSONObject save(Context c,String folder,byte[] jpeg,JSONObject context,ModuleV1.Host host)throws Exception {
    JSONObject j=new JSONObject(context.toString());j.put("id",UUID.randomUUID().toString());j.put("filename",filename(j));j.put("schema",1);
    Uri photo=create(c,folder,j.getString("filename"),"image/jpeg");j.put("uri",photo.toString());Uri sidecar=null;
    try {
      try(OutputStream out=c.getContentResolver().openOutputStream(photo,"w")){if(out==null)throw new IOException("Cannot write photo");out.write(jpeg);}
      exif(c,photo,j);
      sidecar=create(c,folder,j.getString("filename")+".json","application/json");
      try(OutputStream out=c.getContentResolver().openOutputStream(sidecar,"w")){if(out==null)throw new IOException("Cannot write metadata sidecar");out.write(j.toString(2).getBytes(StandardCharsets.UTF_8));}
      publish(c,folder,photo);publish(c,folder,sidecar);
      // Original and recovery metadata are durable before the database row is inserted.
      host.savePhoto(j.toString());return j;
    }catch(Exception failure){
      // Never delete a saved photo because indexing failed: its sidecar permits later recovery.
      publish(c,folder,photo);if(sidecar!=null)publish(c,folder,sidecar);throw failure;
    }
  }
  static int scan(Context c,String folder,ModuleV1.Host host)throws Exception {
    ContentResolver r=c.getContentResolver();int count=0;
    if(folder.isEmpty())throw new IOException("Choose the existing photo folder first, then rebuild its index");
    Uri tree=Uri.parse(folder);String parent=DocumentsContract.getTreeDocumentId(tree);Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,parent);
    Map<String,Uri> files=new HashMap<>();
    try(android.database.Cursor cursor=r.query(children,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME},null,null,null)){
      if(cursor!=null)while(cursor.moveToNext())files.put(cursor.getString(1),DocumentsContract.buildDocumentUriUsingTree(tree,cursor.getString(0)));
    }
    for(Map.Entry<String,Uri> f:files.entrySet())if(f.getKey().endsWith(".jpg.json")&&f.getKey().startsWith("oTrail;")){
      Uri photo=files.get(f.getKey().substring(0,f.getKey().length()-5));if(photo==null)continue;
      try(InputStream in=r.openInputStream(f.getValue())){
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1){if(bytes.size()+n>100000)throw new IOException("Metadata sidecar too large");bytes.write(b,0,n);}
        JSONObject j=new JSONObject(new String(bytes.toByteArray(),StandardCharsets.UTF_8));j.put("uri",photo.toString());host.savePhoto(j.toString());count++;
      }
    }
    return count;
  }
}

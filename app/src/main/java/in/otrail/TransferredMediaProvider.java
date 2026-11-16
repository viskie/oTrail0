package in.otrail;
import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;
/** Read-only, app-private media copied during an explicitly authorized phone migration. */
public final class TransferredMediaProvider extends ContentProvider {
 public boolean onCreate(){return true;}
 private File file(Uri uri)throws IOException{
  if(uri.getPathSegments().size()!=1)throw new FileNotFoundException();
  String name=uri.getLastPathSegment();if(name==null||!name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,200}"))throw new FileNotFoundException();
  File root=new File(getContext().getFilesDir(),"transferred-media").getCanonicalFile(),file=new File(root,name).getCanonicalFile();
  if(!root.equals(file.getParentFile())||!file.isFile())throw new FileNotFoundException();return file;
 }
 public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{if(!"r".equals(mode))throw new FileNotFoundException("Read only");try{return ParcelFileDescriptor.open(file(uri),ParcelFileDescriptor.MODE_READ_ONLY);}catch(IOException e){throw new FileNotFoundException("Media is unavailable");}}
 public String getType(Uri uri){String extension=android.webkit.MimeTypeMap.getFileExtensionFromUrl(uri.toString());String type=android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.toLowerCase(java.util.Locale.ROOT));return type==null?"application/octet-stream":type;}
 public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort){try{File file=file(uri);String[] columns=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;MatrixCursor cursor=new MatrixCursor(columns);Object[] values=new Object[columns.length];for(int i=0;i<columns.length;i++)values[i]=OpenableColumns.DISPLAY_NAME.equals(columns[i])?file.getName():OpenableColumns.SIZE.equals(columns[i])?file.length():null;cursor.addRow(values);return cursor;}catch(IOException e){return null;}}
 public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
}

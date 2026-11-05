package in.otrail;

import android.content.*;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;

/** A single private verified APK, read-only, shared only with an explicit URI grant. */
public final class UpdateApkProvider extends ContentProvider {
  @Override
  public boolean onCreate() {
    return true;
  }

  private File file(Uri uri) throws FileNotFoundException {
    if (!"/verified.apk".equals(uri.getPath())) throw new FileNotFoundException();
    File file = new File(getContext().getFilesDir(), "updates/verified.apk");
    if (!file.isFile()) throw new FileNotFoundException();
    return file;
  }

  @Override
  public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
    if (!"r".equals(mode)) throw new FileNotFoundException("Read only");
    return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY);
  }

  @Override
  public String getType(Uri uri) {
    return "application/vnd.android.package-archive";
  }

  @Override
  public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
    try {
      File f = file(uri);
      String[] columns =
          projection == null
              ? new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}
              : projection;
      MatrixCursor c = new MatrixCursor(columns);
      Object[] values = new Object[columns.length];
      for (int i = 0; i < columns.length; i++)
        values[i] =
            OpenableColumns.DISPLAY_NAME.equals(columns[i])
                ? "oTraiL-update.apk"
                : OpenableColumns.SIZE.equals(columns[i]) ? f.length() : null;
      c.addRow(values);
      return c;
    } catch (FileNotFoundException e) {
      return null;
    }
  }

  @Override
  public Uri insert(Uri uri, ContentValues values) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int delete(Uri uri, String where, String[] args) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int update(Uri uri, ContentValues values, String where, String[] args) {
    throw new UnsupportedOperationException();
  }
}

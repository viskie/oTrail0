package in.otrail.extensions.files;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import in.otrail.modules.ModuleV1;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * File manager for the installed Debian root filesystem and phone storage.
 * The Debian image is browsed directly on disk; phone storage needs All files access.
 */
public final class FileManagerModule implements ModuleV1 {
  private static final int BG = 0xff0b0e13, SURFACE = 0xff12161d, SURFACE_ALT = 0xff171c24,
      TEXT = 0xfff8fafc, MUTED = 0xff8494a3, CYAN = 0xff00d4ff, PURPLE = 0xff9b59ff,
      GREEN = 0xff55ff55, RED = 0xffff3838, BORDER = 0xff2a3555;

  private Activity activity;
  private Host host;
  private FrameLayout container;
  private LinearLayout listScreen, viewerScreen, pdfScreen, rootChips, entriesList, actionsRow;
  private TextView status, pathText, viewerTitle, viewerBody, pdfTitle, pdfPageLabel;
  private ImageView pdfImage;
  private PdfRenderer pdfRenderer;
  private ParcelFileDescriptor pdfDescriptor;
  private int pdfPage, pdfPageCount;
  private float pdfZoom = 1f;
  private String rootKey = "debian";
  private File current;
  private final Map<String, File> lastPaths = new HashMap<>();
  private File clipSource;
  private boolean clipMove;
  private boolean loading;
  private List<File> entries = new ArrayList<>();
  private Button pasteButton, grantButton;
  private final Handler main = new Handler(Looper.getMainLooper());

  @Override public String id() {
    return "mod8";
  }

  @Override public View create(Activity activity, Host host) throws Exception {
    this.activity = activity;
    this.host = host;
    container = new FrameLayout(activity);
    container.setBackgroundColor(BG);
    listScreen = buildListScreen();
    viewerScreen = buildViewerScreen();
    pdfScreen = buildPdfScreen();
    container.addView(listScreen, new FrameLayout.LayoutParams(-1, -1));
    container.addView(viewerScreen, new FrameLayout.LayoutParams(-1, -1));
    container.addView(pdfScreen, new FrameLayout.LayoutParams(-1, -1));
    show(listScreen);
    if (!root("debian").isDirectory() && root("phone").isDirectory()) rootKey = "phone";
    rebuildRoots();
    open(root(rootKey));
    return container;
  }

  // ------------------------------------------------------------------ list screen

  private LinearLayout buildListScreen() {
    LinearLayout root = new LinearLayout(activity);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(dp(9), dp(7), dp(9), dp(7));

    LinearLayout header = new LinearLayout(activity);
    header.setGravity(Gravity.CENTER_VERTICAL);
    TextView title = label("FILE MANAGER", 17, TEXT);
    title.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
    header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    header.addView(chip("↑ UP", this::goUp), new LinearLayout.LayoutParams(dp(58), dp(30)));
    root.addView(header);

    HorizontalScrollView rootScroll = new HorizontalScrollView(activity);
    rootScroll.setHorizontalScrollBarEnabled(false);
    rootChips = new LinearLayout(activity);
    rootChips.setOrientation(LinearLayout.HORIZONTAL);
    rootScroll.addView(rootChips, new HorizontalScrollView.LayoutParams(-2, -2));
    root.addView(rootScroll);

    HorizontalScrollView pathScroll = new HorizontalScrollView(activity);
    pathScroll.setHorizontalScrollBarEnabled(false);
    pathText = label("", 10, CYAN);
    pathText.setTypeface(Typeface.MONOSPACE);
    pathText.setSingleLine(true);
    pathText.setPadding(dp(4), dp(6), dp(4), dp(6));
    pathScroll.addView(pathText, new HorizontalScrollView.LayoutParams(-2, -2));
    root.addView(pathScroll);

    actionsRow = new LinearLayout(activity);
    actionsRow.setGravity(Gravity.CENTER_VERTICAL);
    actionsRow.addView(chip("NEW FOLDER", this::newFolder), new LinearLayout.LayoutParams(0, dp(30), 1));
    pasteButton = chip("PASTE", this::paste);
    pasteButton.setVisibility(View.GONE);
    actionsRow.addView(pasteButton, new LinearLayout.LayoutParams(0, dp(30), 1));
    actionsRow.addView(chip("GO TO…", this::jumpToPath), new LinearLayout.LayoutParams(0, dp(30), 1));
    grantButton = chip("ALLOW ALL FILES", this::requestAllFiles);
    grantButton.setVisibility(View.GONE);
    actionsRow.addView(grantButton, new LinearLayout.LayoutParams(0, dp(30), 1));
    root.addView(actionsRow);

    status = label("", 10, MUTED);
    status.setPadding(dp(3), dp(4), dp(3), dp(4));
    root.addView(status);

    ScrollView scroll = new ScrollView(activity);
    scroll.setFillViewport(true);
    entriesList = new LinearLayout(activity);
    entriesList.setOrientation(LinearLayout.VERTICAL);
    scroll.addView(entriesList, new ScrollView.LayoutParams(-1, -2));
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    return root;
  }

  private void rebuildRoots() {
    rootChips.removeAllViews();
    rootChips.addView(rootChip("DEBIAN", "debian"));
    rootChips.addView(rootChip("PHONE", "phone"));
    rootChips.addView(rootChip("APP DATA", "app"));
  }

  private View rootChip(String text, String key) {
    boolean active = key.equals(rootKey);
    TextView chip = new TextView(activity);
    chip.setText(text);
    chip.setTextSize(10);
    chip.setTypeface(Typeface.DEFAULT_BOLD);
    chip.setGravity(Gravity.CENTER);
    chip.setPadding(dp(12), dp(6), dp(12), dp(6));
    chip.setTextColor(active ? 0xff000000 : CYAN);
    GradientDrawable frame = new GradientDrawable();
    frame.setColor(active ? CYAN : SURFACE_ALT);
    frame.setCornerRadius(dp(14));
    frame.setStroke(dp(1), active ? CYAN : BORDER);
    chip.setBackground(frame);
    chip.setOnClickListener(v -> {
      rootKey = key;
      rebuildRoots();
      File directory = lastPaths.get(key);
      open(directory != null && directory.isDirectory() ? directory : root(key));
    });
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
    p.setMargins(dp(2), dp(3), dp(3), dp(3));
    chip.setLayoutParams(p);
    return chip;
  }

  private void goUp() {
    File parent = current == null ? null : current.getParentFile();
    if (parent == null) {
      setStatus("Already at the top of this volume.", MUTED);
      return;
    }
    open(parent);
  }

  private void open(File directory) {
    if (directory == null) return;
    if (!directory.exists()) {
      current = directory;
      lastPaths.put(rootKey, directory);
      renderList(new ArrayList<>(), directory);
      return;
    }
    if (!directory.isDirectory()) {
      setStatus("Not a folder: " + directory.getAbsolutePath(), RED);
      return;
    }
    if (!directory.canRead() && !isPhonePath(directory)) {
      setStatus("No read access to " + directory.getAbsolutePath(), RED);
      return;
    }
    loading = true;
    setStatus("Loading…", MUTED);
    new Thread(() -> {
      List<File> result = null;
      String failure = null;
      try {
        result = FileOps.list(directory);
      } catch (Exception error) {
        failure = error.getMessage();
      }
      final List<File> listed = result;
      final String problem = failure;
      main.post(() -> {
        loading = false;
        if (activity == null) return;
        if (problem != null) {
          current = directory;
          lastPaths.put(rootKey, directory);
          setStatus(problem, RED);
          renderList(new ArrayList<>(), directory);
          return;
        }
        entries = listed;
        current = directory;
        lastPaths.put(rootKey, directory);
        renderList(listed, directory);
      });
    }, "files-list").start();
  }

  private void renderList(List<File> files, File directory) {
    entriesList.removeAllViews();
    pathText.setText(directory.getAbsolutePath());
    grantButton.setVisibility(needsAllFiles() && isPhonePath(directory) ? View.VISIBLE : View.GONE);

    if ("debian".equals(rootKey) && !directory.exists()) {
      entriesList.addView(note("Debian is not installed yet. Activate GOD and finish the Debian "
          + "image setup, then open this module again.\n\nExpected: " + directory.getAbsolutePath()));
      setStatus("Debian root filesystem not found.", RED);
      return;
    }
    if (!directory.exists()) {
      entriesList.addView(note("This path does not exist: " + directory.getAbsolutePath()));
      setStatus("Path not found.", RED);
      return;
    }
    if (files.isEmpty()) {
      entriesList.addView(note("This folder is empty."));
    } else {
      for (File file : files) entriesList.addView(fileRow(file));
    }
    setStatus(files.size() + " item" + (files.size() == 1 ? "" : "s")
        + (clipSource != null ? " · clipboard: " + (clipMove ? "move " : "copy ")
            + clipSource.getName() : ""), MUTED);
  }

  private View fileRow(File file) {
    boolean directory = file.isDirectory();
    LinearLayout row = new LinearLayout(activity);
    row.setOrientation(LinearLayout.VERTICAL);
    row.setPadding(dp(8), dp(6), dp(8), dp(6));
    GradientDrawable frame = new GradientDrawable();
    frame.setColor(SURFACE);
    frame.setCornerRadius(dp(8));
    frame.setStroke(dp(1), directory ? BORDER : 0xff1b2430);
    row.setBackground(frame);

    LinearLayout top = new LinearLayout(activity);
    top.setGravity(Gravity.CENTER_VERTICAL);
    TextView badge = label(directory ? "DIR" : "FILE", 9, directory ? CYAN : PURPLE);
    badge.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
    badge.setPadding(0, 0, dp(6), 0);
    top.addView(badge);
    TextView name = label(file.getName(), 12, directory ? CYAN : TEXT);
    name.setSingleLine(true);
    name.setEllipsize(TextUtils.TruncateAt.END);
    if (directory) name.setTypeface(Typeface.DEFAULT_BOLD);
    top.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
    TextView more = label("⋯", 15, MUTED);
    more.setPadding(dp(8), 0, dp(2), 0);
    top.addView(more);
    row.addView(top);

    String detail;
    if (directory) {
      long count = FileOps.countChildren(file);
      detail = count + " item" + (count == 1 ? "" : "s") + " · " + FileOps.date(file.lastModified());
    } else {
      detail = (isPdf(file) ? "pdf · " : FileOps.isText(file) ? "text · " : "")
          + FileOps.size(file.length()) + " · " + FileOps.date(file.lastModified());
    }
    row.addView(label(detail, 9, MUTED));

    row.setOnClickListener(v -> {
      if (directory) open(file);
      else openFile(file);
    });
    more.setOnClickListener(v -> actions(file));
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.setMargins(0, dp(2), 0, dp(2));
    row.setLayoutParams(p);
    return row;
  }

  private void openFile(File file) {
    if (isPdf(file)) openPdf(file);
    else if (FileOps.isText(file)) viewText(file);
    else details(file);
  }

  private static boolean isPdf(File file) {
    return file.isFile() && file.getName().toLowerCase(Locale.US).endsWith(".pdf");
  }

  private void actions(File file) {
    List<String> labels = new ArrayList<>();
    List<Runnable> actions = new ArrayList<>();
    if (file.isDirectory()) {
      labels.add("Open folder");
      actions.add(() -> open(file));
    } else if (isPdf(file)) {
      labels.add("View PDF");
      actions.add(() -> openPdf(file));
    } else if (FileOps.isText(file)) {
      labels.add("View text");
      actions.add(() -> viewText(file));
    }
    labels.add("Rename");
    actions.add(() -> rename(file));
    labels.add("Copy to clipboard");
    actions.add(() -> setClipboard(file, false));
    labels.add("Move to clipboard");
    actions.add(() -> setClipboard(file, true));
    labels.add("Details");
    actions.add(() -> details(file));
    labels.add("Delete");
    actions.add(() -> confirmDelete(file));
    new AlertDialog.Builder(activity)
        .setTitle(file.getName())
        .setItems(labels.toArray(new String[0]), (dialog, index) -> {
          try {
            actions.get(index).run();
          } catch (Exception error) {
            setStatus(String.valueOf(error.getMessage()), RED);
          }
        })
        .setNegativeButton("CANCEL", null)
        .show();
  }

  private void setClipboard(File file, boolean move) {
    clipSource = file;
    clipMove = move;
    pasteButton.setText(move ? "PASTE (MOVE)" : "PASTE (COPY)");
    pasteButton.setVisibility(View.VISIBLE);
    setStatus((move ? "Move " : "Copied ") + file.getName()
        + " · open the destination folder, then PASTE.", CYAN);
  }

  private void paste() {
    File source = clipSource;
    File targetDirectory = current;
    if (source == null || targetDirectory == null) return;
    String sourcePath = source.getAbsolutePath();
    String targetPath = targetDirectory.getAbsolutePath();
    if (sourcePath.equals(targetPath) || targetPath.startsWith(sourcePath + File.separator)) {
      setStatus("A folder cannot be copied into itself.", RED);
      return;
    }
    final File destination = FileOps.uniqueTarget(targetDirectory, source.getName());
    final boolean move = clipMove;
    setStatus((move ? "Moving " : "Copying ") + source.getName() + "…", CYAN);
    new Thread(() -> {
      String failure = null;
      try {
        if (move) FileOps.move(source, destination);
        else FileOps.copy(source, destination);
      } catch (Exception error) {
        failure = "Failed: " + error.getMessage();
      }
      final String problem = failure;
      main.post(() -> {
        if (activity == null) return;
        if (problem != null) {
          setStatus(problem, RED);
          return;
        }
        clipSource = null;
        clipMove = false;
        pasteButton.setVisibility(View.GONE);
        setStatus("Saved to " + destination.getAbsolutePath(), GREEN);
        open(current);
      });
    }, "files-paste").start();
  }

  private void confirmDelete(File file) {
    new AlertDialog.Builder(activity)
        .setTitle("Delete " + file.getName() + "?")
        .setMessage(file.isDirectory()
            ? "This permanently deletes the folder and everything inside it."
            : "This permanently deletes the file.")
        .setPositiveButton("DELETE", (dialog, which) -> {
          setStatus("Deleting " + file.getName() + "…", CYAN);
          new Thread(() -> {
            String failure = null;
            try {
              FileOps.delete(file);
            } catch (Exception error) {
              failure = "Failed: " + error.getMessage();
            }
            final String problem = failure;
            main.post(() -> {
              if (activity == null) return;
              if (problem != null) {
                setStatus(problem, RED);
                return;
              }
              setStatus("Deleted " + file.getName() + ".", GREEN);
              open(current);
            });
          }, "files-delete").start();
        })
        .setNegativeButton("CANCEL", null)
        .show();
  }

  private void rename(File file) {
    EditText input = field(file.getName());
    LinearLayout box = new LinearLayout(activity);
    box.setPadding(dp(16), dp(8), dp(16), 0);
    box.addView(input, new LinearLayout.LayoutParams(-1, -2));
    new AlertDialog.Builder(activity)
        .setTitle("Rename")
        .setView(box)
        .setPositiveButton("RENAME", (dialog, which) -> {
          String name = input.getText().toString().trim();
          if (name.isEmpty() || name.contains("/")) {
            setStatus("Enter a valid file name.", RED);
            return;
          }
          File target = new File(file.getParentFile(), name);
          if (target.exists()) {
            setStatus("A file with that name already exists.", RED);
            return;
          }
          if (file.renameTo(target)) {
            setStatus("Renamed to " + name + ".", GREEN);
            open(current);
          } else {
            setStatus("Rename failed.", RED);
          }
        })
        .setNegativeButton("CANCEL", null)
        .show();
  }

  private void newFolder() {
    if (current == null) return;
    EditText input = field("New folder");
    LinearLayout box = new LinearLayout(activity);
    box.setPadding(dp(16), dp(8), dp(16), 0);
    box.addView(input, new LinearLayout.LayoutParams(-1, -2));
    new AlertDialog.Builder(activity)
        .setTitle("New folder")
        .setView(box)
        .setPositiveButton("CREATE", (dialog, which) -> {
          String name = input.getText().toString().trim();
          if (name.isEmpty() || name.contains("/")) {
            setStatus("Enter a valid folder name.", RED);
            return;
          }
          File target = new File(current, name);
          if (target.mkdir()) {
            setStatus("Created " + name + ".", GREEN);
            open(current);
          } else {
            setStatus("Could not create the folder.", RED);
          }
        })
        .setNegativeButton("CANCEL", null)
        .show();
  }

  private void jumpToPath() {
    EditText input = field(current == null ? "/" : current.getAbsolutePath());
    LinearLayout box = new LinearLayout(activity);
    box.setPadding(dp(16), dp(8), dp(16), 0);
    box.addView(input, new LinearLayout.LayoutParams(-1, -2));
    new AlertDialog.Builder(activity)
        .setTitle("Open path")
        .setView(box)
        .setPositiveButton("OPEN", (dialog, which) -> {
          String path = input.getText().toString().trim();
          if (path.isEmpty()) return;
          File directory = new File(path);
          if (!directory.isDirectory()) {
            setStatus("Not a readable folder: " + path, RED);
            return;
          }
          open(directory);
        })
        .setNegativeButton("CANCEL", null)
        .show();
  }

  private void details(File file) {
    StringBuilder text = new StringBuilder();
    text.append("Path: ").append(file.getAbsolutePath()).append('\n');
    text.append("Type: ").append(file.isDirectory() ? "folder"
        : isPdf(file) ? "PDF document" : FileOps.isText(file) ? "text file" : "binary file")
        .append('\n');
    if (file.isFile()) text.append("Size: ").append(FileOps.size(file.length()))
        .append(" (").append(file.length()).append(" bytes)\n");
    if (file.isDirectory()) text.append("Entries: ").append(FileOps.countChildren(file)).append('\n');
    text.append("Modified: ").append(FileOps.date(file.lastModified())).append('\n');
    text.append("Readable: ").append(file.canRead() ? "yes" : "no").append('\n');
    text.append("Writable: ").append(file.canWrite() ? "yes" : "no").append('\n');
    text.append("Executable: ").append(file.canExecute() ? "yes" : "no");
    new AlertDialog.Builder(activity)
        .setTitle(file.getName())
        .setMessage(text.toString())
        .setPositiveButton("CLOSE", null)
        .show();
  }

  // ------------------------------------------------------------------ viewer

  private LinearLayout buildViewerScreen() {
    LinearLayout root = new LinearLayout(activity);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(dp(9), dp(7), dp(9), dp(7));

    LinearLayout header = new LinearLayout(activity);
    header.setGravity(Gravity.CENTER_VERTICAL);
    viewerTitle = label("TEXT", 15, CYAN);
    viewerTitle.setSingleLine(true);
    viewerTitle.setEllipsize(TextUtils.TruncateAt.MIDDLE);
    viewerTitle.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
    header.addView(viewerTitle, new LinearLayout.LayoutParams(0, -2, 1));
    header.addView(chip("DROP", this::closeViewer), new LinearLayout.LayoutParams(dp(58), dp(30)));
    root.addView(header);

    ScrollView scroll = new ScrollView(activity);
    scroll.setFillViewport(true);
    viewerBody = label("", 11, TEXT);
    viewerBody.setTypeface(Typeface.MONOSPACE);
    viewerBody.setTextIsSelectable(true);
    viewerBody.setPadding(dp(6), dp(6), dp(6), dp(6));
    GradientDrawable frame = new GradientDrawable();
    frame.setColor(SURFACE);
    frame.setCornerRadius(dp(8));
    frame.setStroke(dp(1), BORDER);
    viewerBody.setBackground(frame);
    scroll.addView(viewerBody, new ScrollView.LayoutParams(-1, -2));
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    return root;
  }

  private void viewText(File file) {
    viewerTitle.setText(file.getName());
    viewerBody.setText("Loading…");
    show(viewerScreen);
    final File target = file;
    new Thread(() -> {
      String text;
      try {
        text = FileOps.readText(target);
      } catch (Exception error) {
        text = "Could not read this file: " + error.getMessage();
      }
      final String body = text;
      main.post(() -> {
        if (activity != null) viewerBody.setText(body);
      });
    }, "files-view").start();
  }

  private void closeViewer() {
    show(listScreen);
  }

  // ------------------------------------------------------------------ PDF viewer

  private LinearLayout buildPdfScreen() {
    LinearLayout root = new LinearLayout(activity);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(dp(9), dp(7), dp(9), dp(7));

    LinearLayout header = new LinearLayout(activity);
    header.setGravity(Gravity.CENTER_VERTICAL);
    pdfTitle = label("PDF", 15, CYAN);
    pdfTitle.setSingleLine(true);
    pdfTitle.setEllipsize(TextUtils.TruncateAt.MIDDLE);
    pdfTitle.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
    header.addView(pdfTitle, new LinearLayout.LayoutParams(0, -2, 1));
    header.addView(chip("DROP", this::closePdf), new LinearLayout.LayoutParams(dp(58), dp(30)));
    root.addView(header);

    LinearLayout controls = new LinearLayout(activity);
    controls.setGravity(Gravity.CENTER_VERTICAL);
    controls.addView(chip("‹", () -> pdfShiftPage(-1)), new LinearLayout.LayoutParams(0, dp(30), 1));
    pdfPageLabel = label("— / —", 10, MUTED);
    pdfPageLabel.setGravity(Gravity.CENTER);
    controls.addView(pdfPageLabel, new LinearLayout.LayoutParams(0, dp(30), 2));
    controls.addView(chip("›", () -> pdfShiftPage(1)), new LinearLayout.LayoutParams(0, dp(30), 1));
    controls.addView(chip("−", () -> pdfSetZoom(pdfZoom - .25f)), new LinearLayout.LayoutParams(0, dp(30), 1));
    controls.addView(chip("+", () -> pdfSetZoom(pdfZoom + .25f)), new LinearLayout.LayoutParams(0, dp(30), 1));
    root.addView(controls);

    HorizontalScrollView horizontal = new HorizontalScrollView(activity);
    horizontal.setHorizontalScrollBarEnabled(true);
    ScrollView vertical = new ScrollView(activity);
    vertical.setFillViewport(true);
    pdfImage = new ImageView(activity);
    pdfImage.setAdjustViewBounds(true);
    pdfImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
    vertical.addView(pdfImage, new ScrollView.LayoutParams(-2, -2));
    horizontal.addView(vertical, new HorizontalScrollView.LayoutParams(-2, -1));
    root.addView(horizontal, new LinearLayout.LayoutParams(-1, 0, 1));
    return root;
  }

  private void openPdf(File file) {
    closePdfRenderer();
    try {
      pdfDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
      pdfRenderer = new PdfRenderer(pdfDescriptor);
      pdfPageCount = pdfRenderer.getPageCount();
      pdfPage = 0;
      pdfZoom = 1f;
    } catch (Exception error) {
      closePdfRenderer();
      setStatus("Cannot open PDF: " + error.getMessage(), RED);
      details(file);
      return;
    }
    pdfTitle.setText(file.getName());
    show(pdfScreen);
    renderPdfPage();
  }

  private void pdfShiftPage(int delta) {
    if (pdfRenderer == null) return;
    int next = Math.max(0, Math.min(pdfPageCount - 1, pdfPage + delta));
    if (next == pdfPage) return;
    pdfPage = next;
    renderPdfPage();
  }

  private void pdfSetZoom(float zoom) {
    if (pdfRenderer == null) return;
    float limited = Math.max(.5f, Math.min(3f, zoom));
    if (Math.abs(limited - pdfZoom) < .01f) return;
    pdfZoom = limited;
    renderPdfPage();
  }

  private void renderPdfPage() {
    PdfRenderer renderer = pdfRenderer;
    if (renderer == null) return;
    final int page = pdfPage;
    final float zoom = pdfZoom;
    pdfPageLabel.setText((page + 1) + " / " + pdfPageCount
        + String.format(Locale.US, " · %.0f%%", zoom * 100));
    setStatus("Rendering page " + (page + 1) + "…", MUTED);
    new Thread(() -> {
      Bitmap bitmap = null;
      String failure = null;
      try {
        synchronized (this) {
          if (pdfRenderer != renderer) return;
          try (PdfRenderer.Page rendered = renderer.openPage(page)) {
            int screen = activity.getResources().getDisplayMetrics().widthPixels;
            int width = Math.max(320, Math.min(4096, (int) ((screen - dp(24)) * zoom)));
            int height = Math.max(1, (int) (width * rendered.getHeight()
                / (float) rendered.getWidth()));
            height = Math.min(height, 6000);
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(0xffffffff);
            rendered.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
          }
        }
      } catch (Exception error) {
        failure = "Cannot render page: " + error.getMessage();
      }
      final Bitmap image = bitmap;
      final String problem = failure;
      main.post(() -> {
        if (activity == null) return;
        if (problem != null) {
          setStatus(problem, RED);
          return;
        }
        if (image == null) return;
        pdfImage.setImageBitmap(image);
        pdfImage.setLayoutParams(new ScrollView.LayoutParams(image.getWidth(), image.getHeight()));
        setStatus(pdfPageCount + " pages · zoom " + ((int) (pdfZoom * 100)) + "%", MUTED);
      });
    }, "files-pdf").start();
  }

  private void closePdf() {
    closePdfRenderer();
    show(listScreen);
  }

  private synchronized void closePdfRenderer() {
    if (pdfImage != null) pdfImage.setImageDrawable(null);
    if (pdfRenderer != null) {
      try {
        pdfRenderer.close();
      } catch (Exception ignored) {
        // Nothing else to release.
      }
      pdfRenderer = null;
    }
    if (pdfDescriptor != null) {
      try {
        pdfDescriptor.close();
      } catch (Exception ignored) {
        // Nothing else to release.
      }
      pdfDescriptor = null;
    }
    pdfPage = 0;
    pdfPageCount = 0;
  }

  // ------------------------------------------------------------------ access + helpers

  private File root(String key) {
    if ("phone".equals(key)) return Environment.getExternalStorageDirectory();
    if ("app".equals(key)) return activity.getFilesDir();
    return new File(new File(activity.getFilesDir(), "modules/data/mod1"), "debian/rootfs");
  }

  private boolean needsAllFiles() {
    return android.os.Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager();
  }

  private boolean isPhonePath(File directory) {
    String path = directory.getAbsolutePath();
    return path.startsWith("/storage") || path.startsWith("/sdcard")
        || path.startsWith("/mnt/media_rw");
  }

  private void requestAllFiles() {
    try {
      Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
          Uri.parse("package:" + activity.getPackageName()));
      activity.startActivity(intent);
      setStatus("Enable All files access, then return here.", CYAN);
    } catch (Exception error) {
      try {
        activity.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
      } catch (Exception ignored) {
        setStatus("Open system Settings → Apps → oTraiL → Permissions manually.", RED);
      }
    }
  }

  @Override public void resume() {
    if (activity == null) return;
    if (current != null && isPhonePath(current) && !needsAllFiles()) open(current);
  }

  @Override public void pause() {}

  @Override public void close() {
    closePdfRenderer();
    activity = null;
    host = null;
    container = null;
    listScreen = null;
    viewerScreen = null;
    pdfScreen = null;
    entriesList = null;
  }

  private void show(View screen) {
    listScreen.setVisibility(screen == listScreen ? View.VISIBLE : View.GONE);
    viewerScreen.setVisibility(screen == viewerScreen ? View.VISIBLE : View.GONE);
    pdfScreen.setVisibility(screen == pdfScreen ? View.VISIBLE : View.GONE);
  }

  private void setStatus(String text, int color) {
    if (status == null) return;
    status.setText(text);
    status.setTextColor(color);
  }

  private TextView note(String text) {
    TextView view = label(text, 11, MUTED);
    view.setPadding(dp(6), dp(12), dp(6), dp(12));
    return view;
  }

  private TextView label(String text, int size, int color) {
    TextView view = new TextView(activity);
    view.setText(text);
    view.setTextSize(size);
    view.setTextColor(color);
    view.setIncludeFontPadding(false);
    view.setPadding(dp(2), dp(2), dp(2), dp(2));
    return view;
  }

  private EditText field(String value) {
    EditText input = new EditText(activity);
    input.setText(value);
    input.setTextSize(12);
    input.setTextColor(TEXT);
    input.setHintTextColor(0xff5f6f80);
    input.setSingleLine(true);
    input.setPadding(dp(9), dp(7), dp(9), dp(7));
    GradientDrawable frame = new GradientDrawable();
    frame.setColor(SURFACE_ALT);
    frame.setCornerRadius(dp(7));
    frame.setStroke(dp(1), BORDER);
    input.setBackground(frame);
    return input;
  }

  private Button chip(String text, Runnable action) {
    Button button = new Button(activity);
    button.setBackgroundTintList(null);
    button.setStateListAnimator(null);
    button.setText(text);
    button.setTextSize(9);
    button.setTextColor(CYAN);
    button.setAllCaps(false);
    button.setSingleLine(true);
    button.setEllipsize(TextUtils.TruncateAt.END);
    button.setMinHeight(0);
    button.setMinimumHeight(0);
    button.setMinWidth(0);
    button.setMinimumWidth(0);
    button.setPadding(dp(4), 0, dp(4), 0);
    GradientDrawable frame = new GradientDrawable();
    frame.setColor(0xff000000);
    frame.setCornerRadius(dp(7));
    frame.setStroke(dp(1), BORDER);
    button.setBackground(frame);
    button.setOnClickListener(v -> {
      try {
        action.run();
      } catch (Throwable error) {
        String message = error.getMessage();
        setStatus(message == null ? error.getClass().getSimpleName() : message, RED);
      }
    });
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, dp(30));
    p.setMargins(dp(2), 0, dp(2), 0);
    button.setLayoutParams(p);
    return button;
  }

  private int dp(int value) {
    return Math.round(value * activity.getResources().getDisplayMetrics().density);
  }
}

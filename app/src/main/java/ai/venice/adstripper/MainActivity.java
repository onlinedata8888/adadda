package ai.venice.adstripper;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.*;

import java.io.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int REQ_PICK = 1001;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newSingleThreadExecutor();

    private TextView status;
    private ProgressBar bar;
    private Button pick;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);

        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (16 * d);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("AdStripper");
        title.setTextSize(26);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Pick an APK. Its ad code gets neutralised, then the APK is rebuilt and signed.");
        sub.setPadding(0, pad / 2, 0, pad);
        root.addView(sub);

        pick = new Button(this);
        pick.setText("Pick an APK file");
        pick.setOnClickListener(v -> pickApk());
        root.addView(pick);

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(true);
        bar.setVisibility(View.GONE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = pad / 2;
        root.addView(bar, lp);

        status = new TextView(this);
        status.setTextIsSelectable(true);
        status.setPadding(0, pad, 0, 0);
        root.addView(status);

        setContentView(root);
    }

    private void pickApk() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_PICK);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK || res != RESULT_OK || data == null || data.getData() == null) return;

        final Uri uri = data.getData();
        final String display = queryName(uri);

        pick.setEnabled(false);
        bar.setVisibility(View.VISIBLE);
        status.setText("Preparing...");

        pool.execute(() -> {
            try {
                File inApk = new File(getCacheDir(), "input.apk");
                try (InputStream in = getContentResolver().openInputStream(uri);
                     OutputStream out = new FileOutputStream(inApk)) {
                    if (in == null) throw new IllegalStateException("cannot open " + uri);
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }

                String base = display.toLowerCase().endsWith(".apk")
                        ? display.substring(0, display.length() - 4) : display;
                File outApk = new File(getExternalFilesDir(null), base + "-noads.apk");
                if (outApk.exists()) outApk = new File(getExternalFilesDir(null), base + "-noads-2.apk");

                final File result = outApk;
                AdPatcher.patch(inApk, outApk, true, msg -> ui.post(() -> status.setText(msg)));

                ui.post(() -> {
                    bar.setVisibility(View.GONE);
                    pick.setEnabled(true);
                    status.setText("Done.\n\nSaved:\n" + result.getAbsolutePath()
                            + "\n\nUninstall the original app first, then install this file.");
                });
            } catch (Throwable t) {
                ui.post(() -> {
                    bar.setVisibility(View.GONE);
                    pick.setEnabled(true);
                    status.setText("Failed:\n" + t);
                });
            }
        });
    }

    private String queryName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) {
                    String s = c.getString(i);
                    if (s != null && !s.isEmpty()) return s;
                }
            }
        } catch (Exception ignored) { }
        return "app.apk";
    }
}

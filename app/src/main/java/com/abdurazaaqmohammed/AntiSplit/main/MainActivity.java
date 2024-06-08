package com.abdurazaaqmohammed.AntiSplit.main;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.abdurazaaqmohammed.AntiSplit.R;
import com.reandroid.apkeditor.merge.LogUtil;
import com.reandroid.apkeditor.merge.Merger;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;

public class MainActivity extends Activity implements Merger.LogListener {
    private final static int REQUEST_CODE_OPEN_SPLIT_APK_TO_ANTISPLIT = 1;
    private final static int REQUEST_CODE_SAVE_APK = 2;
    private final static boolean supportsFilePicker = Build.VERSION.SDK_INT>19;
    private static boolean logEnabled;

    private Uri splitAPKUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getActionBar().hide();
        setContentView(R.layout.activity_main);
        deleteDir(getExternalCacheDir());

        if (!supportsFilePicker) {
            findViewById(R.id.oldAndroidInfo).setVisibility(View.VISIBLE);
            // Android versions below 4.4 are too old to use the file picker for ACTION_OPEN_DOCUMENT/ACTION_CREATE_DOCUMENT. The location of the file must be manually input. The files will be saved to Download folder in the internal storage.
        }

        SharedPreferences settings = getSharedPreferences("set", Context.MODE_PRIVATE);

        // Fetch settings from SharedPreferences
        logEnabled = settings.getBoolean("logEnabled", true);
        LogUtil.setLogListener(this);
        LogUtil.setLogEnabled(logEnabled);

        Switch logSwitch = findViewById(R.id.logSwitch);
        logSwitch.setChecked(logEnabled);
        logSwitch.setOnCheckedChangeListener((buttonView, isChecked) ->{
            logEnabled = isChecked;
            LogUtil.setLogEnabled(logEnabled);
        });

        findViewById(R.id.decodeButton).setOnClickListener(v -> openFilePickerOrStartProcessing());

        // Check if user shared or opened file with the app.
        final Intent fromShareOrView = getIntent();
        final String fromShareOrViewAction = fromShareOrView.getAction();
        if (Intent.ACTION_SEND.equals(fromShareOrViewAction)) {
            splitAPKUri = fromShareOrView.getParcelableExtra(Intent.EXTRA_STREAM);
        } else if (Intent.ACTION_VIEW.equals(fromShareOrViewAction)) {
            splitAPKUri = fromShareOrView.getData();
        }
        if (splitAPKUri != null) {
            if(supportsFilePicker) {
                openFileManagerToSaveAPK();
            } else {
                new ProcessTask(this).execute(Uri.fromFile(new File(Environment.getExternalStorageDirectory() + File.separator + "Download" + File.separator + getOriginalFileName(this, splitAPKUri))));
            }
        }
    }
    @Override
    protected void onPause() {
        final SharedPreferences settings = getSharedPreferences("set", Context.MODE_PRIVATE);
        settings.edit().putBoolean("logEnabled", logEnabled).apply();
        super.onPause();
    }

    @Override
    public void onLog(String log) {
        runOnUiThread(() -> {
            TextView logTextView = findViewById(R.id.logField);
            logTextView.append(log + "\n");
            ScrollView scrollView = findViewById(R.id.scrollView);
            scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
        });
    }
    private void openFilePickerOrStartProcessing() {
        if (supportsFilePicker) {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"application/zip", "application/octet-stream"}); // XAPK is octet-stream
            startActivityForResult(intent, REQUEST_CODE_OPEN_SPLIT_APK_TO_ANTISPLIT);
        } else {
            TextView workingFileField = findViewById(R.id.workingFileField);
            final String workingFilePath = workingFileField.getText().toString();
            splitAPKUri = Uri.fromFile(new File(workingFilePath));
            new ProcessTask(this).execute(Uri.fromFile(new File(Environment.getExternalStorageDirectory() + File.separator + "Download" + File.separator + workingFilePath.substring(workingFilePath.lastIndexOf("/") + 1).replace(".apks", "_antisplit.apk").replace(".xapk", "_antisplit.apk").replace(".apkm", "_antisplit.apk"))));
        }
    }

    private void process(Uri outputUri) {
        ((TextView) findViewById(R.id.logField)).setText("");
        final File cacheDir = getExternalCacheDir();
        deleteDir(cacheDir);
        try {
            Merger.run(getContentResolver().openInputStream(splitAPKUri), cacheDir, getContentResolver().openOutputStream(outputUri));
        } catch (IOException e) {
            showError(e.toString());
        }
    }

    public static void deleteDir(File dir){
        String[] children = dir.list();
        for (String child : children) {
            new File(dir, child).delete();
        }
    }

    @Override
    protected void onDestroy() {
        deleteDir(getExternalCacheDir());
        super.onDestroy();
    }


    private static class ProcessTask extends AsyncTask<Uri, Void, String> {
        private WeakReference<MainActivity> activityReference;

        // only retain a weak reference to the activity
        ProcessTask(MainActivity context) {
            activityReference = new WeakReference<>(context);
        }

        @Override
        protected String doInBackground(Uri... uris) {
            MainActivity activity = activityReference.get();
            activity.process(uris[0]);
            activity.showSuccess();
            return null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                switch(requestCode) {
                    case REQUEST_CODE_OPEN_SPLIT_APK_TO_ANTISPLIT:
                        splitAPKUri = uri;
                        openFileManagerToSaveAPK();
                        break;
                    case REQUEST_CODE_SAVE_APK:
                        new ProcessTask(this).execute(uri);
                        break;
                }
            }
        }
    }

    private void showSuccess() {
        final String successMessage = getString(R.string.success_saved);
        LogUtil.logMessage(successMessage);
        runOnUiThread(() -> Toast.makeText(this, successMessage, Toast.LENGTH_SHORT).show());
    }

    private void showError(String error) {
        runOnUiThread(() -> {
            TextView errorBox = findViewById(R.id.errorField);
            errorBox.setVisibility(View.VISIBLE);
            errorBox.setText(error);
            Toast.makeText(this, error, Toast.LENGTH_SHORT).show();
        });
    }

    private String getOriginalFileName(Context context, Uri uri) {
        String result = null;
        if (uri.getScheme().equals("content")) {
            try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    result = cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME));
                }
            }
        }
        if (result == null) {
            result = uri.getPath();
            int cut = result.lastIndexOf('/');
            if (cut != -1) {
                result = result.substring(cut + 1);
            }
        }
        return result.replaceFirst("\\.(?:xapk|apk[sm])", "_antisplit");
    }

    private void openFileManagerToSaveAPK() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/vnd.android.package-archive");
        intent.putExtra(Intent.EXTRA_TITLE, getOriginalFileName(this, splitAPKUri));
        startActivityForResult(intent, REQUEST_CODE_SAVE_APK);
    }
}
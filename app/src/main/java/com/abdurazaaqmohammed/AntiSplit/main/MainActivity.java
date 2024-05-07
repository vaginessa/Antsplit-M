package com.abdurazaaqmohammed.AntiSplit.main;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import android.content.SharedPreferences;

import com.abdurazaaqmohammed.AntiSplit.R;
import com.reandroid.apkeditor.merge.LogUtil;
import com.reandroid.apkeditor.merge.Merger;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;

public class MainActivity extends Activity implements Merger.LogListener {
    private final static int REQUEST_CODE_OPEN_SPLIT_APK_TO_ANTISPLIT = 1;
    private final static int REQUEST_CODE_SAVE_APK = 2;
    private final static boolean isOldAndroid = Build.VERSION.SDK_INT<19;
    private static boolean logEnabled;
    private static String newFileName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getActionBar().hide();
        setContentView(R.layout.activity_main);
        deleteDir(getExternalCacheDir());

        if (isOldAndroid) {
            findViewById(R.id.oldAndroidInfo).setVisibility(View.VISIBLE);
            // Android versions below 4.4 are too old to use the file picker for ACTION_OPEN_DOCUMENT/ACTION_CREATE_DOCUMENT. The location of the file must be manually input. The files will be saved to Download folder in the internal storage.
        } else {
            findViewById(R.id.workingFileField).setVisibility(View.INVISIBLE);
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
        Uri sharedUri = null;
        if (Intent.ACTION_SEND.equals(fromShareOrViewAction)) {
            sharedUri = fromShareOrView.getParcelableExtra(Intent.EXTRA_STREAM);
        } else if (Intent.ACTION_VIEW.equals(fromShareOrViewAction)) {
            sharedUri = fromShareOrView.getData();
        }
        if (sharedUri != null) {
            new ProcessTask(this).execute(sharedUri);
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
            logTextView.scrollTo(0, logTextView.getBottom());
        });
    }
    @SuppressLint("InlinedApi")
    private void openFilePickerOrStartProcessing() {
        if (isOldAndroid) {
            TextView workingFileField = findViewById(R.id.workingFileField);
            final String workingFilePath = workingFileField.getText().toString();
            try {
                Merger.runOldAndroid(getContentResolver().openInputStream(Uri.parse(workingFilePath)), getExternalCacheDir(), workingFilePath.substring(workingFilePath.lastIndexOf("/") + 1));
            } catch (IOException e) {
                showError(e.toString());
            }
        } else {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"application/zip", "application/octet-stream"}); // XAPK is octet-stream
            startActivityForResult(intent, REQUEST_CODE_OPEN_SPLIT_APK_TO_ANTISPLIT);
        }
    }

    private void process(Uri splitAPKUri) {
        clearLogField();
        newFileName = getOriginalFileName(this, splitAPKUri);
        try {
            Merger.run(getContentResolver().openInputStream(splitAPKUri), getExternalCacheDir());
            LogUtil.logMessage(getString(R.string.success));
        } catch (IOException e) {
            showError(e.toString());
        }
    }

    public static void deleteDir(File dir){
        if(!dir.exists()){
            return;
        }
        if(dir.isFile()){
            dir.delete();
            return;
        }
        if(!dir.isDirectory()){
            return;
        }
        File[] files=dir.listFiles();
        if(files==null){
            deleteEmptyDirectories(dir);
            return;
        }
        for(File file:files){
            deleteDir(file);
        }
        deleteEmptyDirectories(dir);
    }
    public static void deleteEmptyDirectories(File dir){
        if(dir==null || !dir.isDirectory()){
            return;
        }
        File[] filesList = dir.listFiles();
        if(filesList == null || filesList.length == 0){
            dir.delete();
            return;
        }
        int count = filesList.length;
        for(int i = 0; i < count; i++){
            File file = filesList[i];
            if(file.isFile() && file.length() != 0){
                return;
            }
        }
        count = filesList.length;
        for(int i = 0; i < count; i++){
            File file = filesList[i];
            if(file.isDirectory()){
                deleteEmptyDirectories(file);
}
        }
        filesList = dir.listFiles();
        if(filesList == null || filesList.length == 0){
            dir.delete();
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

            deleteDir(activity.getExternalCacheDir());
            activity.process(uris[0]);
            return activity.getString(R.string.success);
        }

        @Override
        protected void onPostExecute(String result) {
            MainActivity activity = activityReference.get();
            Toast.makeText(activity, result, Toast.LENGTH_SHORT).show();

            activity.openFileManagerToSaveAPK();
        }
    }

    private static class CopyTask extends AsyncTask<Uri, Void, String> {
        private WeakReference<MainActivity> activityReference;

        // only retain a weak reference to the activity
        CopyTask(MainActivity context) {
            activityReference = new WeakReference<>(context);
        }
        @Override
        protected String doInBackground(Uri...uris) {
            MainActivity activity = activityReference.get();

            // This is stupid figure out how to refactor it to write to the output URI from ACTION_CREATE_DOCUMENT directly
            File fileInCacheDir = new File(activity.getExternalCacheDir() + File.separator + "AntiSplit M Output.apk");
            InputStream inputStream = null;
            try {
                inputStream = new FileInputStream(fileInCacheDir);

            OutputStream outputStream = activity.getContentResolver().openOutputStream(uris[0]);

            byte[] buffer = new byte[1024];
            int length;
            while ((length = inputStream.read(buffer)) > 0) {
                outputStream.write(buffer, 0, length);
            }

            inputStream.close();
            outputStream.close();
            } catch (IOException e) {
                activity.showError(e.toString());
            }
            return activity.getString(R.string.success_saved);
        }

        @Override
        protected void onPostExecute(String result) {
            MainActivity activity = activityReference.get();
            Toast.makeText(activity.getApplicationContext(), result, Toast.LENGTH_SHORT).show();
        }
    }

    private void clearLogField() {
        TextView logTextView = findViewById(R.id.logField);
        logTextView.setText("");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
               switch(requestCode) {
                   case REQUEST_CODE_OPEN_SPLIT_APK_TO_ANTISPLIT:
                       new ProcessTask(this).execute(uri);
                       break;
                   case REQUEST_CODE_SAVE_APK:
                       new CopyTask(this).execute(uri);
                   break;
               }
            }
        }
    }
    private void showError(String error) {
        TextView errorBox = findViewById(R.id.errorField);
        errorBox.setVisibility(View.VISIBLE);
        errorBox.setText(error);
        Toast.makeText(this, error, Toast.LENGTH_SHORT).show();
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
        return result.replaceFirst("\\.(apks|xapk|apkm)", "_antisplit");
    }

    private void openFileManagerToSaveAPK() {
        @SuppressLint("InlinedApi") Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/vnd.android.package-archive");
        intent.putExtra(Intent.EXTRA_TITLE, newFileName);
        startActivityForResult(intent, REQUEST_CODE_SAVE_APK);
    }
}

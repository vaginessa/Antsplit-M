package com.abdurazaaqmohammed.AntiSplit.main;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.abdurazaaqmohammed.AntiSplit.R;
import com.reandroid.apkeditor.merge.Merger;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;

public class MainActivity extends Activity {
    private final int REQUEST_CODE_OPEN_SPLIT_APK_TO_ANTISPLIT = 1;
    private final int REQUEST_CODE_SAVE_APK = 2;

    private final boolean isOldAndroid = Build.VERSION.SDK_INT<19;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getActionBar().hide();
        setContentView(R.layout.activity_main);
        deleteDir(getExternalCacheDir());

        if (isOldAndroid) {
            findViewById(R.id.oldAndroidInfo).setVisibility(View.VISIBLE);
            // Android versions below 4.4 are too old to use the file picker for ACTION_OPEN_DOCUMENT/ACTION_CREATE_DOCUMENT. The location of the file must be manually input. The files will be saved to Download folder in the internal storage.
        }

        findViewById(R.id.decodeButton).setOnClickListener(v -> openFilePickerOrStartProcessing());
        // Check if user shared or opened file with the app.
        final Intent fromShareOrView = getIntent();
        final String fromShareOrViewAction = fromShareOrView.getAction();
        if (Intent.ACTION_SEND.equals(fromShareOrViewAction)) {
            Uri sharedUri = fromShareOrView.getParcelableExtra(Intent.EXTRA_STREAM);
            if (sharedUri != null) process(sharedUri);
        } else if (Intent.ACTION_VIEW.equals(fromShareOrViewAction)) {
            Uri sharedUri = fromShareOrView.getData();
            if (sharedUri != null) process(sharedUri);
        }
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
        try {
            Merger.run(getContentResolver().openInputStream(splitAPKUri), getExternalCacheDir());
            openFileManagerToSaveAPK(splitAPKUri);
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

    public static File newFile(File outputDir, ZipEntry zipEntry) throws IOException {
        File file = new File(outputDir, zipEntry.getName());
        String canonicalizedPath = file.getCanonicalPath();
        if (!canonicalizedPath.startsWith(outputDir.getCanonicalPath() + File.separator)) {
            throw new IOException("Zip entry is outside of the target dir: " + zipEntry.getName());
        }
        return file;
    }
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                try {
                   switch(requestCode) {
                       case REQUEST_CODE_OPEN_SPLIT_APK_TO_ANTISPLIT:
                           deleteDir(getExternalCacheDir());
                           process(uri);
                           break;
                       case REQUEST_CODE_SAVE_APK:
                           // This is stupid figure out how to refactor it to write to the output URI from ACTION_CREATE_DOCUMENT directly
                           File fileInCacheDir = new File(getExternalCacheDir() + File.separator + "AntiSplit M Output.apk");
                           InputStream inputStream = new FileInputStream(fileInCacheDir);
                           OutputStream outputStream = getContentResolver().openOutputStream(uri);

                           byte[] buffer = new byte[1024];
                           int length;
                           while ((length = inputStream.read(buffer)) > 0) {
                               outputStream.write(buffer, 0, length);
                           }

                           inputStream.close();
                           outputStream.close();
                           Toast.makeText(this, "File saved successfully", Toast.LENGTH_SHORT).show();
                           TextView errorBox = findViewById(R.id.errorField);
                           errorBox.setVisibility(View.INVISIBLE);
                       break;
                   }
                } catch (IOException e) {
                    showError(e.toString());
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
        return result;
    }

    private void openFileManagerToSaveAPK(Uri splitAPKUri) {
        @SuppressLint("InlinedApi") Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/vnd.android.package-archive");
        intent.putExtra(Intent.EXTRA_TITLE, getOriginalFileName(this, splitAPKUri).replace(".apks", "_antisplit").replace(".xapk", "_antisplit").replace(".apkm", "_antisplit"));
        startActivityForResult(intent, REQUEST_CODE_SAVE_APK);
    }
}
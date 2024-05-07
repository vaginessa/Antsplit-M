/*
 *  Copyright (C) 2022 github.com/REAndroid
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.reandroid.apkeditor.merge;

import com.reandroid.apk.ApkBundle;
import com.reandroid.apk.ApkModule;
import com.reandroid.apkeditor.common.AndroidManifestHelper;
import com.reandroid.app.AndroidManifest;
import com.reandroid.archive.ZipEntryMap;
import com.reandroid.arsc.chunk.TableBlock;
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock;
import com.reandroid.arsc.chunk.xml.ResXmlAttribute;
import com.reandroid.arsc.chunk.xml.ResXmlElement;
import com.reandroid.arsc.container.SpecTypePair;
import com.reandroid.arsc.model.ResourceEntry;
import com.reandroid.arsc.value.Entry;
import com.reandroid.arsc.value.ResValue;
import com.reandroid.arsc.value.ValueType;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class Merger {

    private static File newFile(File outputDir, ZipEntry zipEntry) throws IOException {
        File file = new File(outputDir, zipEntry.getName());
        String canonicalizedPath = file.getCanonicalPath();
        if (!canonicalizedPath.startsWith(outputDir.getCanonicalPath() + File.separator)) {
            throw new IOException("Zip entry is outside of the target dir: " + zipEntry.getName());
        }
        return file;
    }
    private static void extractZip(InputStream zi, File outputDir) throws IOException {
        byte[] buffer = new byte[1024];
        try (ZipInputStream zis = new ZipInputStream(zi)) {
            ZipEntry zipEntry = zis.getNextEntry();
            while (zipEntry != null) {
                File newFile = newFile(outputDir, zipEntry);
                final String name = zipEntry.getName();
                if(name.endsWith(".apk")) {
                    FileOutputStream fos = new FileOutputStream(newFile);
                    int len;
                    while ((len = zis.read(buffer)) > 0) {
                        fos.write(buffer, 0, len);
                    }
                    fos.close();
                    LogUtil.logMessage("Extracted " + name);
                }
                zipEntry = zis.getNextEntry();
            }
            zis.closeEntry();
        }
    }
    private static void sanitizeManifest(ApkModule apkModule) {
        if(!apkModule.hasAndroidManifest()){
            return;
        }
        AndroidManifestBlock manifest = apkModule.getAndroidManifest();
        LogUtil.logMessage("Sanitizing manifest ...");
        AndroidManifestHelper.removeAttributeFromManifestByName(manifest,
                AndroidManifest.NAME_requiredSplitTypes);
        AndroidManifestHelper.removeAttributeFromManifestByName(manifest,
                AndroidManifest.NAME_splitTypes);
        AndroidManifestHelper.removeAttributeFromManifestAndApplication(manifest,
                AndroidManifest.ID_extractNativeLibs,
                 AndroidManifest.NAME_extractNativeLibs);
        AndroidManifestHelper.removeAttributeFromManifestAndApplication(manifest,
                AndroidManifest.ID_isSplitRequired,
                 AndroidManifest.NAME_isSplitRequired);
        ResXmlElement application = manifest.getApplicationElement();
        List<ResXmlElement> splitMetaDataElements =
                AndroidManifestHelper.listSplitRequired(application);
        boolean splits_removed = false;
        for(ResXmlElement meta : splitMetaDataElements){
            if(!splits_removed){
                splits_removed = removeSplitsTableEntry(meta, apkModule);
            }
            LogUtil.logMessage("Removed-element : <" + meta.getName() + "> name=\""
                    + AndroidManifestHelper.getNamedValue(meta) + "\"");
            application.remove(meta);
        }
        manifest.refresh();
    }
    private static boolean removeSplitsTableEntry(ResXmlElement metaElement, ApkModule apkModule) {
        ResXmlAttribute nameAttribute = metaElement.searchAttributeByResourceId(AndroidManifest.ID_name);
        if(nameAttribute == null){
            return false;
        }
        if(!"com.android.vending.splits".equals(nameAttribute.getValueAsString())){
            return false;
        }
        ResXmlAttribute valueAttribute=metaElement.searchAttributeByResourceId(
                AndroidManifest.ID_value);
        if(valueAttribute==null){
            valueAttribute=metaElement.searchAttributeByResourceId(
                    AndroidManifest.ID_resource);
        }
        if(valueAttribute == null
                || valueAttribute.getValueType() != ValueType.REFERENCE){
            return false;
        }
        if(!apkModule.hasTableBlock()){
            return false;
        }
        TableBlock tableBlock = apkModule.getTableBlock();
        ResourceEntry resourceEntry = tableBlock.getResource(valueAttribute.getData());
        if(resourceEntry == null){
            return false;
        }
        ZipEntryMap zipEntryMap = apkModule.getZipEntryMap();
        for(Entry entry : resourceEntry){
            if(entry == null){
                continue;
            }
            ResValue resValue = entry.getResValue();
            if(resValue == null){
                continue;
            }
            String path = resValue.getValueAsString();
            LogUtil.logMessage("Removed-table-entry : "+path);
            //Remove file entry
            zipEntryMap.remove(path);
            // It's not safe to destroy entry, resource id might be used in dex code.
            // Better replace it with boolean value.
            entry.setNull(true);
            SpecTypePair specTypePair = entry.getTypeBlock()
                    .getParentSpecTypePair();
            specTypePair.removeNullEntries(entry.getId());
        }
        return true;
    }

    public interface LogListener {
        void onLog(String log);
    }

    public static void run(InputStream ins, File cacheDir) throws IOException {
        LogUtil.logMessage("Searching apk files ...");
        extractZip(ins, cacheDir);
        ApkBundle bundle=new ApkBundle();
        bundle.loadApkDirectory(cacheDir, false);
        LogUtil.logMessage("Found modules: "+bundle.getApkModuleList().size());

        ApkModule mergedModule=bundle.mergeModules();
        sanitizeManifest(mergedModule);
        mergedModule.writeApk(new File(cacheDir + File.separator + "AntiSplit M Output.apk"));
        mergedModule.close();
        bundle.close();
    }

    public static void runOldAndroid(InputStream ins, File cacheDir, String filename) throws IOException {
        extractZip(ins, cacheDir);
        ApkBundle bundle=new ApkBundle();
        bundle.loadApkDirectory(cacheDir, false);

        ApkModule mergedModule=bundle.mergeModules();
        sanitizeManifest(mergedModule);
        mergedModule.writeApk(new File("/sdcard/Download" + File.separator + filename.replace(".apks", "_antisplit.apk").replace(".xapk", "_antisplit.apk").replace(".apkm", "_antisplit.apk")));
        mergedModule.close();
        bundle.close();
    }
}

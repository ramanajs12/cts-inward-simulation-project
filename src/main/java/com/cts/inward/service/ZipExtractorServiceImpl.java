package com.cts.inward.service;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class ZipExtractorServiceImpl implements ZipExtractorService {

    @Override
    public Map<String, File> extractZip(File zipFile, String outputFolder) {

        // This map holds all extracted files — key is filename, value is the File on disk
        Map<String, File> imageMap = new HashMap<>();

        // If ZIP file is missing, no point going further
        if (zipFile == null || !zipFile.exists()) {
            throw new RuntimeException("ZIP file not found : " + zipFile);
        }

        try {
            // Create the output folder if it doesn't already exist
            File outputDir = new File(outputFolder);
            if (!outputDir.exists()) {
                outputDir.mkdirs();
            }

            // Open the ZIP as a stream so we can read entries one by one
            ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFile.toPath()));

            ZipEntry entry;

            // Loop through every file/folder inside the ZIP until nothing is left
            while ((entry = zis.getNextEntry()) != null) {

                // Build the full disk path where this entry will be saved
                File extractedFile = new File(outputDir, entry.getName());

                // If this entry is a folder, just create it and move to next entry
                if (entry.isDirectory()) {
                    extractedFile.mkdirs();
                    zis.closeEntry();
                    continue;
                }

                // Make sure the parent folder exists before writing the file
                File parentFolder = extractedFile.getParentFile();
                if (parentFolder != null && !parentFolder.exists()) {
                    parentFolder.mkdirs();
                }

                // Write the extracted file content to disk
                FileOutputStream fos = new FileOutputStream(extractedFile);

                // Read and write in 4KB chunks — avoids loading large image files into memory
                byte[] buffer = new byte[4096];
                int len;

                while ((len = zis.read(buffer)) != -1) {
                    fos.write(buffer, 0, len);
                }

                fos.close();

                // Register the file in our map — filename is the key used later to match with XML
                imageMap.put(extractedFile.getName(), extractedFile);

                // Mark this entry as done before moving to the next one
                zis.closeEntry();
            }

            zis.close();

            System.out.println("Total Images Extracted : " + imageMap.size());

        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Failed to extract ZIP file : " + e.getMessage(), e);
        }

        // Return all extracted files — empty map if ZIP had no files
        return imageMap;
    }
}
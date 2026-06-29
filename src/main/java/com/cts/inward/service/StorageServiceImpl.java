package com.cts.inward.service;

import java.io.File;
import java.io.FileInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import com.cts.util.PropertyUtil;

public class StorageServiceImpl implements StorageService {

    // Supabase project URL — loaded from application.properties
    private final String supabaseUrl  = PropertyUtil.getProperty("supabase.url");

    // Secret key for authenticating API requests — never hardcode this
    private final String supabaseKey  = PropertyUtil.getProperty("supabase.secret.key");

    // Bucket name where all cheque images will be stored
    private final String bucket       = PropertyUtil.getProperty("supabase.bucket");

    // Reusing one HttpClient for all uploads — more efficient than creating a new one each time
    private final HttpClient httpClient = HttpClient.newHttpClient();
    
    @Override
    public String uploadFile(File file, String folderPath) {

        // If the file doesn't exist on disk, no point sending the request
        if (file == null || !file.exists()) {
            throw new RuntimeException("File not found for upload : " + file);
        }

        try {
            // Build the path inside the bucket — e.g. "inward/Batch_001/CHQ_001.tif"
            String objectPath = folderPath + "/" + file.getName();

            // Full Supabase Storage upload endpoint for this file
            String uploadUrl = supabaseUrl
                    + "/storage/v1/object/"
                    + bucket
                    + "/"
                    + objectPath;

            // Build the PUT request — Supabase uses PUT to upload files
            // Streaming the file directly from disk so we don't load it fully into memory
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(uploadUrl))
                    .header("Authorization", "Bearer " + supabaseKey)
                    .header("apikey", supabaseKey)
                    .header("Content-Type", "application/octet-stream")
                    .PUT(HttpRequest.BodyPublishers.ofInputStream(() -> {
                        try {
                            return new FileInputStream(file);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }))
                    .build();

            // Send the request and wait for Supabase to respond
            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString());

            // 200 means file was replaced, 201 means it was freshly uploaded — both are success
            if (response.statusCode() == 200 || response.statusCode() == 201) {

                // Return the public URL — this gets stored in InwardCheque.frontImagePath / rearImagePath
                return supabaseUrl
                        + "/storage/v1/object/public/"
                        + bucket
                        + "/"
                        + objectPath;
            }

            // If we get here, Supabase rejected the upload — log status and response for debugging
            throw new RuntimeException("Upload failed. Status : "
                    + response.statusCode()
                    + " Response : "
                    + response.body());

        } catch (Exception e) {
            // Wrap and throw so BatchProcessingService can catch it and mark the cheque as failed
            throw new RuntimeException("Storage upload error : " + e.getMessage(), e);
        }
    }
}
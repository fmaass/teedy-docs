package com.sismics.docs.rest;

import com.google.common.io.ByteStreams;
import com.google.common.io.Resources;
import com.sismics.docs.core.util.DirectoryUtil;
import com.sismics.util.filter.TokenBasedSecurityFilter;
import org.glassfish.jersey.media.multipart.FormDataMultiPart;
import org.glassfish.jersey.media.multipart.MultiPartFeature;
import org.glassfish.jersey.media.multipart.file.StreamDataBodyPart;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;

/**
 * Exhaustive test of the document resource.
 * 
 * @author bgamard
 */
public class TestDocumentResource extends BaseJerseyTest {
    /**
     * Test the document resource.
     * 
     * @throws Exception e
     */
    @Test
    public void testDocumentResource() throws Exception {
        // Login document1
        clientUtil.createUser("document1");
        String document1Token = clientUtil.login("document1");
        
        // Login document3
        clientUtil.createUser("document3");
        String document3Token = clientUtil.login("document3");
        
        // Create a tag
        JsonObject json = target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, document1Token)
                .put(Entity.form(new Form()
                        .param("name", "SuperTag")
                        .param("color", "#ffff00")), JsonObject.class);
        String tag1Id = json.getString("id");
        Assertions.assertNotNull(tag1Id);

        // Create a tag
        json = target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, document1Token)
                .put(Entity.form(new Form()
                        .param("name", "HR")
                        .param("color", "#0000ff")), JsonObject.class);
        String tag2Id = json.getString("id");
        Assertions.assertNotNull(tag2Id);

        // Create a document with document1
        long create1Date = new Date().getTime();
        json = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, document1Token)
                .put(Entity.form(new Form()
                        .param("title", "My super title document 1")
                        .param("description", "My super description for document 1")
                        .param("subject", "Subject document 1")
                        .param("identifier", "Identifier document 1")
                        .param("publisher", "Publisher document 1")
                        .param("format", "Format document 1")
                        .param("source", "Source document 1")
                        .param("type", "Software")
                        .param("coverage", "Greenland")
                        .param("rights", "Public Domain")
                        .param("tags", tag1Id)
                        .param("tags", tag2Id)
                        .param("language", "eng")
                        .param("create_date", Long.toString(create1Date))), JsonObject.class);
        String document1Id = json.getString("id");
        Assertions.assertNotNull(document1Id);

        // Add a file to this document
        String file1Id = clientUtil.addFileToDocument(FILE_EINSTEIN_ROOSEVELT_LETTER_PNG,
                document1Token, document1Id);

        // Share this document
        target().path("/share").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, document1Token)
                .put(Entity.form(new Form().param("id", document1Id)), JsonObject.class);

        // Create another document with document1
        json = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, document1Token)
                .put(Entity.form(new Form()
                        .param("title", "My super title document 2")
                        .param("language", "eng")
                        .param("tags", tag2Id)
                        .param("relations", document1Id)), JsonObject.class);
        String document2Id = json.getString("id");
        Assertions.assertNotNull(document2Id);

        // List all documents
        json = target().path("/document/list")
                .queryParam("sort_column", 3)
                .queryParam("asc", true)
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, document1Token)
                .get(JsonObject.class);
        JsonArray documents = json.getJsonArray("documents");
        JsonArray tags = documents.getJsonObject(0).getJsonArray("tags");
        Assertions.assertEquals(2, documents.size());
        Assertions.assertNotNull(documents.getJsonObject(0).get("update_date"));
        Assertions.assertEquals(document1Id, documents.getJsonObject(0).getString("id"));
        Assertions.assertEquals("eng", documents.getJsonObject(0).getString("language"));
        Assertions.assertEquals(file1Id, documents.getJsonObject(0).getString("file_id"));
        Assertions.assertEquals(1, documents.getJsonObject(0).getInt("file_count"));
        Assertions.assertEquals(2, tags.size());
        Assertions.assertEquals(tag2Id, tags.getJsonObject(0).getString("id"));
        Assertions.assertEquals("HR", tags.getJsonObject(0).getString("name"));
        Assertions.assertEquals("#0000ff", tags.getJsonObject(0).getString("color"));
        Assertions.assertEquals(tag1Id, tags.getJsonObject(1).getString("id"));
        Assertions.assertEquals("SuperTag", tags.getJsonObject(1).getString("name"));
        Assertions.assertEquals("#ffff00", tags.getJsonObject(1).getString("color"));

        // List all documents from document3
        json = target().path("/document/list")
                .queryParam("sort_column", 3)
                .queryParam("asc", false)
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, document3Token)
                .get(JsonObject.class);
        documents = json.getJsonArray("documents");
        Assertions.assertTrue(documents.isEmpty());
        
        // Create a document with document3
        long create3Date = new Date().getTime();
        json = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, document3Token)
                .put(Entity.form(new Form()
                        .param("title", "My_super_title_document_3")
                        .param("description", "My super description for document 3")
                        .param("language", "eng")
                        .param("create_date", Long.toString(create3Date))), JsonObject.class);
        String document3Id = json.getString("id");
        Assertions.assertNotNull(document3Id);
        
        // Add a file to this document
        clientUtil.addFileToDocument(FILE_EINSTEIN_ROOSEVELT_LETTER_PNG,
                document3Token, document3Id);

        // Create another document with document3
        json = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, document3Token)
                .put(Entity.form(new Form()
                        .param("title", "My_super_title_document_4")
                        .param("language", "eng")), JsonObject.class);
        String document4Id = json.getString("id");
        Assertions.assertNotNull(document4Id);

        // List all documents from document3
        json = target().path("/document/list")
                .queryParam("sort_column", 3)
                .queryParam("asc", false)
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, document3Token)
                .get(JsonObject.class);
        documents = json.getJsonArray("documents");
        Assertions.assertEquals(2, documents.size());

        // Check highlights
        json = target().path("/document/list")
                .queryParam("search", "full:uranium full:einstein")
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, document1Token)
                .get(JsonObject.class);
        String highlight = json.getJsonArray("documents").getJsonObject(0).getString("highlight");
        Assertions.assertTrue(highlight.contains("<strong>"));

        // Check suggestions. The suggestion dictionary is built from the Lucene "title" field, which
        // is populated by the ASYNC document-indexing listener after the create request returned — so
        // under load the "document" token may not be in the index yet when this runs, and the fuzzy
        // suggester returns a shorter/other match. Poll the real suggestion endpoint until the index
        // reflects the indexed titles; the assertion (top suggestion is exactly "document") is
        // unchanged and still proven.
        final String[] lastSuggestion = {null};
        awaitCondition(() -> "search=docu never suggested \"document\" (last top suggestion: "
                + lastSuggestion[0] + ")", () -> {
            JsonObject suggestJson = target().path("/document/list")
                    .queryParam("search", "docu")
                    .request()
                    .cookie(TokenBasedSecurityFilter.COOKIE_NAME, document1Token)
                    .get(JsonObject.class);
            JsonArray suggestions = suggestJson.getJsonArray("suggestions");
            lastSuggestion[0] = suggestions.isEmpty() ? null : suggestions.getString(0);
            return "document".equals(lastSuggestion[0]);
        });
        String suggestion = lastSuggestion[0];
        Assertions.assertEquals("document", suggestion);

        // Search documents
        Assertions.assertEquals(1, searchDocuments("full:uranium full:einstein", document1Token));
        Assertions.assertEquals(2, searchDocuments("tit*", document1Token));
        Assertions.assertEquals(2, searchDocuments("docu*", document1Token));
        Assertions.assertEquals(2, searchDocuments("full:title", document1Token));
        Assertions.assertEquals(2, searchDocuments("title", document1Token));
        Assertions.assertEquals(1, searchDocuments("super description", document1Token));
        Assertions.assertEquals(1, searchDocuments("subject", document1Token));
        Assertions.assertEquals(1, searchDocuments("identifier", document1Token));
        Assertions.assertEquals(1, searchDocuments("publisher", document1Token));
        Assertions.assertEquals(1, searchDocuments("format", document1Token));
        Assertions.assertEquals(1, searchDocuments("source", document1Token));
        Assertions.assertEquals(1, searchDocuments("software", document1Token));
        Assertions.assertEquals(1, searchDocuments("greenland", document1Token));
        Assertions.assertEquals(1, searchDocuments("public domain", document1Token));
        Assertions.assertEquals(0, searchDocuments("by:document3", document1Token));
        Assertions.assertEquals(2, searchDocuments("by:document1", document1Token));
        Assertions.assertEquals(0, searchDocuments("by:nobody", document1Token));
        ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
        Assertions.assertEquals(2, searchDocuments("at:" + now.format(DateTimeFormatter.ofPattern("yyyy")), document1Token));
        Assertions.assertEquals(2, searchDocuments("at:" + now.format(DateTimeFormatter.ofPattern("yyyy-MM")), document1Token));
        Assertions.assertEquals(2, searchDocuments("at:" + now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")), document1Token));
        Assertions.assertEquals(2, searchDocuments("after:2010 before:2040-08", document1Token));
        Assertions.assertEquals(2, searchDocuments("uat:" + now.format(DateTimeFormatter.ofPattern("yyyy")), document1Token));
        Assertions.assertEquals(2, searchDocuments("uat:" + now.format(DateTimeFormatter.ofPattern("yyyy-MM")), document1Token));
        Assertions.assertEquals(2, searchDocuments("uat:" + now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")), document1Token));
        Assertions.assertEquals(2, searchDocuments("uafter:2010 ubefore:2040-08", document1Token));
        Assertions.assertEquals(1, searchDocuments("tag:super", document1Token));
        Assertions.assertEquals(1, searchDocuments("!tag:super", document1Token));
        Assertions.assertEquals(1, searchDocuments("tag:super tag:hr", document1Token));
        Assertions.assertEquals(0, searchDocuments("tag:super !tag:hr", document1Token));
        Assertions.assertEquals(1, searchDocuments("shared:yes", document1Token));
        Assertions.assertEquals(2, searchDocuments("lang:eng", document1Token));
        Assertions.assertEquals(1, searchDocuments("mime:image/png", document1Token));
        Assertions.assertEquals(0, searchDocuments("mime:empty/void", document1Token));
        Assertions.assertEquals(1, searchDocuments("after:2010 before:2040-08 tag:super shared:yes lang:eng simple:title simple:description full:uranium", document1Token));
        Assertions.assertEquals(1, searchDocuments("title:My_super_title_document_3", document3Token));
        Assertions.assertEquals(2, searchDocuments("title:My_super_title_document_3 title:My_super_title_document_4", document3Token));

        // Search documents (nothing)
        Assertions.assertEquals(0, searchDocuments("random", document1Token));
        Assertions.assertEquals(0, searchDocuments("full:random", document1Token));
        Assertions.assertEquals(0, searchDocuments("after:2010 before:2011-05-20", document1Token));
        Assertions.assertEquals(0, searchDocuments("at:2040-05-35", document1Token));
        Assertions.assertEquals(0, searchDocuments("after:2010-18 before:2040-05-38", document1Token));
        Assertions.assertEquals(0, searchDocuments("after:2010-18", document1Token));
        Assertions.assertEquals(0, searchDocuments("before:2040-05-38", document1Token));
        Assertions.assertEquals(0, searchDocuments("tag:Nop", document1Token));
        Assertions.assertEquals(0, searchDocuments("lang:fra", document1Token));
        Assertions.assertEquals(0, searchDocuments("title:Unknown title", document3Token));

        // Get document 1
        json = target().path("/document/" + document1Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, document1Token)
                .get(JsonObject.class);
        Assertions.assertEquals(document1Id, json.getString("id"));
        Assertions.assertEquals("document1", json.getString("creator"));
        Assertions.assertEquals(1, json.getInt("file_count"));
        Assertions.assertTrue(json.getBoolean("shared"));
        Assertions.assertEquals("My super title document 1", json.getString("title"));
        Assertions.assertEquals("My super description for document 1", json.getString("description"));
        Assertions.assertEquals("Subject document 1", json.getString("subject"));
        Assertions.assertEquals("Identifier document 1", json.getString("identifier"));
        Assertions.assertEquals("Publisher document 1", json.getString("publisher"));
        Assertions.assertEquals("Format document 1", json.getString("format"));
        Assertions.assertEquals("Source document 1", json.getString("source"));
        Assertions.assertEquals("Software", json.getString("type"));
        Assertions.assertEquals("Greenland", json.getString("coverage"));
        Assertions.assertEquals("Public Domain", json.getString("rights"));
        Assertions.assertEquals("eng", json.getString("language"));
        Assertions.assertEquals(create1Date, json.getJsonNumber("create_date").longValue());
        Assertions.assertNotNull(json.get("update_date"));
        tags = json.getJsonArray("tags");
        Assertions.assertEquals(2, tags.size());
        Assertions.assertEquals(tag2Id, tags.getJsonObject(0).getString("id"));
        Assertions.assertEquals(tag1Id, tags.getJsonObject(1).getString("id"));
        JsonArray contributors = json.getJsonArray("contributors");
        Assertions.assertEquals(1, contributors.size());
        Assertions.assertEquals("document1", contributors.getJsonObject(0).getString("username"));
        JsonArray relations = json.getJsonArray("relations");
        Assertions.assertEquals(1, relations.size());
        Assertions.assertEquals(document2Id, relations.getJsonObject(0).getString("id"));
        Assertions.assertFalse(relations.getJsonObject(0).getBoolean("source"));
        Assertions.assertEquals("My super title document 2", relations.getJsonObject(0).getString("title"));
        Assertions.assertFalse(json.containsKey("files"));
        Assertions.assertEquals(file1Id, json.getString("file_id"));

        // Get document 2
        json = target().path("/document/" + document2Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, document1Token)
                .get(JsonObject.class);
        Assertions.assertEquals(document2Id, json.getString("id"));
        relations = json.getJsonArray("relations");
        Assertions.assertEquals(1, relations.size());
        Assertions.assertEquals(document1Id, relations.getJsonObject(0).getString("id"));
        Assertions.assertTrue(relations.getJsonObject(0).getBoolean("source"));
        Assertions.assertEquals("My super title document 1", relations.getJsonObject(0).getString("title"));
        Assertions.assertFalse(json.containsKey("files"));
        Assertions.assertTrue(json.isNull("file_id"));

        // Create a tag
        json = target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, document1Token)
                .put(Entity.form(new Form().param("name", "SuperTag2").param("color", "#00ffff")), JsonObject.class);
        String tag3Id = json.getString("id");
        Assertions.assertNotNull(tag3Id);
        
        // Update document 1
        json = target().path("/document/" + document1Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, document1Token)
                .post(Entity.form(new Form()
                        .param("title", "My new super document 1")
                        .param("description", "My new super description for document\r\n\u00A0\u0009 1")
                        .param("subject", "My new subject for document 1")
                        .param("identifier", "My new identifier for document 1")
                        .param("publisher", "My new publisher for document 1")
                        .param("format", "My new format for document 1")
                        .param("source", "My new source for document 1")
                        .param("type", "Image")
                        .param("coverage", "France")
                        .param("language", "eng")
                        .param("rights", "All Rights Reserved")
                        .param("tags", tag3Id)), JsonObject.class);
        Assertions.assertEquals(document1Id, json.getString("id"));
        
        // Update document 2
        json = target().path("/document/" + document2Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, document1Token)
                .post(Entity.form(new Form()
                        .param("title", "My super title document 2")
                        .param("language", "eng")), JsonObject.class);
        Assertions.assertEquals(document2Id, json.getString("id"));

        // Export a document in PDF format
        Response response = target().path("/document/" + document1Id + "/pdf")
                .queryParam("margin", "10")
                .queryParam("metadata", "true")
                .queryParam("comments", "true")
                .queryParam("fitimagetopage", "true")
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, document1Token)
                .get();
        InputStream is = (InputStream) response.getEntity();
        byte[] pdfBytes = ByteStreams.toByteArray(is);
        Assertions.assertTrue(pdfBytes.length > 0);

        // Search documents by query
        json = target().path("/document/list")
                .queryParam("search", "new")
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, document1Token)
                .get(JsonObject.class);
        documents = json.getJsonArray("documents");
        Assertions.assertEquals(1, documents.size());
        Assertions.assertEquals(document1Id, documents.getJsonObject(0).getString("id"));
        Assertions.assertFalse(documents.getJsonObject(0).containsKey("files"));

        // Search documents by query with files
        json = target().path("/document/list")
                .queryParam("files", true)
                .queryParam("search", "new")
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, document1Token)
                .get(JsonObject.class);
        documents = json.getJsonArray("documents");
        Assertions.assertEquals(1, documents.size());
        Assertions.assertEquals(1, documents.size());
        Assertions.assertEquals(document1Id, documents.getJsonObject(0).getString("id"));
        JsonArray files = documents.getJsonObject(0).getJsonArray("files");
        Assertions.assertEquals(1, files.size());
        Assertions.assertEquals(file1Id, files.getJsonObject(0).getString("id"));
        Assertions.assertEquals("Einstein-Roosevelt-letter.png", files.getJsonObject(0).getString("name"));
        Assertions.assertEquals("image/png", files.getJsonObject(0).getString("mimetype"));

        // Get document 1
        json = target().path("/document/" + document1Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, document1Token)
                .get(JsonObject.class);
        Assertions.assertTrue(json.getString("title").contains("new"));
        Assertions.assertTrue(json.getString("description").contains("new"));
        Assertions.assertTrue(json.getString("subject").contains("new"));
        Assertions.assertTrue(json.getString("identifier").contains("new"));
        Assertions.assertTrue(json.getString("publisher").contains("new"));
        Assertions.assertTrue(json.getString("format").contains("new"));
        Assertions.assertTrue(json.getString("source").contains("new"));
        Assertions.assertEquals("Image", json.getString("type"));
        Assertions.assertEquals("France", json.getString("coverage"));
        Assertions.assertEquals("All Rights Reserved", json.getString("rights"));
        tags = json.getJsonArray("tags");
        Assertions.assertEquals(1, tags.size());
        Assertions.assertEquals(tag3Id, tags.getJsonObject(0).getString("id"));
        contributors = json.getJsonArray("contributors");
        Assertions.assertEquals(1, contributors.size());
        Assertions.assertEquals("document1", contributors.getJsonObject(0).getString("username"));
        relations = json.getJsonArray("relations");
        Assertions.assertEquals(1, relations.size());
        Assertions.assertFalse(json.containsKey("files"));

        // Get document 1 with its files
        json = target().path("/document/" + document1Id)
                .queryParam("files", true)
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, document1Token)
                .get(JsonObject.class);
        files = json.getJsonArray("files");
        Assertions.assertEquals(1, files.size());
        Assertions.assertEquals(file1Id, files.getJsonObject(0).getString("id"));
        Assertions.assertEquals("Einstein-Roosevelt-letter.png", files.getJsonObject(0).getString("name"));
        Assertions.assertEquals("image/png", files.getJsonObject(0).getString("mimetype"));

        // Get document 1 again (relations preserved by partial update)
        json = target().path("/document/" + document1Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, document1Token)
                .get(JsonObject.class);
        relations = json.getJsonArray("relations");
        Assertions.assertEquals(1, relations.size());
        
        // Trashes a document (soft-delete)
        json = target().path("/document/" + document1Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, document1Token)
                .delete(JsonObject.class);
        Assertions.assertEquals("ok", json.getString("status"));

        // Trashes a non-existing document
        response = target().path("/document/69b79238-84bb-4263-a32f-9cbdf8c92188").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, document1Token)
                .delete();
        Assertions.assertEquals(Status.NOT_FOUND, Status.fromStatusCode(response.getStatus()));

        // Files should still exist on disk after trash (soft-delete)
        java.io.File storedFile = DirectoryUtil.getStorageDirectory().resolve(file1Id).toFile();
        java.io.File webFile = DirectoryUtil.getStorageDirectory().resolve(file1Id + "_web").toFile();
        java.io.File thumbnailFile = DirectoryUtil.getStorageDirectory().resolve(file1Id + "_thumb").toFile();
        Assertions.assertTrue(storedFile.exists());

        // Get a trashed document (KO - not visible in normal queries)
        response = target().path("/document/" + document1Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, document1Token)
                .get();
        Assertions.assertEquals(Status.NOT_FOUND, Status.fromStatusCode(response.getStatus()));

        // Permanently delete the trashed document
        json = target().path("/document/" + document1Id + "/permanent").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, document1Token)
                .delete(JsonObject.class);
        Assertions.assertEquals("ok", json.getString("status"));

        // Now files should be deleted from FS
        Assertions.assertFalse(storedFile.exists());
        Assertions.assertFalse(webFile.exists());
        Assertions.assertFalse(thumbnailFile.exists());
    }
    
    /**
     * Search documents and returns the number found.
     * 
     * @param query Search query
     * @param token Authentication token
     * @return Number of documents found
     */
    private int searchDocuments(String query, String token) {
        JsonObject json = target().path("/document/list")
                .queryParam("search", query)
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class);
        return json.getJsonArray("documents").size();
    }
    
    /**
     * Test ODT extraction.
     * 
     * @throws Exception e
     */
    @Test
    public void testOdtExtraction() throws Exception {
        // Login document_odt
        clientUtil.createUser("document_odt");
        String documentOdtToken = clientUtil.login("document_odt");

        // Create a document
        String document1Id = clientUtil.createDocument(documentOdtToken);
        
        // Add a PDF file
        String file1Id = clientUtil.addFileToDocument(FILE_DOCUMENT_ODT, documentOdtToken, document1Id);

        // Search documents by query in full content
        JsonObject json = target().path("/document/list")
                .queryParam("search", "full:ipsum")
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, documentOdtToken)
                .get(JsonObject.class);
        Assertions.assertEquals(1, json.getJsonArray("documents").size());
        
        // Get the file thumbnail data
        Response response = target().path("/file/" + file1Id + "/data")
                .queryParam("size", "thumb")
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, documentOdtToken)
                .get();
        InputStream is = (InputStream) response.getEntity();
        byte[] fileBytes = ByteStreams.toByteArray(is);
        Assertions.assertTrue(fileBytes.length > 0); // Images rendered from PDF differ in size from OS to OS due to font issues

        // Export a document in PDF format
        response = target().path("/document/" + document1Id + "/pdf")
                .queryParam("margin", "10")
                .queryParam("metadata", "true")
                .queryParam("comments", "true")
                .queryParam("fitimagetopage", "true")
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, documentOdtToken)
                .get();
        Assertions.assertEquals(Status.OK, Status.fromStatusCode(response.getStatus()));
        is = (InputStream) response.getEntity();
        byte[] pdfBytes = ByteStreams.toByteArray(is);
        Assertions.assertTrue(pdfBytes.length > 0);
    }
    
    /**
     * Test DOCX extraction.
     * 
     * @throws Exception e
     */
    @Test
    public void testDocxExtraction() throws Exception {
        // Login document_docx
        clientUtil.createUser("document_docx");
        String documentDocxToken = clientUtil.login("document_docx");

        // Create a document
        String document1Id = clientUtil.createDocument(documentDocxToken);
        
        // Add a PDF file
        String file1Id = clientUtil.addFileToDocument(FILE_DOCUMENT_DOCX, documentDocxToken, document1Id);

        // Search documents by query in full content
        JsonObject json = target().path("/document/list")
                .queryParam("search", "full:dolor")
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, documentDocxToken)
                .get(JsonObject.class);
        Assertions.assertEquals(1, json.getJsonArray("documents").size());
        
        // Get the file thumbnail data
        Response response = target().path("/file/" + file1Id + "/data")
                .queryParam("size", "thumb")
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, documentDocxToken)
                .get();
        InputStream is = (InputStream) response.getEntity();
        byte[] fileBytes = ByteStreams.toByteArray(is);
        Assertions.assertTrue(fileBytes.length > 0); // Images rendered from PDF differ in size from OS to OS due to font issues

        // Export a document in PDF format
        response = target().path("/document/" + document1Id + "/pdf")
                .queryParam("margin", "10")
                .queryParam("metadata", "true")
                .queryParam("comments", "true")
                .queryParam("fitimagetopage", "true")
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, documentDocxToken)
                .get();
        Assertions.assertEquals(Status.OK, Status.fromStatusCode(response.getStatus()));
        is = (InputStream) response.getEntity();
        byte[] pdfBytes = ByteStreams.toByteArray(is);
        Assertions.assertTrue(pdfBytes.length > 0);
    }
    
    /**
     * Test PDF extraction.
     * 
     * @throws Exception e
     */
    @Test
    public void testPdfExtraction() throws Exception {
        // Login document_pdf
        clientUtil.createUser("document_pdf");
        String documentPdfToken = clientUtil.login("document_pdf");

        // Create a document
        String document1Id = clientUtil.createDocument(documentPdfToken);
        
        // Add a PDF file
        String file1Id = clientUtil.addFileToDocument(FILE_WIKIPEDIA_PDF, documentPdfToken, document1Id);

        // Content extraction and indexing run on a worker after the upload response returns, so wait
        // for processing to drain and poll the search until the extracted content is indexed rather
        // than asserting once and racing the async work. Match THIS document's id in the results, not a
        // bare count == 1: a shared fixture (or a straggler from another test) could satisfy a count of
        // one while THIS document's PDF content was never extracted/indexed, passing the test vacuously.
        awaitProcessingQuiescence();
        awaitCondition("PDF full-content search did not return document " + document1Id + " (full:vrandecic)", () -> {
            JsonArray documents = target().path("/document/list")
                    .queryParam("search", "full:vrandecic")
                    .request()
                    .cookie(TokenBasedSecurityFilter.COOKIE_NAME, documentPdfToken)
                    .get(JsonObject.class)
                    .getJsonArray("documents");
            for (int i = 0; i < documents.size(); i++) {
                if (document1Id.equals(documents.getJsonObject(i).getString("id"))) {
                    return true;
                }
            }
            return false;
        });

        // Get the file thumbnail data
        Response response = target().path("/file/" + file1Id + "/data")
                .queryParam("size", "thumb")
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, documentPdfToken)
                .get();
        InputStream is = (InputStream) response.getEntity();
        byte[] fileBytes = ByteStreams.toByteArray(is);
        Assertions.assertTrue(fileBytes.length > 0); // Images rendered from PDF differ in size from OS to OS due to font issues

        // Export a document in PDF format
        response = target().path("/document/" + document1Id + "/pdf")
                .queryParam("margin", "10")
                .queryParam("metadata", "true")
                .queryParam("comments", "true")
                .queryParam("fitimagetopage", "true")
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, documentPdfToken)
                .get();
        Assertions.assertEquals(Status.OK, Status.fromStatusCode(response.getStatus()));
        is = (InputStream) response.getEntity();
        byte[] pdfBytes = ByteStreams.toByteArray(is);
        Assertions.assertTrue(pdfBytes.length > 0);
    }

    /**
     * Test plain text extraction.
     *
     * @throws Exception e
     */
    @Test
    public void testPlainTextExtraction() throws Exception {
        // Login document_plain
        clientUtil.createUser("document_plain");
        String documentPlainToken = clientUtil.login("document_plain");

        // Create a document
        String document1Id = clientUtil.createDocument(documentPlainToken);

        // Add a plain text file
        String file1Id = clientUtil.addFileToDocument(FILE_DOCUMENT_TXT, documentPlainToken, document1Id);

        // Search documents by query in full content
        JsonObject json = target().path("/document/list")
                .queryParam("search", "full:love")
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, documentPlainToken)
                .get(JsonObject.class);
        Assertions.assertEquals(1, json.getJsonArray("documents").size());

        // Get the file thumbnail data
        Response response = target().path("/file/" + file1Id + "/data")
                .queryParam("size", "thumb")
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, documentPlainToken)
                .get();
        InputStream is = (InputStream) response.getEntity();
        byte[] fileBytes = ByteStreams.toByteArray(is);
        Assertions.assertTrue(fileBytes.length > 0); // Images rendered from PDF differ in size from OS to OS due to font issues

        // Get the content data
        response = target().path("/file/" + file1Id + "/data")
                .queryParam("size", "content")
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, documentPlainToken)
                .get();
        Assertions.assertEquals(Status.OK, Status.fromStatusCode(response.getStatus()));
        is = (InputStream) response.getEntity();
        Assertions.assertTrue(new String(ByteStreams.toByteArray(is)).contains("love"));

        // Export a document in PDF format
        response = target().path("/document/" + document1Id + "/pdf")
                .queryParam("margin", "10")
                .queryParam("metadata", "true")
                .queryParam("comments", "true")
                .queryParam("fitimagetopage", "true")
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, documentPlainToken)
                .get();
        Assertions.assertEquals(Status.OK, Status.fromStatusCode(response.getStatus()));
        is = (InputStream) response.getEntity();
        byte[] pdfBytes = ByteStreams.toByteArray(is);
        Assertions.assertTrue(pdfBytes.length > 0);
    }

    /**
     * Test video extraction.
     *
     * @throws Exception e
     */
    @Test
    public void testVideoExtraction() throws Exception {
        // Login document_video
        clientUtil.createUser("document_video");
        String documentVideoToken = clientUtil.login("document_video");

        // Create a document
        String document1Id = clientUtil.createDocument(documentVideoToken);

        // Add a video file
        String file1Id = clientUtil.addFileToDocument(FILE_VIDEO_WEBM, documentVideoToken, document1Id);

        // Search documents by query in full content
        JsonObject json = target().path("/document/list")
                .queryParam("search", "full:vp9")
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, documentVideoToken)
                .get(JsonObject.class);
        Assertions.assertEquals(1, json.getJsonArray("documents").size());

        // Get the file thumbnail data
        Response response = target().path("/file/" + file1Id + "/data")
                .queryParam("size", "thumb")
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, documentVideoToken)
                .get();
        InputStream is = (InputStream) response.getEntity();
        byte[] fileBytes = ByteStreams.toByteArray(is);
        Assertions.assertTrue(fileBytes.length > 0); // Images rendered from PDF differ in size from OS to OS due to font issues

        // Export a document in PDF format
        response = target().path("/document/" + document1Id + "/pdf")
                .queryParam("margin", "10")
                .queryParam("metadata", "true")
                .queryParam("comments", "true")
                .queryParam("fitimagetopage", "true")
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, documentVideoToken)
                .get();
        Assertions.assertEquals(Status.OK, Status.fromStatusCode(response.getStatus()));
        is = (InputStream) response.getEntity();
        byte[] pdfBytes = ByteStreams.toByteArray(is);
        Assertions.assertTrue(pdfBytes.length > 0);
    }

    /**
     * Test PPTX extraction.
     *
     * @throws Exception e
     */
    @Test
    public void testPptxExtraction() throws Exception {
        // Login document_pptx
        clientUtil.createUser("document_pptx", 10000000); // 10MB quota
        String documentPptxToken = clientUtil.login("document_pptx");

        // Create a document
        String document1Id = clientUtil.createDocument(documentPptxToken);

        // Add a PPTX file
        String file1Id = clientUtil.addFileToDocument(FILE_APACHE_PPTX, documentPptxToken, document1Id);

        // Search documents by query in full content
        JsonObject json = target().path("/document/list")
                .queryParam("search", "full:scaling")
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, documentPptxToken)
                .get(JsonObject.class);
        Assertions.assertEquals(1, json.getJsonArray("documents").size());

        // Get the file thumbnail data
        Response response = target().path("/file/" + file1Id + "/data")
                .queryParam("size", "thumb")
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, documentPptxToken)
                .get();
        InputStream is = (InputStream) response.getEntity();
        byte[] fileBytes = ByteStreams.toByteArray(is);
        Assertions.assertTrue(fileBytes.length > 0); // Images rendered from PDF differ in size from OS to OS due to font issues

        // Export a document in PDF format
        response = target().path("/document/" + document1Id + "/pdf")
                .queryParam("margin", "10")
                .queryParam("metadata", "true")
                .queryParam("comments", "true")
                .queryParam("fitimagetopage", "true")
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, documentPptxToken)
                .get();
        Assertions.assertEquals(Status.OK, Status.fromStatusCode(response.getStatus()));
        is = (InputStream) response.getEntity();
        byte[] pdfBytes = ByteStreams.toByteArray(is);
        Assertions.assertTrue(pdfBytes.length > 0);
    }

    /**
     * Test EML import.
     *
     * @throws Exception e
     */
    @Test
    public void testEmlImport() throws Exception {
        // Login document_eml
        clientUtil.createUser("document_eml");
        String documentEmlToken = clientUtil.login("document_eml");

        // Import a document as EML
        JsonObject json;
        try (InputStream is = Resources.getResource("file/test_mail.eml").openStream()) {
            StreamDataBodyPart streamDataBodyPart = new StreamDataBodyPart("file", is, "test_mail.eml");
            try (FormDataMultiPart multiPart = new FormDataMultiPart()) {
                json = target()
                        .register(MultiPartFeature.class)
                        .path("/document/eml").request()
                        .cookie(TokenBasedSecurityFilter.COOKIE_NAME, documentEmlToken)
                        .put(Entity.entity(multiPart.bodyPart(streamDataBodyPart),
                                MediaType.MULTIPART_FORM_DATA_TYPE), JsonObject.class);
            }
        }

        String documentId = json.getString("id");
        Assertions.assertNotNull(documentId);

        // Get the document
        json = target().path("/document/" + documentId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, documentEmlToken)
                .get(JsonObject.class);
        Assertions.assertEquals("subject here", json.getString("title"));
        Assertions.assertTrue(json.getString("description").contains("content here"));
        Assertions.assertEquals("subject here", json.getString("subject"));
        Assertions.assertEquals("EML", json.getString("format"));
        Assertions.assertEquals("Email", json.getString("source"));
        Assertions.assertEquals("eng", json.getString("language"));
        Assertions.assertEquals(1519222261000L, json.getJsonNumber("create_date").longValue());

        // Get all files from a document
        json = target().path("/file/list")
                .queryParam("id", documentId)
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, documentEmlToken)
                .get(JsonObject.class);
        JsonArray files = json.getJsonArray("files");
        Assertions.assertEquals(2, files.size());
        Assertions.assertEquals("14_UNHCR_nd.pdf", files.getJsonObject(0).getString("name"));
        Assertions.assertEquals(251216L, files.getJsonObject(0).getJsonNumber("size").longValue());
        Assertions.assertEquals("application/pdf", files.getJsonObject(0).getString("mimetype"));
        Assertions.assertEquals("refugee status determination.pdf", files.getJsonObject(1).getString("name"));
        Assertions.assertEquals(279276L, files.getJsonObject(1).getJsonNumber("size").longValue());
        Assertions.assertEquals("application/pdf", files.getJsonObject(1).getString("mimetype"));
    }

    /**
     * SEC A3-04: when an EML import fails while adding attachments (quota too small for
     * the first attachment), the plaintext attachment temp files extracted by EmailUtil
     * must be deleted. With the quota below the smallest attachment, no attachment hands
     * off to an async event, so the temp-file count is race-free.
     *
     * @throws Exception e
     */
    @Test
    public void testEmlImportFailureDeletesAttachmentTemps() throws Exception {
        // 100KB quota: smaller than either EML attachment (251KB, 279KB)
        clientUtil.createUser("document_eml_leak", 100000);
        String token = clientUtil.login("document_eml_leak");

        // Capture EXACTLY the temp files this import creates through the centralized FileService seam,
        // rather than diffing a shared tmpdir. This closes the leak window the content-list scan missed:
        // EmailUtil creates each attachment temp BEFORE recording it in the mail content list, so a temp
        // orphaned in that gap is invisible to a content-list-based check but is seen here.
        java.util.List<Path> created = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        int status;
        String errorType;
        com.sismics.docs.core.service.FileService.setTemporaryFileListener(created::add);
        try (InputStream is = Resources.getResource("file/test_mail.eml").openStream()) {
            StreamDataBodyPart streamDataBodyPart = new StreamDataBodyPart("file", is, "test_mail.eml");
            try (FormDataMultiPart multiPart = new FormDataMultiPart()) {
                Response response = target()
                        .register(MultiPartFeature.class)
                        .path("/document/eml").request()
                        .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                        .put(Entity.entity(multiPart.bodyPart(streamDataBodyPart),
                                MediaType.MULTIPART_FORM_DATA_TYPE));
                status = response.getStatus();
                errorType = response.readEntity(JsonObject.class).getString("type", null);
            }
        } finally {
            com.sismics.docs.core.service.FileService.setTemporaryFileListener(null);
        }

        // The import fails with the SPECIFIC per-user quota breach (HTTP 400, type QuotaReached), not
        // merely "not 200" — asserting the exact failure pins that the temps are cleaned on THIS path.
        Assertions.assertEquals(Status.BAD_REQUEST.getStatusCode(), status, "EML import must fail on the quota breach");
        Assertions.assertEquals("QuotaReached", errorType, "the failure must be the quota breach");

        // It must have created exactly the EML temp + one temp per attachment (the EML has two). Asserting
        // this cardinality means a disconnected seam that captured nothing cannot pass the "all gone"
        // check vacuously.
        Assertions.assertEquals(3, created.size(),
                "the failed EML import must create exactly the EML temp + 2 attachment temps: " + created);

        // Every temp it created must be gone.
        for (Path p : created) {
            Assertions.assertFalse(Files.exists(p), "EML import temp leaked on failed import: " + p);
        }
    }

    /**
     * SEC A3-04b: the ORIGINAL EML temp (created at the top of importEml, now inside the
     * cleanup scope) must be deleted on a failed import, not just the attachment temps.
     * With the quota below the first attachment, the import fails and NO temp file created
     * during the import may survive — proving the EML temp AND both attachment temps are
     * all removed. A regression that drops the EML-temp delete leaves a residual in the
     * snapshot delta.
     *
     * @throws Exception e
     */
    @Test
    public void testEmlImportFailureDeletesEmlTemp() throws Exception {
        // 100KB quota: smaller than either EML attachment (251KB, 279KB)
        clientUtil.createUser("document_eml_temp", 100000);
        String token = clientUtil.login("document_eml_temp");

        // Capture every temp created by the import (EML temp + attachment temps) via the FileService
        // seam, proving the ORIGINAL EML temp — created at the top of importEml — is deleted on failure
        // alongside the attachment temps, not left behind.
        java.util.List<Path> created = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        int status;
        String errorType;
        com.sismics.docs.core.service.FileService.setTemporaryFileListener(created::add);
        try (InputStream is = Resources.getResource("file/test_mail.eml").openStream()) {
            StreamDataBodyPart part = new StreamDataBodyPart("file", is, "test_mail.eml");
            try (FormDataMultiPart multiPart = new FormDataMultiPart()) {
                Response response = target().register(MultiPartFeature.class)
                        .path("/document/eml").request()
                        .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                        .put(Entity.entity(multiPart.bodyPart(part), MediaType.MULTIPART_FORM_DATA_TYPE));
                status = response.getStatus();
                errorType = response.readEntity(JsonObject.class).getString("type", null);
            }
        } finally {
            com.sismics.docs.core.service.FileService.setTemporaryFileListener(null);
        }

        // The import fails with the SPECIFIC per-user quota breach (HTTP 400, type QuotaReached).
        Assertions.assertEquals(Status.BAD_REQUEST.getStatusCode(), status, "Import must fail on the quota breach");
        Assertions.assertEquals("QuotaReached", errorType, "the failure must be the quota breach");

        // Exactly the EML temp + one per attachment were created (a disconnected seam capturing nothing
        // could not satisfy this, so the subsequent "all gone" check cannot pass vacuously).
        Assertions.assertEquals(3, created.size(),
                "the failed EML import must create exactly the EML temp + 2 attachment temps: " + created);

        // No temp the import created may survive: the EML temp AND both attachment temps are all deleted.
        for (Path p : created) {
            Assertions.assertFalse(Files.exists(p), "EML/attachment temp leaked on failed import: " + p);
        }
    }

    /**
     * Test custom metadata.
     */
    @Test
    public void testCustomMetadata() {
        // Login admin
        String adminToken = adminToken();

        // Login metadata1
        clientUtil.createUser("metadata1");
        String metadata1Token = clientUtil.login("metadata1");

        // Create some metadata with admin
        JsonObject json = target().path("/metadata").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("name", "0str")
                        .param("type", "STRING")), JsonObject.class);
        String metadataStrId = json.getString("id");
        json = target().path("/metadata").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("name", "1int")
                        .param("type", "INTEGER")), JsonObject.class);
        String metadataIntId = json.getString("id");
        json = target().path("/metadata").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("name", "2float")
                        .param("type", "FLOAT")), JsonObject.class);
        String metadataFloatId = json.getString("id");
        json = target().path("/metadata").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("name", "3date")
                        .param("type", "DATE")), JsonObject.class);
        String metadataDateId = json.getString("id");
        json = target().path("/metadata").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("name", "4bool")
                        .param("type", "BOOLEAN")), JsonObject.class);
        String metadataBoolId = json.getString("id");

        // Create a document with metadata1
        json = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, metadata1Token)
                .put(Entity.form(new Form()
                        .param("title", "Metadata 1")
                        .param("language", "eng")
                        .param("metadata_id", metadataStrId)
                        .param("metadata_id", metadataIntId)
                        .param("metadata_id", metadataFloatId)
                        .param("metadata_value", "my string")
                        .param("metadata_value", "50")
                        .param("metadata_value", "12.4")), JsonObject.class);
        String document1Id = json.getString("id");

        // Check the values
        json = target().path("/document/" + document1Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, metadata1Token)
                .get(JsonObject.class);
        JsonArray metadata = json.getJsonArray("metadata");
        Assertions.assertEquals(5, metadata.size());
        JsonObject meta = metadata.getJsonObject(0);
        Assertions.assertEquals(metadataStrId, meta.getString("id"));
        Assertions.assertEquals("0str", meta.getString("name"));
        Assertions.assertEquals("STRING", meta.getString("type"));
        Assertions.assertEquals("my string", meta.getString("value"));
        meta = metadata.getJsonObject(1);
        Assertions.assertEquals(metadataIntId, meta.getString("id"));
        Assertions.assertEquals("1int", meta.getString("name"));
        Assertions.assertEquals("INTEGER", meta.getString("type"));
        Assertions.assertEquals(50, meta.getInt("value"));
        meta = metadata.getJsonObject(2);
        Assertions.assertEquals(metadataFloatId, meta.getString("id"));
        Assertions.assertEquals("2float", meta.getString("name"));
        Assertions.assertEquals("FLOAT", meta.getString("type"));
        Assertions.assertEquals(12.4, meta.getJsonNumber("value").doubleValue(), 0);
        meta = metadata.getJsonObject(3);
        Assertions.assertEquals(metadataDateId, meta.getString("id"));
        Assertions.assertEquals("3date", meta.getString("name"));
        Assertions.assertEquals("DATE", meta.getString("type"));
        Assertions.assertFalse(meta.containsKey("value"));
        meta = metadata.getJsonObject(4);
        Assertions.assertEquals(metadataBoolId, meta.getString("id"));
        Assertions.assertEquals("4bool", meta.getString("name"));
        Assertions.assertEquals("BOOLEAN", meta.getString("type"));
        Assertions.assertFalse(meta.containsKey("value"));

        // Update the document with metadata1 (add more metadata)
        long dateValue = new Date().getTime();
        target().path("/document/" + document1Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, metadata1Token)
                .post(Entity.form(new Form()
                        .param("title", "Metadata 1")
                        .param("language", "eng")
                        .param("metadata_id", metadataStrId)
                        .param("metadata_id", metadataIntId)
                        .param("metadata_id", metadataFloatId)
                        .param("metadata_id", metadataDateId)
                        .param("metadata_id", metadataBoolId)
                        .param("metadata_value", "my string 2")
                        .param("metadata_value", "52")
                        .param("metadata_value", "14.4")
                        .param("metadata_value", Long.toString(dateValue))
                        .param("metadata_value", "true")), JsonObject.class);

        // Check the values
        json = target().path("/document/" + document1Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, metadata1Token)
                .get(JsonObject.class);
        metadata = json.getJsonArray("metadata");
        Assertions.assertEquals(5, metadata.size());
        meta = metadata.getJsonObject(0);
        Assertions.assertEquals(metadataStrId, meta.getString("id"));
        Assertions.assertEquals("0str", meta.getString("name"));
        Assertions.assertEquals("STRING", meta.getString("type"));
        Assertions.assertEquals("my string 2", meta.getString("value"));
        meta = metadata.getJsonObject(1);
        Assertions.assertEquals(metadataIntId, meta.getString("id"));
        Assertions.assertEquals("1int", meta.getString("name"));
        Assertions.assertEquals("INTEGER", meta.getString("type"));
        Assertions.assertEquals(52, meta.getInt("value"));
        meta = metadata.getJsonObject(2);
        Assertions.assertEquals(metadataFloatId, meta.getString("id"));
        Assertions.assertEquals("2float", meta.getString("name"));
        Assertions.assertEquals("FLOAT", meta.getString("type"));
        Assertions.assertEquals(14.4, meta.getJsonNumber("value").doubleValue(), 0);
        meta = metadata.getJsonObject(3);
        Assertions.assertEquals(metadataDateId, meta.getString("id"));
        Assertions.assertEquals("3date", meta.getString("name"));
        Assertions.assertEquals("DATE", meta.getString("type"));
        Assertions.assertEquals(dateValue, meta.getJsonNumber("value").longValue());
        meta = metadata.getJsonObject(4);
        Assertions.assertEquals(metadataBoolId, meta.getString("id"));
        Assertions.assertEquals("4bool", meta.getString("name"));
        Assertions.assertEquals("BOOLEAN", meta.getString("type"));
        Assertions.assertTrue(meta.getBoolean("value"));

        // Update the document with metadata1 (remove some metadata)
        target().path("/document/" + document1Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, metadata1Token)
                .post(Entity.form(new Form()
                        .param("title", "Metadata 1")
                        .param("language", "eng")
                        .param("metadata_id", metadataFloatId)
                        .param("metadata_id", metadataDateId)
                        .param("metadata_id", metadataBoolId)
                        .param("metadata_value", "14.4")
                        .param("metadata_value", Long.toString(dateValue))
                        .param("metadata_value", "true")), JsonObject.class);

        // Check the values
        json = target().path("/document/" + document1Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, metadata1Token)
                .get(JsonObject.class);
        metadata = json.getJsonArray("metadata");
        Assertions.assertEquals(5, metadata.size());
        meta = metadata.getJsonObject(0);
        Assertions.assertEquals(metadataStrId, meta.getString("id"));
        Assertions.assertEquals("0str", meta.getString("name"));
        Assertions.assertEquals("STRING", meta.getString("type"));
        Assertions.assertFalse(meta.containsKey("value"));
        meta = metadata.getJsonObject(1);
        Assertions.assertEquals(metadataIntId, meta.getString("id"));
        Assertions.assertEquals("1int", meta.getString("name"));
        Assertions.assertEquals("INTEGER", meta.getString("type"));
        Assertions.assertFalse(meta.containsKey("value"));
        meta = metadata.getJsonObject(2);
        Assertions.assertEquals(metadataFloatId, meta.getString("id"));
        Assertions.assertEquals("2float", meta.getString("name"));
        Assertions.assertEquals("FLOAT", meta.getString("type"));
        Assertions.assertEquals(14.4, meta.getJsonNumber("value").doubleValue(), 0);
        meta = metadata.getJsonObject(3);
        Assertions.assertEquals(metadataDateId, meta.getString("id"));
        Assertions.assertEquals("3date", meta.getString("name"));
        Assertions.assertEquals("DATE", meta.getString("type"));
        Assertions.assertEquals(dateValue, meta.getJsonNumber("value").longValue());
        meta = metadata.getJsonObject(4);
        Assertions.assertEquals(metadataBoolId, meta.getString("id"));
        Assertions.assertEquals("4bool", meta.getString("name"));
        Assertions.assertEquals("BOOLEAN", meta.getString("type"));
        Assertions.assertTrue(meta.getBoolean("value"));
    }

    @Test
    public void testUpdateDocumentPartialPreservesUntouchedFields() throws Exception {
        clientUtil.createUser("partial1");
        String token = clientUtil.login("partial1");

        // Create a tag
        JsonObject json = target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .put(Entity.form(new Form()
                        .param("name", "PartialTag")
                        .param("color", "#ff0000")), JsonObject.class);
        String tagId = json.getString("id");

        // Create a fully populated document
        long createTs = 1700000000000L;
        json = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .put(Entity.form(new Form()
                        .param("title", "Original Title")
                        .param("description", "Original Description")
                        .param("subject", "Original Subject")
                        .param("identifier", "ID-001")
                        .param("publisher", "Original Publisher")
                        .param("format", "PDF")
                        .param("source", "Scanner")
                        .param("type", "Invoice")
                        .param("coverage", "Germany")
                        .param("rights", "Private")
                        .param("language", "eng")
                        .param("create_date", String.valueOf(createTs))
                        .param("tags", tagId)), JsonObject.class);
        String docId = json.getString("id");

        // Create a second document for relation
        json = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .put(Entity.form(new Form()
                        .param("title", "Related Doc")
                        .param("language", "eng")
                        .param("relations", docId)), JsonObject.class);
        String relatedDocId = json.getString("id");

        // Verify the relation exists on the original doc
        json = target().path("/document/" + docId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class);
        Assertions.assertEquals(1, json.getJsonArray("relations").size());

        // Update with ONLY title and language (mimics quick-tag pattern)
        json = target().path("/document/" + docId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .post(Entity.form(new Form()
                        .param("title", "Updated Title")
                        .param("language", "eng")), JsonObject.class);
        Assertions.assertEquals(docId, json.getString("id"));

        // Verify all untouched fields are preserved
        json = target().path("/document/" + docId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class);
        Assertions.assertEquals("Updated Title", json.getString("title"));
        Assertions.assertEquals("Original Description", json.getString("description"));
        Assertions.assertEquals("Original Subject", json.getString("subject"));
        Assertions.assertEquals("ID-001", json.getString("identifier"));
        Assertions.assertEquals("Original Publisher", json.getString("publisher"));
        Assertions.assertEquals("PDF", json.getString("format"));
        Assertions.assertEquals("Scanner", json.getString("source"));
        Assertions.assertEquals("Invoice", json.getString("type"));
        Assertions.assertEquals("Germany", json.getString("coverage"));
        Assertions.assertEquals("Private", json.getString("rights"));
        Assertions.assertEquals(createTs, json.getJsonNumber("create_date").longValue());
        Assertions.assertEquals(1, json.getJsonArray("tags").size());
        Assertions.assertEquals(tagId, json.getJsonArray("tags").getJsonObject(0).getString("id"));
        Assertions.assertEquals(1, json.getJsonArray("relations").size());
    }

    @Test
    public void testUpdateDocumentExplicitClearStillClears() throws Exception {
        clientUtil.createUser("partial2");
        String token = clientUtil.login("partial2");

        // Create document with description
        JsonObject json = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .put(Entity.form(new Form()
                        .param("title", "Clear Test")
                        .param("description", "Has a description")
                        .param("language", "eng")), JsonObject.class);
        String docId = json.getString("id");

        // Update sending an empty description (explicit clear)
        json = target().path("/document/" + docId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .post(Entity.form(new Form()
                        .param("title", "Clear Test")
                        .param("description", "")
                        .param("language", "eng")), JsonObject.class);
        Assertions.assertEquals(docId, json.getString("id"));

        // Verify description was cleared
        json = target().path("/document/" + docId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class);
        Assertions.assertTrue(json.isNull("description") || json.getString("description").isEmpty());
    }

    @Test
    public void testUpdateDocumentTagsOnlyKeepsMetadata() throws Exception {
        clientUtil.createUser("partial3");
        String token = clientUtil.login("partial3");

        // Create tags
        JsonObject json = target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .put(Entity.form(new Form()
                        .param("name", "TagA")
                        .param("color", "#aaaaaa")), JsonObject.class);
        String tagAId = json.getString("id");

        json = target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .put(Entity.form(new Form()
                        .param("name", "TagB")
                        .param("color", "#bbbbbb")), JsonObject.class);
        String tagBId = json.getString("id");

        // Create doc with description, subject, and tagA
        json = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .put(Entity.form(new Form()
                        .param("title", "Tag Swap Test")
                        .param("description", "Keep this")
                        .param("subject", "Keep this too")
                        .param("language", "eng")
                        .param("tags", tagAId)), JsonObject.class);
        String docId = json.getString("id");

        // Quick-tag: send only title, language, tags (replacing tagA with tagB)
        json = target().path("/document/" + docId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .post(Entity.form(new Form()
                        .param("title", "Tag Swap Test")
                        .param("language", "eng")
                        .param("tags", tagBId)), JsonObject.class);
        Assertions.assertEquals(docId, json.getString("id"));

        // Verify tags changed but description/subject survived
        json = target().path("/document/" + docId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class);
        Assertions.assertEquals("Keep this", json.getString("description"));
        Assertions.assertEquals("Keep this too", json.getString("subject"));
        JsonArray tags = json.getJsonArray("tags");
        Assertions.assertEquals(1, tags.size());
        Assertions.assertEquals(tagBId, tags.getJsonObject(0).getString("id"));
    }

    /**
     * P8 contract: removing a document's LAST tag must persist. Sending zero {@code tags}
     * params looks identical to "omitted" (preserve), so the client sends {@code tags_reset=true}
     * to signal an explicit clear.
     */
    @Test
    public void testUpdateDocumentTagsResetClearsLastTag() {
        clientUtil.createUser("tagreset1");
        String token = clientUtil.login("tagreset1");

        JsonObject json = target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .put(Entity.form(new Form()
                        .param("name", "OnlyTag")
                        .param("color", "#cccccc")), JsonObject.class);
        String tagId = json.getString("id");

        json = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .put(Entity.form(new Form()
                        .param("title", "Reset Tag Doc")
                        .param("language", "eng")
                        .param("tags", tagId)), JsonObject.class);
        String docId = json.getString("id");

        // Sanity: the tag is present
        json = target().path("/document/" + docId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class);
        Assertions.assertEquals(1, json.getJsonArray("tags").size());

        // Explicit clear of the last tag: no tags param, tags_reset=true
        target().path("/document/" + docId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .post(Entity.form(new Form()
                        .param("title", "Reset Tag Doc")
                        .param("language", "eng")
                        .param("tags_reset", "true")), JsonObject.class);

        json = target().path("/document/" + docId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class);
        Assertions.assertEquals(0, json.getJsonArray("tags").size());
    }

    /**
     * P8 contract: omitting the {@code tags} param (no tags_reset) preserves the existing tag.
     * This guards the backward-compatible path for old clients.
     */
    @Test
    public void testUpdateDocumentTagsOmittedPreserves() {
        clientUtil.createUser("tagreset2");
        String token = clientUtil.login("tagreset2");

        JsonObject json = target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .put(Entity.form(new Form()
                        .param("name", "KeepTag")
                        .param("color", "#dddddd")), JsonObject.class);
        String tagId = json.getString("id");

        json = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .put(Entity.form(new Form()
                        .param("title", "Keep Tag Doc")
                        .param("language", "eng")
                        .param("tags", tagId)), JsonObject.class);
        String docId = json.getString("id");

        // Update title only: tag must be preserved
        target().path("/document/" + docId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .post(Entity.form(new Form()
                        .param("title", "Keep Tag Doc 2")
                        .param("language", "eng")), JsonObject.class);

        json = target().path("/document/" + docId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class);
        Assertions.assertEquals(1, json.getJsonArray("tags").size());
        Assertions.assertEquals(tagId, json.getJsonArray("tags").getJsonObject(0).getString("id"));
    }

    /**
     * P8 contract: clearing the LAST set custom-metadata value must persist. Sending zero
     * {@code metadata_id} params looks like "omitted" (preserve), so the client sends
     * {@code metadata_reset=true} to signal an explicit clear of all values.
     */
    @Test
    public void testUpdateDocumentMetadataResetClearsLastValue() {
        String adminToken = adminToken();
        clientUtil.createUser("metareset1");
        String token = clientUtil.login("metareset1");

        JsonObject json = target().path("/metadata").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("name", "ResetStr")
                        .param("type", "STRING")), JsonObject.class);
        String metaId = json.getString("id");

        json = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .put(Entity.form(new Form()
                        .param("title", "Meta Reset Doc")
                        .param("language", "eng")
                        .param("metadata_id", metaId)
                        .param("metadata_value", "keep me")), JsonObject.class);
        String docId = json.getString("id");

        // Sanity: value is set
        json = target().path("/document/" + docId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class);
        JsonArray metadata = json.getJsonArray("metadata");
        JsonObject meta = findMetadata(metadata, metaId);
        Assertions.assertEquals("keep me", meta.getString("value"));

        // Explicit clear: no metadata_id params, metadata_reset=true
        target().path("/document/" + docId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .post(Entity.form(new Form()
                        .param("title", "Meta Reset Doc")
                        .param("language", "eng")
                        .param("metadata_reset", "true")), JsonObject.class);

        json = target().path("/document/" + docId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class);
        meta = findMetadata(json.getJsonArray("metadata"), metaId);
        Assertions.assertFalse(meta.containsKey("value"), "metadata value should be cleared");

        // Backward-compat: omitting metadata_id and the sentinel preserves values.
        // Re-set a value, then update title only.
        target().path("/document/" + docId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .post(Entity.form(new Form()
                        .param("title", "Meta Reset Doc")
                        .param("language", "eng")
                        .param("metadata_id", metaId)
                        .param("metadata_value", "again")), JsonObject.class);
        target().path("/document/" + docId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .post(Entity.form(new Form()
                        .param("title", "Meta Reset Doc 2")
                        .param("language", "eng")), JsonObject.class);
        json = target().path("/document/" + docId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class);
        meta = findMetadata(json.getJsonArray("metadata"), metaId);
        Assertions.assertEquals("again", meta.getString("value"), "omitted metadata must be preserved");
    }

    /**
     * Relations reset contract: {@code relations_reset=true} with no relations supplied clears
     * ALL OUTGOING relations (including the last one) while PRESERVING incoming relations
     * (which are owned by the source document). Omitting the sentinel preserves outgoing
     * relations for backward compatibility.
     */
    @Test
    public void testUpdateDocumentRelationsReset() {
        clientUtil.createUser("relreset1");
        String token = clientUtil.login("relreset1");

        // Hub document
        JsonObject json = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .put(Entity.form(new Form()
                        .param("title", "Relations Hub")
                        .param("language", "eng")), JsonObject.class);
        String hubId = json.getString("id");

        // Outgoing target: hub -> out
        json = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .put(Entity.form(new Form()
                        .param("title", "Relations Out")
                        .param("language", "eng")), JsonObject.class);
        String outId = json.getString("id");

        // Add the outgoing relation from the hub
        target().path("/document/" + hubId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .post(Entity.form(new Form()
                        .param("title", "Relations Hub")
                        .param("language", "eng")
                        .param("relations", outId)), JsonObject.class);

        // Incoming source: in -> hub (relation owned by the source document "in")
        json = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .put(Entity.form(new Form()
                        .param("title", "Relations In")
                        .param("language", "eng")
                        .param("relations", hubId)), JsonObject.class);
        String inId = json.getString("id");

        // Sanity: the hub sees BOTH relations (one outgoing source=true, one incoming source=false)
        json = target().path("/document/" + hubId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class);
        JsonArray relations = json.getJsonArray("relations");
        Assertions.assertEquals(2, relations.size());
        JsonObject outRel = findRelation(relations, outId);
        JsonObject inRel = findRelation(relations, inId);
        Assertions.assertTrue(outRel.getBoolean("source"), "hub -> out is outgoing (source=true)");
        Assertions.assertFalse(inRel.getBoolean("source"), "in -> hub is incoming (source=false)");

        // Explicit clear of all outgoing relations: no relations param, relations_reset=true
        target().path("/document/" + hubId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .post(Entity.form(new Form()
                        .param("title", "Relations Hub")
                        .param("language", "eng")
                        .param("relations_reset", "true")), JsonObject.class);

        // The outgoing relation is gone; the incoming relation is PRESERVED
        json = target().path("/document/" + hubId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class);
        relations = json.getJsonArray("relations");
        Assertions.assertEquals(1, relations.size(), "reset clears every outgoing relation");
        Assertions.assertEquals(inId, relations.getJsonObject(0).getString("id"));
        Assertions.assertFalse(relations.getJsonObject(0).getBoolean("source"),
                "the surviving relation is the incoming one");

        // The source document "in" still owns its outgoing relation to the hub
        json = target().path("/document/" + inId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class);
        relations = json.getJsonArray("relations");
        Assertions.assertEquals(1, relations.size());
        Assertions.assertEquals(hubId, relations.getJsonObject(0).getString("id"));
        Assertions.assertTrue(relations.getJsonObject(0).getBoolean("source"));
    }

    /**
     * Relations reset contract: omitting the {@code relations} param (no relations_reset)
     * preserves the existing outgoing relation. Guards the backward-compatible path.
     */
    @Test
    public void testUpdateDocumentRelationsOmittedPreserves() {
        clientUtil.createUser("relreset2");
        String token = clientUtil.login("relreset2");

        JsonObject json = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .put(Entity.form(new Form()
                        .param("title", "Keep Relation Target")
                        .param("language", "eng")), JsonObject.class);
        String targetId = json.getString("id");

        json = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .put(Entity.form(new Form()
                        .param("title", "Keep Relation Source")
                        .param("language", "eng")
                        .param("relations", targetId)), JsonObject.class);
        String sourceId = json.getString("id");

        // Update title only: the outgoing relation must be preserved
        target().path("/document/" + sourceId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .post(Entity.form(new Form()
                        .param("title", "Keep Relation Source 2")
                        .param("language", "eng")), JsonObject.class);

        json = target().path("/document/" + sourceId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class);
        JsonArray relations = json.getJsonArray("relations");
        Assertions.assertEquals(1, relations.size(), "omitted relations must be preserved");
        Assertions.assertEquals(targetId, relations.getJsonObject(0).getString("id"));
        Assertions.assertTrue(relations.getJsonObject(0).getBoolean("source"));
    }

    private static JsonObject findRelation(JsonArray relations, String relatedId) {
        for (int i = 0; i < relations.size(); i++) {
            JsonObject r = relations.getJsonObject(i);
            if (relatedId.equals(r.getString("id"))) {
                return r;
            }
        }
        throw new AssertionError("relation not found: " + relatedId);
    }

    private static JsonObject findMetadata(JsonArray metadata, String metaId) {
        for (int i = 0; i < metadata.size(); i++) {
            JsonObject m = metadata.getJsonObject(i);
            if (metaId.equals(m.getString("id"))) {
                return m;
            }
        }
        throw new AssertionError("metadata not found: " + metaId);
    }

    /**
     * A VOCABULARY-typed metadata value on a document must be one of the referenced
     * vocabulary's entry values: an in-vocabulary value is accepted, an out-of-vocabulary
     * value is rejected.
     */
    @Test
    public void testVocabularyMetadataValue() {
        // Login admin, define a VOCABULARY metadata backed by the seeded 'type' vocabulary
        String adminToken = adminToken();
        JsonObject json = target().path("/metadata").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .put(Entity.form(new Form()
                        .param("name", "DublinType")
                        .param("type", "VOCABULARY")
                        .param("vocabulary", "type")), JsonObject.class);
        String metaId = json.getString("id");

        // Login a normal user
        clientUtil.createUser("vocvalue1");
        String token = clientUtil.login("vocvalue1");

        // In-vocabulary value ('Text' is a seeded 'type' entry) is accepted
        json = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .put(Entity.form(new Form()
                        .param("title", "Voc value ok")
                        .param("language", "eng")
                        .param("metadata_id", metaId)
                        .param("metadata_value", "Text")), JsonObject.class);
        String docId = json.getString("id");

        json = target().path("/document/" + docId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class);
        JsonObject meta = findMetadata(json.getJsonArray("metadata"), metaId);
        Assertions.assertEquals("VOCABULARY", meta.getString("type"));
        Assertions.assertEquals("Text", meta.getString("value"));

        // Out-of-vocabulary value is rejected on CREATE
        Response response = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .put(Entity.form(new Form()
                        .param("title", "Voc value bad")
                        .param("language", "eng")
                        .param("metadata_id", metaId)
                        .param("metadata_value", "NotAVocabularyValue")));
        Assertions.assertEquals(Status.BAD_REQUEST.getStatusCode(), response.getStatus());

        // The same membership invariant must hold on the UPDATE path: setting an
        // out-of-vocabulary value via document update is rejected.
        response = target().path("/document/" + docId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .post(Entity.form(new Form()
                        .param("title", "Voc value ok")
                        .param("language", "eng")
                        .param("metadata_id", metaId)
                        .param("metadata_value", "StillNotAVocabularyValue")));
        Assertions.assertEquals(Status.BAD_REQUEST.getStatusCode(), response.getStatus());

        // A valid in-vocabulary value IS accepted on update (changes 'Text' -> 'Image').
        target().path("/document/" + docId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .post(Entity.form(new Form()
                        .param("title", "Voc value ok")
                        .param("language", "eng")
                        .param("metadata_id", metaId)
                        .param("metadata_value", "Image")), JsonObject.class);
        json = target().path("/document/" + docId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class);
        meta = findMetadata(json.getJsonArray("metadata"), metaId);
        Assertions.assertEquals("Image", meta.getString("value"));

        // Clean up the metadata definition. addMetadata() returns EVERY defined field,
        // so a leaked definition would inflate the metadata-array size other tests in
        // this shared-database class assert on (e.g. testCustomMetadata).
        target().path("/metadata/" + metaId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete(JsonObject.class);
    }

    /**
     * SEC-01: emptying trash must be scoped to the caller's own documents.
     * A non-admin user can empty their own trash (regression: the ACL join
     * required a live READ ACL, which is soft-deleted on trash, so this
     * previously returned deleted_count == 0 for non-admins).
     */
    @Test
    public void testEmptyTrashOwnerScope() {
        clientUtil.createUser("emptytrash1");
        String t1 = clientUtil.login("emptytrash1");

        // Create a document
        JsonObject json = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t1)
                .put(Entity.form(new Form()
                        .param("title", "Trash owner scope doc")
                        .param("language", "eng")), JsonObject.class);
        String docId = json.getString("id");

        // Trash it (soft-delete)
        json = target().path("/document/" + docId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t1)
                .delete(JsonObject.class);
        Assertions.assertEquals("ok", json.getString("status"));

        // Empty own trash: exactly the one owned trashed doc is purged
        json = target().path("/document/trash").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t1)
                .delete(JsonObject.class);
        Assertions.assertEquals(1, json.getJsonNumber("deleted_count").intValue());

        // The doc is permanently gone (already purged by empty-trash)
        Response response = target().path("/document/" + docId + "/permanent").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t1)
                .delete();
        Assertions.assertEquals(Status.NOT_FOUND, Status.fromStatusCode(response.getStatus()));
    }

    /**
     * SEC-01 (cross-owner data-loss): emptying trash must NOT permanently
     * delete documents owned by another user. Admin is the actor being
     * restricted here (admin previously bypassed the ACL filter and purged
     * every owner's trashed docs).
     */
    @Test
    public void testEmptyTrashDoesNotPurgeOtherOwners() {
        clientUtil.createUser("emptytrash2");
        String t2 = clientUtil.login("emptytrash2");

        // t2 creates and trashes a document
        JsonObject json = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t2)
                .put(Entity.form(new Form()
                        .param("title", "Other owner trashed doc")
                        .param("language", "eng")), JsonObject.class);
        String docId = json.getString("id");
        target().path("/document/" + docId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t2)
                .delete(JsonObject.class);

        // Admin empties trash
        String adminToken = adminToken();
        target().path("/document/trash").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete(JsonObject.class);

        // t2's trashed doc must have survived: t2 can still permanently delete it
        json = target().path("/document/" + docId + "/permanent").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t2)
                .delete(JsonObject.class);
        Assertions.assertEquals("ok", json.getString("status"));
    }

    /**
     * NF-01: empty-trash deletion/quota events must be attributed to the real
     * owner. Emptying one's own trash releases the owner's storage; an admin
     * emptying trash must not touch (nor release against the wrong account) a
     * different owner's storage.
     */
    @Test
    public void testEmptyTrashQuotaAttribution() throws Exception {
        // --- Owner path: emptying own trash releases the owner's storage ---
        clientUtil.createUser("emptytrash3");
        String t3 = clientUtil.login("emptytrash3");

        JsonObject json = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t3)
                .put(Entity.form(new Form()
                        .param("title", "Quota owner doc")
                        .param("language", "eng")), JsonObject.class);
        String doc3Id = json.getString("id");
        clientUtil.addFileToDocument(FILE_EINSTEIN_ROOSEVELT_LETTER_PNG, t3, doc3Id);
        Assertions.assertEquals(FILE_EINSTEIN_ROOSEVELT_LETTER_PNG_SIZE, getUserQuota(t3));

        // Trash keeps the file counted
        target().path("/document/" + doc3Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t3)
                .delete(JsonObject.class);
        Assertions.assertEquals(FILE_EINSTEIN_ROOSEVELT_LETTER_PNG_SIZE, getUserQuota(t3));

        // Emptying own trash releases the owner's storage
        json = target().path("/document/trash").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t3)
                .delete(JsonObject.class);
        Assertions.assertEquals(1, json.getJsonNumber("deleted_count").intValue());
        Assertions.assertEquals(0L, getUserQuota(t3));

        // --- Cross-owner path: admin empty-trash must not affect another owner's storage ---
        clientUtil.createUser("emptytrash4");
        String t4 = clientUtil.login("emptytrash4");

        json = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t4)
                .put(Entity.form(new Form()
                        .param("title", "Quota other-owner doc")
                        .param("language", "eng")), JsonObject.class);
        String doc4Id = json.getString("id");
        clientUtil.addFileToDocument(FILE_EINSTEIN_ROOSEVELT_LETTER_PNG, t4, doc4Id);
        target().path("/document/" + doc4Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t4)
                .delete(JsonObject.class);
        Assertions.assertEquals(FILE_EINSTEIN_ROOSEVELT_LETTER_PNG_SIZE, getUserQuota(t4));

        // Admin empties trash: t4's file is preserved and its storage unchanged
        String adminToken = adminToken();
        target().path("/document/trash").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete(JsonObject.class);
        Assertions.assertEquals(FILE_EINSTEIN_ROOSEVELT_LETTER_PNG_SIZE, getUserQuota(t4));
    }

    /**
     * BL-004 (twin of SEC-01): listing trash must be scoped to the caller's own
     * documents. A non-admin owner can see their own trashed document (regression:
     * listTrash filtered by the Lucene ACL join, which requires a live READ ACL —
     * soft-deleted on trash — so a non-admin's trash list was always empty).
     */
    @Test
    public void testListTrashOwnerScope() {
        clientUtil.createUser("listtrash1");
        String t1 = clientUtil.login("listtrash1");

        // Create a document and trash it (soft-delete)
        String docId = trashNewDocument(t1, "List trash owner scope doc");

        // The owner can list their own trashed document
        JsonObject json = target().path("/document/trash").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t1)
                .get(JsonObject.class);
        Assertions.assertEquals(1, json.getJsonNumber("total").intValue());
        Assertions.assertTrue(trashListContains(json, docId),
                "Owner's trash list must contain their own trashed document");
    }

    /**
     * BL-004: the trash list must not leak another owner's trashed documents.
     */
    @Test
    public void testListTrashDoesNotShowOtherOwners() {
        clientUtil.createUser("listtrash2");
        String t2 = clientUtil.login("listtrash2");
        clientUtil.createUser("listtrash3");
        String t3 = clientUtil.login("listtrash3");

        // t2 creates and trashes a document
        String doc2Id = trashNewDocument(t2, "Other owner list trash doc");

        // t3's trash list must not contain t2's trashed document
        JsonObject json = target().path("/document/trash").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t3)
                .get(JsonObject.class);
        Assertions.assertFalse(trashListContains(json, doc2Id),
                "Trash list must not leak another owner's trashed document");
    }

    /**
     * BL-004: the trash list honors the `asc` order parameter over the delete date
     * (default descending / most-recently-trashed first).
     */
    @Test
    public void testListTrashSortOrder() throws InterruptedException {
        clientUtil.createUser("listtrash4");
        String t4 = clientUtil.login("listtrash4");

        // Trash two documents with a gap so their delete dates are strictly distinct,
        // making the expected ordering deterministic.
        String docAId = trashNewDocument(t4, "List trash order doc A");
        Thread.sleep(10);
        String docBId = trashNewDocument(t4, "List trash order doc B");

        // Default order is descending by delete date: the newest (B) comes first
        List<String> descIds = trashListIds(target().path("/document/trash").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t4)
                .get(JsonObject.class));
        Assertions.assertEquals(List.of(docBId, docAId), descIds);

        // asc=true flips to ascending by delete date: the oldest (A) comes first
        List<String> ascIds = trashListIds(target().path("/document/trash").queryParam("asc", "true").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t4)
                .get(JsonObject.class));
        Assertions.assertEquals(List.of(docAId, docBId), ascIds);
    }

    /**
     * BL-004: the trash list is owner-scoped for the admin too (consistent with the
     * owner-scoped emptyTrash/restore/permanentDelete) — admin does not see another
     * owner's trashed documents in the list.
     */
    @Test
    public void testListTrashAdminDoesNotSeeOtherOwners() {
        clientUtil.createUser("listtrash6");
        String t6 = clientUtil.login("listtrash6");

        String doc6Id = trashNewDocument(t6, "Admin-should-not-see trashed doc");

        // Admin lists trash: the other owner's trashed document must be absent
        String adminToken = adminToken();
        JsonObject json = target().path("/document/trash").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .get(JsonObject.class);
        Assertions.assertFalse(trashListContains(json, doc6Id),
                "Admin's trash list must not contain another owner's trashed document");
    }

    /**
     * BL-004: the trash list honors limit/offset paging in memory while total
     * always reflects the full count of the caller's trashed documents.
     */
    @Test
    public void testListTrashPagination() {
        clientUtil.createUser("listtrash5");
        String t5 = clientUtil.login("listtrash5");

        // Trash three documents
        String docAId = trashNewDocument(t5, "List trash page doc A");
        String docBId = trashNewDocument(t5, "List trash page doc B");
        String docCId = trashNewDocument(t5, "List trash page doc C");

        // A very large limit combined with a non-zero offset must not overflow the in-memory
        // pagination bounds (fromIndex + limit) and 500; it returns the tail of the list.
        JsonObject huge = target().path("/document/trash")
                .queryParam("limit", String.valueOf(Integer.MAX_VALUE))
                .queryParam("offset", "1").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t5)
                .get(JsonObject.class);
        Assertions.assertEquals(3, huge.getInt("total"));
        Assertions.assertEquals(2, huge.getJsonArray("documents").size());

        // No limit: all three returned, total == 3
        JsonObject full = target().path("/document/trash").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t5)
                .get(JsonObject.class);
        Assertions.assertEquals(3, full.getInt("total"));
        Assertions.assertEquals(3, full.getJsonArray("documents").size());

        // First page (limit=2): 2 documents, total still 3
        JsonObject page1 = target().path("/document/trash").queryParam("limit", "2").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t5)
                .get(JsonObject.class);
        Assertions.assertEquals(3, page1.getInt("total"));
        Assertions.assertEquals(2, page1.getJsonArray("documents").size());

        // Second page (limit=2, offset=2): the remaining 1 document, total still 3
        JsonObject page2 = target().path("/document/trash").queryParam("limit", "2").queryParam("offset", "2").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t5)
                .get(JsonObject.class);
        Assertions.assertEquals(3, page2.getInt("total"));
        Assertions.assertEquals(1, page2.getJsonArray("documents").size());

        // The two pages are disjoint and together cover all three trashed documents
        Set<String> pageIds = new HashSet<>();
        for (JsonValue v : page1.getJsonArray("documents")) {
            pageIds.add(v.asJsonObject().getString("id"));
        }
        for (JsonValue v : page2.getJsonArray("documents")) {
            Assertions.assertTrue(pageIds.add(v.asJsonObject().getString("id")),
                    "paged results must not overlap across offset boundaries");
        }
        Assertions.assertEquals(Set.of(docAId, docBId, docCId), pageIds);
    }

    /**
     * Creates a document for the given token, trashes it, and returns its id.
     */
    private String trashNewDocument(String token, String title) {
        JsonObject json = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .put(Entity.form(new Form()
                        .param("title", title)
                        .param("language", "eng")), JsonObject.class);
        String docId = json.getString("id");
        target().path("/document/" + docId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .delete(JsonObject.class);
        return docId;
    }

    /**
     * @return the ordered list of document ids in a trash list response
     */
    private List<String> trashListIds(JsonObject trashResponse) {
        List<String> ids = new ArrayList<>();
        for (JsonValue value : trashResponse.getJsonArray("documents")) {
            ids.add(value.asJsonObject().getString("id"));
        }
        return ids;
    }

    /**
     * @return true if the trash list response's documents array contains a document with the given id
     */
    private boolean trashListContains(JsonObject trashResponse, String docId) {
        for (JsonValue value : trashResponse.getJsonArray("documents")) {
            if (docId.equals(value.asJsonObject().getString("id"))) {
                return true;
            }
        }
        return false;
    }

    private long getUserQuota(String userToken) {
        return target().path("/user").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, userToken)
                .get(JsonObject.class).getJsonNumber("storage_current").longValue();
    }

    /**
     * SEC-04 / BL-009: editing the tags of a shared document must preserve tag
     * links the editor cannot see. A WRITE-sharing editor who saves only their
     * own visible tags must not silently delete the owner's private tag.
     */
    @Test
    public void testUpdateTagsPreservesInvisibleTags() {
        // Owner A: a private tag and a document carrying it
        clientUtil.createUser("tagkeep1");
        String t1 = clientUtil.login("tagkeep1");
        String tagAId = target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t1)
                .put(Entity.form(new Form().param("name", "OwnerPrivateTag").param("color", "#123456")), JsonObject.class)
                .getString("id");
        String docId = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t1)
                .put(Entity.form(new Form()
                        .param("title", "Shared editable doc")
                        .param("language", "eng")
                        .param("tags", tagAId)), JsonObject.class)
                .getString("id");

        // Share the document to B with WRITE (to edit) and READ (to view)
        clientUtil.createUser("tagkeep2");
        String t2 = clientUtil.login("tagkeep2");
        for (String perm : new String[]{"WRITE", "READ"}) {
            target().path("/acl").request()
                    .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t1)
                    .put(Entity.form(new Form()
                            .param("source", docId)
                            .param("perm", perm)
                            .param("target", "tagkeep2")
                            .param("type", "USER")), JsonObject.class);
        }

        // B creates its own tag and edits the shared doc to carry only that tag
        String tagBId = target().path("/tag").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t2)
                .put(Entity.form(new Form().param("name", "EditorTag").param("color", "#654321")), JsonObject.class)
                .getString("id");
        target().path("/document/" + docId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t2)
                .post(Entity.form(new Form()
                        .param("title", "Shared editable doc")
                        .param("language", "eng")
                        .param("tags", tagBId)), JsonObject.class);

        // A must still see its private tag on the document (link preserved)
        JsonArray aTags = target().path("/document/" + docId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t1)
                .get(JsonObject.class).getJsonArray("tags");
        Assertions.assertTrue(tagsContain(aTags, tagAId),
                "owner's invisible tag must be preserved after the editor's tag update");

        // B sees the tag it added, but not A's private tag (invisible to B)
        JsonArray bTags = target().path("/document/" + docId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, t2)
                .get(JsonObject.class).getJsonArray("tags");
        Assertions.assertTrue(tagsContain(bTags, tagBId));
        Assertions.assertFalse(tagsContain(bTags, tagAId));
    }

    private boolean tagsContain(JsonArray tags, String tagId) {
        for (int i = 0; i < tags.size(); i++) {
            if (tags.getJsonObject(i).getString("id").equals(tagId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Test the full-account export endpoint (GET /document/export). It must stream a ZIP
     * containing a manifest plus the caller's OWN documents' files, scoped to the caller
     * (a non-admin must not receive another user's documents).
     *
     * @throws Exception e
     */
    @Test
    public void testDocumentExport() throws Exception {
        // Two independent non-admin users, each with a document and a file
        clientUtil.createUser("export1");
        String export1Token = clientUtil.login("export1");
        clientUtil.createUser("export2");
        String export2Token = clientUtil.login("export2");

        String doc1Id = clientUtil.createDocument(export1Token);
        clientUtil.addFileToDocument(FILE_PIA_00452_JPG, export1Token, doc1Id);

        String doc2Id = clientUtil.createDocument(export2Token);
        clientUtil.addFileToDocument(FILE_EINSTEIN_ROOSEVELT_LETTER_PNG, export2Token, doc2Id);

        // export1 exports their account
        Response response = target().path("/document/export").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, export1Token)
                .get();
        Assertions.assertEquals(Status.OK, Status.fromStatusCode(response.getStatus()));
        Map<String, byte[]> entries = readZipEntries((InputStream) response.getEntity());

        // Manifest is present and describes exactly the caller's own document
        Assertions.assertTrue(entries.containsKey("manifest.json"), "manifest.json missing from export");
        JsonObject manifest;
        try (JsonReader reader = Json.createReader(new ByteArrayInputStream(entries.get("manifest.json")))) {
            manifest = reader.readObject();
        }
        Assertions.assertEquals("export1", manifest.getString("username"));
        JsonArray manifestDocs = manifest.getJsonArray("documents");
        Assertions.assertEquals(1, manifestDocs.size());
        Assertions.assertEquals(1, manifest.getInt("document_count"));
        JsonObject manifestDoc = manifestDocs.getJsonObject(0);
        Assertions.assertEquals(doc1Id, manifestDoc.getString("id"));
        Assertions.assertEquals("Document Title", manifestDoc.getString("title"));

        // No manifest document references export2's document
        for (int i = 0; i < manifestDocs.size(); i++) {
            Assertions.assertNotEquals(doc2Id, manifestDocs.getJsonObject(i).getString("id"));
        }

        // The manifest points at a real file entry, marked exported, that exists in the ZIP
        // with byte-for-byte the original file's content (a strong positive, not just size).
        JsonArray manifestFiles = manifestDoc.getJsonArray("files");
        Assertions.assertEquals(1, manifestFiles.size());
        JsonObject manifestFile = manifestFiles.getJsonObject(0);
        Assertions.assertTrue(manifestFile.getBoolean("exported"));
        String filePath = manifestFile.getString("path");
        Assertions.assertTrue(entries.containsKey(filePath), "file entry " + filePath + " missing from ZIP");
        byte[] ownFixture = Resources.toByteArray(Resources.getResource(FILE_PIA_00452_JPG));
        Assertions.assertArrayEquals(ownFixture, entries.get(filePath));

        // Scoping: export2's file must NOT appear in export1's export — checked by exact content.
        byte[] otherFixture = Resources.toByteArray(Resources.getResource(FILE_EINSTEIN_ROOSEVELT_LETTER_PNG));
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            Assertions.assertFalse(entry.getKey().contains("Einstein-Roosevelt-letter"),
                    "export leaked another user's file name: " + entry.getKey());
            Assertions.assertFalse(Arrays.equals(otherFixture, entry.getValue()),
                    "export leaked another user's file content: " + entry.getKey());
        }

        // Anonymous callers are rejected
        response = target().path("/document/export").request().get();
        Assertions.assertEquals(Status.FORBIDDEN, Status.fromStatusCode(response.getStatus()));
    }

    /**
     * The export must reject an over-cap account as a PREFLIGHT (before eagerly loading the
     * documents) and must audit-log a successful export. Uses a low cap set via the system
     * property seam so a small, bounded fixture (cap + 1 documents) proves the rejection
     * without needing to load thousands of documents.
     *
     * @throws Exception e
     */
    @Test
    public void testDocumentExportGuard() throws Exception {
        // Cap the export to 2 documents for this test.
        System.setProperty(com.sismics.docs.core.util.ExportGuard.MAX_DOCUMENTS_PROPERTY, "2");
        try {
            clientUtil.createUser("exportguard");
            String token = clientUtil.login("exportguard");

            // Create cap + 1 (3) documents so the account is over the cap.
            clientUtil.createDocument(token);
            clientUtil.createDocument(token);
            clientUtil.createDocument(token);

            // Over-cap export is rejected with a 400 ExportTooLarge, WITHOUT loading everything.
            Response response = target().path("/document/export").request()
                    .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                    .get();
            Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(response.getStatus()));
            Assertions.assertEquals("ExportTooLarge", readErrorType(response));

            // No Export audit-log row should have been written for the rejected export.
            Assertions.assertEquals(0, countExportAuditLogs(token),
                    "rejected over-cap export must not write an audit-log row");

            // Now raise the cap so the same account is within bounds; the export succeeds
            // and an Export audit-log row is written.
            System.setProperty(com.sismics.docs.core.util.ExportGuard.MAX_DOCUMENTS_PROPERTY, "10");
            response = target().path("/document/export").request()
                    .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                    .get();
            Assertions.assertEquals(Status.OK, Status.fromStatusCode(response.getStatus()));
            // Drain the stream so the request completes.
            readZipEntries((InputStream) response.getEntity());

            Assertions.assertEquals(1, countExportAuditLogs(token),
                    "successful export must write exactly one audit-log row");
        } finally {
            System.clearProperty(com.sismics.docs.core.util.ExportGuard.MAX_DOCUMENTS_PROPERTY);
        }
    }

    /**
     * The export must be disabled entirely when the feature flag is off.
     *
     * @throws Exception e
     */
    @Test
    public void testDocumentExportDisabled() throws Exception {
        System.setProperty(com.sismics.docs.core.util.ExportGuard.ENABLED_PROPERTY, "false");
        try {
            clientUtil.createUser("exportdisabled");
            String token = clientUtil.login("exportdisabled");
            clientUtil.createDocument(token);

            Response response = target().path("/document/export").request()
                    .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                    .get();
            Assertions.assertEquals(Status.BAD_REQUEST, Status.fromStatusCode(response.getStatus()));
            Assertions.assertEquals("ExportDisabled", readErrorType(response));
        } finally {
            System.clearProperty(com.sismics.docs.core.util.ExportGuard.ENABLED_PROPERTY);
        }
    }

    /**
     * RR-08: an explicitly empty create_date must be treated as omitted, not rejected
     * or parsed as an error. ValidationUtil.validateDate treats an empty/blank string
     * as null (nullable=true), so document creation and update succeed and the server
     * assigns/keeps a valid create_date. This guards against a regression where an
     * empty string would be fed to the numeric parser and throw a 400/500.
     */
    @Test
    public void testEmptyCreateDate() {
        clientUtil.createUser("emptycreatedate");
        String token = clientUtil.login("emptycreatedate");

        // Create with an empty-string create_date: accepted, treated as omitted
        JsonObject json = target().path("/document").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .put(Entity.form(new Form()
                        .param("title", "Empty create_date doc")
                        .param("language", "eng")
                        .param("create_date", "")), JsonObject.class);
        String documentId = json.getString("id");
        Assertions.assertNotNull(documentId);

        // The server assigned a valid (non-null, positive) create_date
        json = target().path("/document/" + documentId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class);
        Assertions.assertNotNull(json.getJsonNumber("create_date"));
        Assertions.assertTrue(json.getJsonNumber("create_date").longValue() > 0);

        // Update with an empty-string create_date is likewise accepted (200), not a 400
        Response response = target().path("/document/" + documentId).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .post(Entity.form(new Form()
                        .param("title", "Empty create_date doc updated")
                        .param("language", "eng")
                        .param("create_date", "")));
        Assertions.assertEquals(Status.OK, Status.fromStatusCode(response.getStatus()));

        // Cleanup
        String adminToken = adminToken();
        target().path("/user/emptycreatedate")
                .queryParam("reassign_to_username", "admin").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, adminToken)
                .delete();
    }

    /**
     * Read the JSON "type" field from an error response body. The export endpoint declares
     * {@code @Produces} octet-stream, so the error entity's negotiated media type is not
     * JSON; read the raw bytes and parse them as JSON here.
     */
    private String readErrorType(Response response) throws Exception {
        byte[] body = ByteStreams.toByteArray((InputStream) response.getEntity());
        try (JsonReader reader = Json.createReader(new ByteArrayInputStream(body))) {
            return reader.readObject().getString("type");
        }
    }

    /**
     * Count the "Export"-class audit-log rows visible to the given user via GET /auditlog.
     */
    private int countExportAuditLogs(String token) {
        JsonObject logs = target().path("/auditlog").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, token)
                .get(JsonObject.class);
        JsonArray logArray = logs.getJsonArray("logs");
        int count = 0;
        for (int i = 0; i < logArray.size(); i++) {
            if ("Export".equals(logArray.getJsonObject(i).getString("class"))) {
                count++;
            }
        }
        return count;
    }

    /**
     * Read all entries of a ZIP stream into a name -> bytes map.
     */
    private Map<String, byte[]> readZipEntries(InputStream is) throws Exception {
        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(is)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ByteStreams.copy(zis, baos);
                entries.put(entry.getName(), baos.toByteArray());
                zis.closeEntry();
            }
        }
        return entries;
    }
}

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

import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.io.InputStream;
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
        Assertions.assertFalse(documents.getJsonObject(0).getBoolean("active_route"));

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

        // Check suggestions
        json = target().path("/document/list")
                .queryParam("search", "docu")
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, document1Token)
                .get(JsonObject.class);
        String suggestion = json.getJsonArray("suggestions").getString(0);
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
        Assertions.assertEquals(0, relations.size());
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

        // Get document 2
        json = target().path("/document/" + document1Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, document1Token)
                .get(JsonObject.class);
        relations = json.getJsonArray("relations");
        Assertions.assertEquals(0, relations.size());
        
        // Deletes a document
        json = target().path("/document/" + document1Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, document1Token)
                .delete(JsonObject.class);
        Assertions.assertEquals("ok", json.getString("status"));

        // Deletes a non-existing document
        response = target().path("/document/69b79238-84bb-4263-a32f-9cbdf8c92188").request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, document1Token)
                .delete();
        Assertions.assertEquals(Status.NOT_FOUND, Status.fromStatusCode(response.getStatus()));

        // Check that the associated files are deleted from FS
        java.io.File storedFile = DirectoryUtil.getStorageDirectory().resolve(file1Id).toFile();
        java.io.File webFile = DirectoryUtil.getStorageDirectory().resolve(file1Id + "_web").toFile();
        java.io.File thumbnailFile = DirectoryUtil.getStorageDirectory().resolve(file1Id + "_thumb").toFile();
        Assertions.assertFalse(storedFile.exists());
        Assertions.assertFalse(webFile.exists());
        Assertions.assertFalse(thumbnailFile.exists());
        
        // Get a document (KO)
        response = target().path("/document/" + document1Id).request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, document1Token)
                .get();
        Assertions.assertEquals(Status.NOT_FOUND, Status.fromStatusCode(response.getStatus()));
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

        // Search documents by query in full content
        JsonObject json = target().path("/document/list")
                .queryParam("search", "full:vrandecic")
                .request()
                .cookie(TokenBasedSecurityFilter.COOKIE_NAME, documentPdfToken)
                .get(JsonObject.class);
        Assertions.assertEquals(1, json.getJsonArray("documents").size());
        
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
}

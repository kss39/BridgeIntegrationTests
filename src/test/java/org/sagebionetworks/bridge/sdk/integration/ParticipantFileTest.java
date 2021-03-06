package org.sagebionetworks.bridge.sdk.integration;

import okhttp3.ResponseBody;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.sagebionetworks.bridge.rest.api.ForConsentedUsersApi;
import org.sagebionetworks.bridge.rest.model.Message;
import org.sagebionetworks.bridge.rest.model.ParticipantFile;
import org.sagebionetworks.bridge.rest.model.ParticipantFileList;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;

import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Scanner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class ParticipantFileTest {

    static final String TEST_UPLOAD_STRING = "This text uploaded as an object via presigned URL.";

    TestUser participant;

    ForConsentedUsersApi userApi;

    ParticipantFile file;

    @Before
    public void before() throws Exception {
        participant = TestUserHelper.createAndSignInUser(ParticipantFileTest.class, true);
        userApi = participant.getClient(ForConsentedUsersApi.class);
    }

    @After
    public void after() throws Exception {
        List<ParticipantFile> remainder = userApi.getParticipantFiles(null, 10).execute().body().getItems();
        while (!remainder.isEmpty()) {
            for (ParticipantFile file : remainder) {
                userApi.deleteParticipantFile(file.getFileId()).execute();
            }
            remainder = userApi.getParticipantFiles(null, 10).execute().body().getItems();
        }
        participant.signOutAndDeleteUser();
    }

    @Test
    public void crudParticipantFile() throws Exception {
        file = new ParticipantFile();
        file.setMimeType("text/plain");

        ForConsentedUsersApi userApi = participant.getClient(ForConsentedUsersApi.class);

        final ParticipantFile keys = userApi.createParticipantFile("file_id", file).execute().body();

        assertNotNull(keys);
        assertEquals(keys.getMimeType(), file.getMimeType());
        String uploadUrl = keys.getUploadUrl();

        URL url = new URL(uploadUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setDoOutput(true);
        connection.setRequestMethod("PUT");
        connection.setRequestProperty("Content-Type", "text/plain");
        OutputStreamWriter out = new OutputStreamWriter(connection.getOutputStream());
        out.write(TEST_UPLOAD_STRING);
        out.close();
        assertEquals(connection.getResponseCode(), 200);
        connection.disconnect();

        ParticipantFileList results = userApi.getParticipantFiles(null, 5).execute().body();
        assertNotNull(results);
        List<ParticipantFile> resultList = results.getItems();
        assertEquals(resultList.size(), 1);
        ParticipantFile onlyFile = resultList.get(0);
        assertEquals(onlyFile.getMimeType(), "text/plain");
        assertEquals(onlyFile.getFileId(), "file_id");

        for (int i = 0; i < 5; i++) {
            file = new ParticipantFile();
            file.setMimeType("text/plain");
            userApi.createParticipantFile(i + "file", file).execute();
        }


        ResponseBody body = userApi.getParticipantFile("file_id").execute().body();
        assertNotNull(body);
        assertNotNull(body.contentType());
        assertEquals(body.contentType().toString(), "text/plain");
        assertEquals(body.contentLength(), TEST_UPLOAD_STRING.length());
        try (InputStream content = body.byteStream()) {
            Scanner sc = new Scanner(content);
            assertEquals(sc.nextLine(), "This text uploaded as an object via presigned URL.");
        }

        results = userApi.getParticipantFiles(null, 5).execute().body();
        assertNotNull(results);
        resultList = results.getItems();
        assertEquals(resultList.size(), 5);
        String nextKey = results.getNextPageOffsetKey();
        results = userApi.getParticipantFiles(nextKey, 5).execute().body();
        assertNotNull(results);
        resultList = results.getItems();
        assertEquals(resultList.size(), 1);
        assertNull(results.getNextPageOffsetKey());

        Message deleteMessage = userApi.deleteParticipantFile("file_id").execute().body();
        assertNotNull(deleteMessage);
        assertEquals(deleteMessage.getMessage(), "Participant file deleted.");

        for (int i = 0; i < 5; i++) {
            userApi.deleteParticipantFile(i + "file").execute();
        }

        results = userApi.getParticipantFiles(null, 5).execute().body();
        assertNotNull(results);
        resultList = results.getItems();
        assertEquals(resultList.size(), 0);
    }
}

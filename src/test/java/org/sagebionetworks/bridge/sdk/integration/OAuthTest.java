package org.sagebionetworks.bridge.sdk.integration;

import static java.lang.String.format;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.sagebionetworks.bridge.rest.model.Role.WORKER;
import static org.sagebionetworks.bridge.sdk.integration.Tests.escapeJSON;
import static org.sagebionetworks.bridge.util.IntegTestUtils.STUDY_ID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;

import org.apache.http.HttpResponse;
import org.apache.http.client.fluent.Request;
import org.apache.http.entity.StringEntity;
import org.apache.http.util.EntityUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.sagebionetworks.bridge.rest.Config;
import org.sagebionetworks.bridge.rest.RestUtils;
import org.sagebionetworks.bridge.rest.api.AuthenticationApi;
import org.sagebionetworks.bridge.rest.api.ForAdminsApi;
import org.sagebionetworks.bridge.rest.api.ForConsentedUsersApi;
import org.sagebionetworks.bridge.rest.api.ForWorkersApi;
import org.sagebionetworks.bridge.rest.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.rest.model.ForwardCursorStringList;
import org.sagebionetworks.bridge.rest.model.OAuthAuthorizationToken;
import org.sagebionetworks.bridge.rest.model.OAuthProvider;
import org.sagebionetworks.bridge.rest.model.SignIn;
import org.sagebionetworks.bridge.rest.model.SignUp;
import org.sagebionetworks.bridge.rest.model.Study;
import org.sagebionetworks.bridge.rest.model.UserSessionInfo;
import org.sagebionetworks.bridge.rest.model.VersionHolder;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.user.TestUserHelper.TestUser;

public class OAuthTest {
    private static final String SYNAPSE_LOGIN_URL = "https://repo-prod.prod.sagebase.org/auth/v1/login";
    private static final String SYNAPSE_OAUTH_CONSENT = "https://repo-prod.prod.sagebase.org/auth/v1/oauth2/consent";

    private TestUser admin;
    private TestUser user;
    private TestUser worker;
    
    @Before
    public void before() throws Exception {
        admin = TestUserHelper.getSignedInAdmin();
        user = TestUserHelper.createAndSignInUser(OAuthTest.class, true);

        // This account will be able to sign in using the paired Synapse credentials.
        String synapseUserId = admin.getConfig().get("synapse.test.user.id");
        worker = TestUserHelper.createAndSignInUser(OAuthTest.class, true, 
                new SignUp().roles(ImmutableList.of(WORKER)).synapseUserId(synapseUserId));
    }
    
    @After
    public void after() throws Exception {
        if (user != null) {
            user.signOutAndDeleteUser();
        }
        if (worker != null) {
            worker.signOutAndDeleteUser();
        }
    }
    
    @Test(expected = EntityNotFoundException.class)
    public void requestOAuthAccessTokenExists() throws Exception {
        ForConsentedUsersApi usersApi = user.getClient(ForConsentedUsersApi.class);
        
        OAuthAuthorizationToken token = new OAuthAuthorizationToken().authToken("authToken");
        usersApi.requestOAuthAccessToken("vendorId", token).execute().body();
    }
    
    @Test
    public void test() throws Exception {
        ForWorkersApi workersApi = worker.getClient(ForWorkersApi.class);
        
        try {
            workersApi.getHealthCodesGrantingOAuthAccess(worker.getStudyId(), "unused-vendor-id", null, null).execute().body();
            fail("Should have thrown exception.");
        } catch(EntityNotFoundException e) {
            assertEquals("OAuthProvider not found.", e.getMessage());
        }
        try {
            workersApi.getOAuthAccessToken(worker.getStudyId(), "unused-vendor-id", "ABC-DEF-GHI").execute().body();
            fail("Should have thrown exception.");
        } catch(EntityNotFoundException e) {
            assertEquals("OAuthProvider not found.", e.getMessage());
        }
        ForAdminsApi adminApi = admin.getClient(ForAdminsApi.class);
        Study study = adminApi.getStudy(worker.getStudyId()).execute().body();
        try {
            OAuthProvider provider = new OAuthProvider().clientId("foo").endpoint("https://webservices.sagebridge.org/")
                    .callbackUrl("https://webservices.sagebridge.org/").secret("secret");
            
            study.getOAuthProviders().put("bridge", provider);
            VersionHolder version = adminApi.updateStudy(study.getIdentifier(), study).execute().body();
            study.setVersion(version.getVersion()); 
            
            ForwardCursorStringList list = workersApi.getHealthCodesGrantingOAuthAccess(worker.getStudyId(), "bridge", null, null).execute().body();
            assertTrue(list.getItems().isEmpty());
            try {
                workersApi.getOAuthAccessToken(worker.getStudyId(), "bridge", "ABC-DEF-GHI").execute();
                fail("Should have thrown an exception");
            } catch(EntityNotFoundException e) {
                
            }
        } finally {
            study.getOAuthProviders().remove("bridge");
            adminApi.updateStudy(study.getIdentifier(), study).execute();
        }
    }
    
    @Test
    public void signInWithSynapseAccount() throws Exception {
        Config config = worker.getConfig();
        String userEmail = config.get("synapse.test.user");
        String userPassword = config.get("synapse.test.user.password");
        worker.signOut();

        // Sign in to Synapse
        String payload = escapeJSON(format("{'username':'%s','password':'%s'}", userEmail, userPassword));
        HttpResponse response = Request.Post(SYNAPSE_LOGIN_URL)
                .setHeader("content-type", "application/json")
                .body(new StringEntity(payload))
                .execute().returnResponse();
        
        String sessionToken = getValue(response, "sessionToken");

        // Consent to return OAuth authorization token
        payload = escapeJSON("{'clientId':'100020','scope':'openid','claims':'{\\\"id_token\\\":{\\\"userid\\\":null}}',"+
                "'responseType':'code','redirectUri':'https://research-staging.sagebridge.org'}");
        response = Request.Post(SYNAPSE_OAUTH_CONSENT)
                .setHeader("content-type", "application/json")
                .setHeader("sessiontoken", sessionToken)
                .body(new StringEntity(payload))
                .execute().returnResponse();
        String authToken = getValue(response, "access_code");
        
        // Call bridge to get a session
        OAuthAuthorizationToken token = new OAuthAuthorizationToken()
                .study(STUDY_ID)
                .vendorId("synapse")
                .authToken(authToken)
                .callbackUrl("https://research-staging.sagebridge.org");
        
        AuthenticationApi authApi = worker.getClient(AuthenticationApi.class);
        UserSessionInfo session = authApi.signInWithOauthToken(token).execute().body();
        
        assertEquals(session.getId(), worker.getSession().getId());
        assertEquals(session.getSynapseUserId(), worker.getSession().getSynapseUserId());
    }
    
    @Test
    public void signInWithSynapseAccountUsingRestUtils() throws Exception {
        Config config = worker.getConfig();
        String userEmail = config.get("synapse.test.user");
        String userPassword = config.get("synapse.test.user.password");
        worker.signOut();

        SignIn signIn = new SignIn().study(STUDY_ID).email(userEmail).password(userPassword);
        AuthenticationApi authApi = worker.getClient(AuthenticationApi.class);
        
        UserSessionInfo session = RestUtils.signInWithSynapse(authApi, signIn);
        
        assertEquals(session.getId(), worker.getSession().getId());
        assertEquals(session.getSynapseUserId(), worker.getSession().getSynapseUserId());
    }
    
    private String getValue(HttpResponse response, String property) throws Exception {
        String responseBody = EntityUtils.toString(response.getEntity());
        JsonNode node = new ObjectMapper().readTree(responseBody);
        return node.get(property).textValue();
    }
}

package org.sagebionetworks.bridge.sdk.integration;

import static com.google.common.base.Preconditions.checkNotNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.sagebionetworks.bridge.sdk.ClientManager;
import org.sagebionetworks.bridge.sdk.Config;
import org.sagebionetworks.bridge.sdk.rest.api.AuthenticationApi;
import org.sagebionetworks.bridge.sdk.rest.api.ForAdminsApi;
import org.sagebionetworks.bridge.sdk.rest.exceptions.BridgeSDKException;
import org.sagebionetworks.bridge.sdk.rest.exceptions.ConsentRequiredException;
import org.sagebionetworks.bridge.sdk.rest.model.ClientInfo;
import org.sagebionetworks.bridge.sdk.rest.model.EmptyPayload;
import org.sagebionetworks.bridge.sdk.rest.model.Role;
import org.sagebionetworks.bridge.sdk.rest.model.SignIn;
import org.sagebionetworks.bridge.sdk.rest.model.SignUp;
import org.sagebionetworks.bridge.sdk.rest.model.UserSessionInfo;

import com.google.common.collect.Sets;

public class TestUserHelper {

    private static final Config CONFIG = new Config();
    private static final SignIn ADMIN_SIGN_IN = CONFIG.getAdminSignIn();
    private static final EmptyPayload EMPTY_PAYLOAD = new EmptyPayload();
    private static final String PASSWORD = "P4ssword";
    private static final ClientInfo CLIENT_INFO = new ClientInfo();
    static {
        CLIENT_INFO.setAppName("Integration Tests");
        CLIENT_INFO.setAppVersion(0);
    }
    
    public static class TestUser {
        private SignIn signIn;
        private ClientManager manager;
        private UserSessionInfo userSession;

        public TestUser(SignIn signIn, ClientManager manager) {
            checkNotNull(signIn.getStudy());
            checkNotNull(signIn.getEmail());
            checkNotNull(signIn.getPassword());
            checkNotNull(manager);
            this.signIn = signIn;
            this.manager = manager;
        }
        public UserSessionInfo getSession() {
            return userSession;
        }
        public String getEmail() {
            return signIn.getEmail();
        }
        public String getPassword() {
            return signIn.getPassword();
        }
        public List<Role> getRoles() {
            return userSession.getRoles();
        }
        public String getDefaultSubpopulation() {
            return signIn.getStudy();
        }
        public String getStudyId() {
            return signIn.getStudy();
        }
        public <T> T getClient(Class<T> service) {
            return manager.getClient(service);
        }
        public UserSessionInfo signInAgain() {
            AuthenticationApi authApi = manager.getClient(AuthenticationApi.class);
            try {
                userSession = authApi.signIn(getSignIn()).execute().body();
            } catch (ConsentRequiredException e) {
                userSession = e.getSession();
                throw e;
            } catch(IOException ioe) {
                throw new BridgeSDKException(ioe.getMessage(), ioe);
            }
            return userSession;
        }
        public void signOut() throws IOException {
            AuthenticationApi authApi = manager.getClient(AuthenticationApi.class);
            authApi.signOut(EMPTY_PAYLOAD).execute();
            userSession.setAuthenticated(false);
        }
        public void signOutAndDeleteUser() throws IOException {
            this.signOut();

            ClientManager adminManager = new ClientManager.Builder().withSignIn(ADMIN_SIGN_IN)
                    .withClientInfo(manager.getClientInfo()).build();
            ForAdminsApi adminsApi = adminManager.getClient(ForAdminsApi.class);
            adminsApi.deleteUser(userSession.getId()).execute();
        }
        public SignIn getSignIn() {
            return signIn;
        }
        public ClientManager getClientManager() {
            return manager;
        }
        public void setClientInfo(ClientInfo clientInfo) {
            ClientManager man = new ClientManager.Builder()
                    .withClientInfo(clientInfo)
                    .withSignIn(signIn)
                    .withConfig(manager.getConfig()).build();
            this.manager = man;
        }
    }
    public static TestUser getSignedInAdmin() {
        ClientManager adminManager = new ClientManager.Builder().withSignIn(ADMIN_SIGN_IN)
                .withClientInfo(CLIENT_INFO).build();
        TestUser adminUser = new TestUser(ADMIN_SIGN_IN, adminManager);
        adminUser.signInAgain();
        return adminUser;
    }

    public static TestUser createAndSignInUser(Class<?> cls, boolean consentUser, Role... roles) throws IOException {
        return new TestUserHelper.Builder(cls).withRoles(roles).withConsentUser(consentUser).createAndSignInUser();
    }
    public static TestUser createAndSignInUser(Class<?> cls, boolean consentUser, SignUp signUp) throws IOException {
        return new TestUserHelper.Builder(cls).withConsentUser(consentUser).withSignUp(signUp).createAndSignInUser();
    }
    
    public static class Builder {
        private Class<?> cls;
        private boolean consentUser;
        private SignUp signUp;
        private ClientInfo clientInfo;
        private Set<Role> roles = Sets.newHashSet();
        
        public Builder withConsentUser(boolean consentUser) {
            this.consentUser = consentUser;
            return this;
        }
        public Builder withSignUp(SignUp signUp) {
            this.signUp = signUp;
            return this;
        }
        public Builder withClientInfo(ClientInfo clientInfo) {
            this.clientInfo = clientInfo;
            return this;
        }
        public Builder withRoles(Role...roles) {
            for (Role role : roles) {
                this.roles.add(role);
            }
            return this;
        }
        
        public Builder(Class<?> cls) {
            checkNotNull(cls);
            this.cls = cls;
        }
        
        public TestUser createAndSignInUser() throws IOException {
            if (clientInfo == null) {
                clientInfo = CLIENT_INFO;
            }
            TestUser admin = getSignedInAdmin();
            ForAdminsApi adminsApi = admin.getClient(ForAdminsApi.class);
            
            Set<Role> rolesList = Sets.newHashSet();
            if (signUp != null && signUp.getRoles() != null) {
                rolesList.addAll(signUp.getRoles());
            }
            if (!roles.isEmpty()) {
                rolesList.addAll(roles);
            }

            // For email address, we don't want consent emails to bounce or SES will get mad at us. All test user email
            // addresses should be in the form bridge-testing+[semi-unique token]@sagebase.org. This directs all test
            // email to bridge-testing@sagebase.org.
            String emailAddress = Tests.makeEmail(cls);

            if (signUp == null) {
                signUp = new SignUp();
            }
            if (signUp.getEmail() == null) {
                signUp.email(emailAddress);
            }
            signUp.setStudy(Tests.TEST_KEY);
            signUp.setRoles(new ArrayList<>(rolesList));
            signUp.setPassword(PASSWORD);
            signUp.setConsent(consentUser);
            adminsApi.createUser(signUp).execute().body();    

            SignIn signIn = new SignIn().study(signUp.getStudy()).email(signUp.getEmail())
                    .password(signUp.getPassword());
            
            ClientManager manager = new ClientManager.Builder().withSignIn(signIn).withClientInfo(clientInfo).build();
            TestUser testUser = new TestUser(signIn, manager);

            UserSessionInfo userSession = null;
            try {
                try {
                    userSession = testUser.signInAgain();
                } catch (ConsentRequiredException e) {
                    userSession = e.getSession();
                    if (consentUser) {
                        // If there's no consent but we're expecting one, that's an error.
                        throw e;
                    }
                }
                return testUser;
            } catch (RuntimeException ex) {
                // Clean up the account, so we don't end up with a bunch of leftover accounts.
                if (userSession != null) {
                    adminsApi.deleteUser(userSession.getId()).execute();
                }
                throw new BridgeSDKException(ex.getMessage(), ex);
            }
        }
    }
}

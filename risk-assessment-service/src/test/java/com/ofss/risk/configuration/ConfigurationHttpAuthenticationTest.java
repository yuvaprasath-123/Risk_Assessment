package com.ofss.risk.configuration;

import com.ofss.internal.*;
import com.ofss.internal.verification.EphemeralCredentials;
import com.ofss.risk.submission.*;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Ephemeral issuer + actual receiver verifier at an in-memory HTTP boundary; not genuine issuer interoperability. */
class ConfigurationHttpAuthenticationTest {
    static final EphemeralCredentials KEYS=new EphemeralCredentials();
    ConfigurationHttpHarness http=new ConfigurationHttpHarness();String credential;
    BusinessConfigurationHttpAdapter adapter(){return new BusinessConfigurationHttpAdapter(new ConfigurationHttpSettings(URI.create("https://configuration.test"),Duration.ofSeconds(1),Duration.ofSeconds(1),65536),()->credential,http.client);}
    @BeforeEach void receiver(){var verifier=new InternalServiceVerifier(true,EphemeralCredentials.ISSUER,"configuration",KEYS.publicKeys(),
        "{\"risk-assessment-service\":[\"CONFIGURATION_ACTIVE_READ\",\"CONFIGURATION_VERSION_READ\"]}",ServicePermissions.CONFIGURATION);
        http.authorize=req->{try{var auth=verifier.authenticate(req.headers().firstValue("Authorization").orElseThrow().substring(7));
            String permission=req.uri().getPath().endsWith("/active")?ServicePermissions.CONFIG_ACTIVE:ServicePermissions.CONFIG_VERSION;
            return auth.getAuthorities().stream().anyMatch(a->a.getAuthority().equals(ServicePermissions.authority(permission)))?200:403;
        }catch(Exception e){return 401;}};
    }
    @Test void genuineServiceShapedEphemeralCredentialsAuthorizeActiveAndPinned(){credential=KEYS.service("configuration","risk-assessment-service",List.of(ServicePermissions.CONFIG_ACTIVE,ServicePermissions.CONFIG_VERSION));
        assertNotNull(adapter().resolve(null,BusinessConfigurationHttpAdapterTest.deadline()));http.body=ConfigurationHttpFixtures.response(false);assertNotNull(adapter().resolve("9007199254740993",BusinessConfigurationHttpAdapterTest.deadline()));}
    @Test void versionOnlyPermissionCannotReadActive(){credential=KEYS.service("configuration","risk-assessment-service",List.of(ServicePermissions.CONFIG_VERSION));
        assertThrows(SubmissionFailure.class,()->adapter().resolve(null,BusinessConfigurationHttpAdapterTest.deadline()));assertEquals(403,http.status);
        http.body=ConfigurationHttpFixtures.response(false);assertNotNull(adapter().resolve("9007199254740993",BusinessConfigurationHttpAdapterTest.deadline()));}
    @ParameterizedTest @ValueSource(strings={"wrong-key","issuer","expired","missing-sub","missing-exp","missing-type","missing-permissions","unknown-kid","audience","identity","user"})
    void receiverRejectsInvalidOrUserCredentialsWithoutFallback(String variation){credential=switch(variation){
        case "audience"->KEYS.service("risk","risk-assessment-service",List.of(ServicePermissions.CONFIG_ACTIVE));
        case "identity"->KEYS.service("configuration","untrusted-service",List.of(ServicePermissions.CONFIG_ACTIVE));case "user"->KEYS.user();
        default->KEYS.service("configuration","risk-assessment-service",List.of(ServicePermissions.CONFIG_ACTIVE),variation);};
        var e=assertThrows(SubmissionFailure.class,()->adapter().resolve(null,BusinessConfigurationHttpAdapterTest.deadline()));assertEquals(SubmissionFailure.Code.DEPENDENCY_UNAVAILABLE,e.code());assertNull(e.getCause());assertEquals(401,http.status);assertEquals(1,http.requests.size());
    }
    @Test void overlappingRotatedExternalCredentialsAccepted(){credential=KEYS.service("configuration","risk-assessment-service",List.of(ServicePermissions.CONFIG_ACTIVE),"next");assertNotNull(adapter().resolve(null,BusinessConfigurationHttpAdapterTest.deadline()));}
}

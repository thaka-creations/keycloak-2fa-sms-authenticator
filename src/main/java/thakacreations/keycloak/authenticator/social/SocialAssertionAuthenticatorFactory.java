package thakacreations.keycloak.authenticator.social;

import com.google.auto.service.AutoService;
import org.keycloak.Config;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.AuthenticatorFactory;
import org.keycloak.models.AuthenticationExecutionModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.ProviderConfigProperty;

import java.util.List;

/** Registers the social assertion authenticator for direct-grant flows. */
@AutoService(AuthenticatorFactory.class)
public class SocialAssertionAuthenticatorFactory implements AuthenticatorFactory {

	public static final String PROVIDER_ID = "social-assertion-authenticator";

	private static final SocialAssertionAuthenticator SINGLETON = new SocialAssertionAuthenticator();
	private static final AuthenticationExecutionModel.Requirement[] CHOICES = {
		AuthenticationExecutionModel.Requirement.ALTERNATIVE,
		AuthenticationExecutionModel.Requirement.DISABLED,
	};

	@Override
	public String getId() {
		return PROVIDER_ID;
	}

	@Override
	public String getDisplayType() {
		return "Social Sign-in Assertion";
	}

	@Override
	public String getHelpText() {
		return "Signs in the user named by a short-lived assertion from user-service after it verified a Google or Apple token.";
	}

	@Override
	public String getReferenceCategory() {
		return "social-assertion";
	}

	@Override
	public boolean isConfigurable() {
		return true;
	}

	@Override
	public boolean isUserSetupAllowed() {
		return false;
	}

	@Override
	public AuthenticationExecutionModel.Requirement[] getRequirementChoices() {
		return CHOICES;
	}

	@Override
	public List<ProviderConfigProperty> getConfigProperties() {
		return List.of(
			new ProviderConfigProperty(SocialAssertionConstants.SECRET, "Assertion secret", "Shared HS256 secret (32+ bytes); the same value user-service signs with.", ProviderConfigProperty.PASSWORD, null, true),
			new ProviderConfigProperty(SocialAssertionConstants.ISSUER, "Issuer", "Expected iss claim.", ProviderConfigProperty.STRING_TYPE, SocialAssertionConstants.DEFAULT_ISSUER),
			new ProviderConfigProperty(SocialAssertionConstants.MAX_AGE, "Max age (seconds)", "Longest allowed lifetime (exp - iat) of an assertion.", ProviderConfigProperty.STRING_TYPE, SocialAssertionConstants.DEFAULT_MAX_AGE)
		);
	}

	@Override
	public Authenticator create(KeycloakSession session) {
		return SINGLETON;
	}

	@Override
	public void init(Config.Scope config) {
	}

	@Override
	public void postInit(KeycloakSessionFactory factory) {
	}

	@Override
	public void close() {
	}
}

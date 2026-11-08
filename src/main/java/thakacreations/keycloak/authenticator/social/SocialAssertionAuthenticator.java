package thakacreations.keycloak.authenticator.social;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.Authenticator;
import org.keycloak.models.AuthenticatorConfigModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Direct-grant step that signs in the user named by a user-service assertion; with no assertion it steps aside so the
 * password + OTP branch runs.
 */
public class SocialAssertionAuthenticator implements Authenticator {

	private static final Logger LOG = Logger.getLogger(SocialAssertionAuthenticator.class);

	@Override
	public void authenticate(AuthenticationFlowContext context) {
		String assertion = context.getHttpRequest().getDecodedFormParameters().getFirst(SocialAssertionConstants.ASSERTION_PARAM);
		if (assertion == null || assertion.isBlank()) {
			context.attempted();
			return;
		}

		AuthenticatorConfigModel config = context.getAuthenticatorConfig();
		Map<String, String> cfg = config != null ? config.getConfig() : Map.of();
		String secret = cfg.getOrDefault(SocialAssertionConstants.SECRET, "");
		String issuer = cfg.getOrDefault(SocialAssertionConstants.ISSUER, SocialAssertionConstants.DEFAULT_ISSUER);
		long maxAge = Long.parseLong(cfg.getOrDefault(SocialAssertionConstants.MAX_AGE, SocialAssertionConstants.DEFAULT_MAX_AGE));
		RealmModel realm = context.getRealm();
		KeycloakSession session = context.getSession();
		long now = System.currentTimeMillis() / 1000;

		AssertionVerifier.Claims claims;
		try {
			claims = AssertionVerifier.verify(assertion, secret.getBytes(StandardCharsets.UTF_8), issuer, realm.getName(), maxAge, now);
		} catch (AssertionVerifier.InvalidAssertion e) {
			LOG.warnf("Rejected social assertion in realm %s: %s", realm.getName(), e.getMessage());
			reject(context, AuthenticationFlowError.INVALID_CREDENTIALS, "Invalid social sign-in assertion");
			return;
		}

		long ttl = Math.max(1, claims.expiresAt() - now + 60);
		if (!session.singleUseObjects().putIfAbsent("social-assertion:" + realm.getId() + ":" + claims.jti(), ttl)) {
			LOG.warnf("Replayed social assertion in realm %s", realm.getName());
			reject(context, AuthenticationFlowError.INVALID_CREDENTIALS, "Social sign-in assertion already used");
			return;
		}

		UserModel user = session.users().getUserById(realm, claims.subject());
		if (user == null) {
			reject(context, AuthenticationFlowError.INVALID_USER, "Unknown user");
			return;
		}
		if (!user.isEnabled()) {
			reject(context, AuthenticationFlowError.USER_DISABLED, "Account is disabled");
			return;
		}
		context.setUser(user);
		context.success();
	}

	private void reject(AuthenticationFlowContext context, AuthenticationFlowError error, String description) {
		Response response = Response.status(Response.Status.UNAUTHORIZED)
			.type(MediaType.APPLICATION_JSON)
			.entity(Map.of("error", "invalid_grant", "error_description", description))
			.build();
		context.failure(error, response);
	}

	@Override
	public void action(AuthenticationFlowContext context) {
		authenticate(context);
	}

	@Override
	public boolean requiresUser() {
		return false;
	}

	@Override
	public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
		return true;
	}

	@Override
	public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {
	}

	@Override
	public void close() {
	}
}

package thakacreations.keycloak.authenticator;

import thakacreations.keycloak.authenticator.gateway.SmsServiceFactory;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.Authenticator;
import org.keycloak.common.util.SecretGenerator;
import org.keycloak.models.AuthenticationExecutionModel;
import org.keycloak.models.AuthenticatorConfigModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.sessions.AuthenticationSessionModel;
import org.keycloak.theme.Theme;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * @author Abdul Nelfrank, https://thaka-creations.github.io/portfolio/, @abdulnelfrank
 * Modified to support both Browser and Direct Grant flows
 */
public class SmsAuthenticator implements Authenticator {

	private static final String MOBILE_NUMBER_FIELD = "attributes.phone_number";
	private static final String TPL_CODE = "login-sms.ftl";

	// UserModel attribute keys — stored in Postgres, shared across all pods
	private static final String ATTR_OTP_CODE   = "_sms_otp_code";
	private static final String ATTR_OTP_EXPIRY = "_sms_otp_expiry";
	private static final String ATTR_OTP_ATTEMPTS = "_sms_otp_attempts";

	@Override
	public void authenticate(AuthenticationFlowContext context) {
		String providedOtp = context.getHttpRequest().getDecodedFormParameters().getFirst(SmsConstants.OTP);

		if (providedOtp != null) {
			validateOtp(context, providedOtp);
			return;
		}

		AuthenticatorConfigModel config = context.getAuthenticatorConfig();
		KeycloakSession session = context.getSession();
		UserModel user = context.getUser();

		String mobileNumber = user.getFirstAttribute(MOBILE_NUMBER_FIELD);
		boolean hasMobileNumber = mobileNumber != null && !mobileNumber.trim().isEmpty();

		Map<String, String> configMap = config != null ? config.getConfig() : new HashMap<>();
		int length = Integer.parseInt(configMap.getOrDefault(SmsConstants.CODE_LENGTH, "6"));
		int ttl = Integer.parseInt(configMap.getOrDefault(SmsConstants.CODE_TTL, "300"));
		boolean isSimulationMode = Boolean.parseBoolean(configMap.getOrDefault(SmsConstants.SIMULATION_MODE, "true"));
		boolean canSendSms = !isSimulationMode && hasMobileNumber;

		String code = SecretGenerator.getInstance().randomString(length, SecretGenerator.DIGITS);
		AuthenticationSessionModel authSession = context.getAuthenticationSession();
		long expiryTime = System.currentTimeMillis() + (ttl * 1000L);

		// Store in auth session notes (browser flow — same session across requests)
		authSession.setAuthNote(SmsConstants.CODE, code);
		authSession.setAuthNote(SmsConstants.CODE_TTL, Long.toString(expiryTime));

		// Store in UserModel attributes (direct grant flow — different auth sessions,
		// but same user; attributes are Postgres-backed and visible to all pods)
		user.setSingleAttribute(ATTR_OTP_CODE, code);
		user.setSingleAttribute(ATTR_OTP_EXPIRY, Long.toString(expiryTime));
		user.removeAttribute(ATTR_OTP_ATTEMPTS);

		boolean isDirectGrant = isDirectGrantFlow(context);

		try {
			if (canSendSms) {
				Theme theme = session.theme().getTheme(Theme.Type.LOGIN);
				Locale locale = session.getContext().resolveLocale(user);
				String smsAuthText = theme.getMessages(locale).getProperty("smsAuthText");
				String smsText = String.format(smsAuthText, code, Math.floorDiv(ttl, 60));
				SmsServiceFactory.get(configMap).send(mobileNumber, smsText);
			}

			if (isDirectGrant) {
				Map<String, Object> responseData = new HashMap<>();
				responseData.put("status", "otp_required");
				responseData.put("message", canSendSms ? "OTP sent to your phone" : "OTP verification required");
				responseData.put("expires_in", ttl);
				if (mayRelayOtp(context, configMap)) {
					responseData.put("otp", code);
					responseData.put("email", user.getEmail());
				}

				Response response = Response.status(Response.Status.OK)
					.type(MediaType.APPLICATION_JSON)
					.entity(responseData)
					.build();

				// Intermediate "OTP required" step — must be a CHALLENGE, not a
				// failure. Using context.failure(INVALID_CREDENTIALS, ...) here
				// makes Keycloak's brute-force protector count every OTP request
				// as a failed login, so accounts lock after `failureFactor`
				// logins (returning "Invalid user credentials" on both login and
				// verify). A challenge returns the same JSON body without tripping
				// brute force.
				context.challenge(response);
			} else {
				context.challenge(context.form().setAttribute("realm", context.getRealm()).createForm(TPL_CODE));
			}
		} catch (Exception e) {
			if (isDirectGrant) {
				Map<String, Object> errorData = new HashMap<>();
				errorData.put("error", "sms_send_failed");
				errorData.put("message", e.getMessage());

				Response response = Response.status(Response.Status.INTERNAL_SERVER_ERROR)
					.type(MediaType.APPLICATION_JSON)
					.entity(errorData)
					.build();

				context.failure(AuthenticationFlowError.INTERNAL_ERROR, response);
			} else {
				context.failureChallenge(AuthenticationFlowError.INTERNAL_ERROR,
					context.form().setError("smsAuthSmsNotSent", e.getMessage())
						.createErrorPage(Response.Status.INTERNAL_SERVER_ERROR));
			}
		}
	}

	@Override
	public void action(AuthenticationFlowContext context) {
		String enteredCode = context.getHttpRequest().getDecodedFormParameters().getFirst(SmsConstants.CODE);
		validateOtp(context, enteredCode);
	}

	private void validateOtp(AuthenticationFlowContext context, String enteredCode) {
		AuthenticationSessionModel authSession = context.getAuthenticationSession();
		UserModel user = context.getUser();
		boolean isDirectGrant = isDirectGrantFlow(context);

		String code = null;
		Long expiryTime = null;

		// Primary: auth session notes (browser flow — same auth session)
		String sessionCode = authSession.getAuthNote(SmsConstants.CODE);
		String sessionTtl  = authSession.getAuthNote(SmsConstants.CODE_TTL);

		if (sessionCode != null && sessionTtl != null) {
			code       = sessionCode;
			expiryTime = Long.parseLong(sessionTtl);
		} else {
			// Fallback: UserModel attributes (direct grant flow — different auth sessions,
			// same user record in Postgres, consistent across all pods)
			String attrCode   = user.getFirstAttribute(ATTR_OTP_CODE);
			String attrExpiry = user.getFirstAttribute(ATTR_OTP_EXPIRY);

			if (attrCode != null && attrExpiry != null) {
				code       = attrCode;
				expiryTime = Long.parseLong(attrExpiry);
			}
		}

		if (code == null || expiryTime == null) {
			if (isDirectGrant) {
				Map<String, Object> errorData = new HashMap<>();
				errorData.put("error", "invalid_request");
				errorData.put("message", "No OTP session found");

				Response response = Response.status(Response.Status.BAD_REQUEST)
					.type(MediaType.APPLICATION_JSON)
					.entity(errorData)
					.build();

				context.failure(AuthenticationFlowError.INTERNAL_ERROR, response);
			} else {
				context.failureChallenge(AuthenticationFlowError.INTERNAL_ERROR,
					context.form().createErrorPage(Response.Status.INTERNAL_SERVER_ERROR));
			}
			return;
		}

		boolean isValid = enteredCode != null && enteredCode.equals(code);
		if (isValid) {
			if (expiryTime < System.currentTimeMillis()) {
				if (isDirectGrant) {
					Map<String, Object> errorData = new HashMap<>();
					errorData.put("error", "otp_expired");
					errorData.put("message", "OTP has expired");

					Response response = Response.status(Response.Status.BAD_REQUEST)
						.type(MediaType.APPLICATION_JSON)
						.entity(errorData)
						.build();

					context.failure(AuthenticationFlowError.EXPIRED_CODE, response);
				} else {
					context.failureChallenge(AuthenticationFlowError.EXPIRED_CODE,
						context.form().setError("smsAuthCodeExpired").createErrorPage(Response.Status.BAD_REQUEST));
				}
			} else {
				// Clear auth session notes
				authSession.removeAuthNote(SmsConstants.CODE);
				authSession.removeAuthNote(SmsConstants.CODE_TTL);

				// Clear UserModel attributes (single-use — prevents OTP reuse across pods)
				user.removeAttribute(ATTR_OTP_CODE);
				user.removeAttribute(ATTR_OTP_EXPIRY);
				user.removeAttribute(ATTR_OTP_ATTEMPTS);

				context.success();
			}
		} else {
			if (recordFailedAttempt(context, user)) {
				authSession.removeAuthNote(SmsConstants.CODE);
				authSession.removeAuthNote(SmsConstants.CODE_TTL);
			}
			if (isDirectGrant) {
				Map<String, Object> errorData = new HashMap<>();
				errorData.put("error", "invalid_otp");
				errorData.put("message", "Invalid OTP code");

				Response response = Response.status(Response.Status.UNAUTHORIZED)
					.type(MediaType.APPLICATION_JSON)
					.entity(errorData)
					.build();

				context.failure(AuthenticationFlowError.INVALID_CREDENTIALS, response);
			} else {
				AuthenticationExecutionModel execution = context.getExecution();
				if (execution.isRequired()) {
					context.failureChallenge(AuthenticationFlowError.INVALID_CREDENTIALS,
						context.form().setAttribute("realm", context.getRealm())
							.setError("smsAuthCodeInvalid").createForm(TPL_CODE));
				} else if (execution.isConditional() || execution.isAlternative()) {
					context.attempted();
				}
			}
		}
	}

	/** The OTP goes back in the response only to a caller holding the relay secret; with no secret configured, to any caller (legacy). */
	static boolean mayRelayOtp(AuthenticationFlowContext context, Map<String, String> configMap) {
		String secret = configMap.get(SmsConstants.OTP_RELAY_SECRET);
		if (secret == null || secret.isBlank()) {
			return true;
		}
		String presented = context.getHttpRequest().getHttpHeaders().getHeaderString(SmsConstants.OTP_RELAY_HEADER);
		return presented != null && MessageDigest.isEqual(
			presented.getBytes(StandardCharsets.UTF_8), secret.getBytes(StandardCharsets.UTF_8));
	}

	/** Counts a wrong code; after maxAttempts the code is discarded so the user must request a new one. Returns true when discarded. */
	private boolean recordFailedAttempt(AuthenticationFlowContext context, UserModel user) {
		AuthenticatorConfigModel config = context.getAuthenticatorConfig();
		Map<String, String> configMap = config != null ? config.getConfig() : new HashMap<>();
		int maxAttempts = Integer.parseInt(configMap.getOrDefault(SmsConstants.MAX_ATTEMPTS, "5"));
		String current = user.getFirstAttribute(ATTR_OTP_ATTEMPTS);
		int attempts = (current == null ? 0 : Integer.parseInt(current)) + 1;
		if (attempts >= maxAttempts) {
			user.removeAttribute(ATTR_OTP_CODE);
			user.removeAttribute(ATTR_OTP_EXPIRY);
			user.removeAttribute(ATTR_OTP_ATTEMPTS);
			return true;
		}
		user.setSingleAttribute(ATTR_OTP_ATTEMPTS, Integer.toString(attempts));
		return false;
	}

	private boolean isDirectGrantFlow(AuthenticationFlowContext context) {
		String requestUri = context.getUriInfo().getRequestUri().getPath();
		if (requestUri.contains("/token")) {
			return true;
		}

		String acceptHeader = context.getHttpRequest().getHttpHeaders().getHeaderString("Accept");
		if (acceptHeader != null && acceptHeader.contains(MediaType.APPLICATION_JSON)
				&& !acceptHeader.contains(MediaType.TEXT_HTML)) {
			return true;
		}

		return false;
	}

	@Override
	public boolean requiresUser() {
		return true;
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

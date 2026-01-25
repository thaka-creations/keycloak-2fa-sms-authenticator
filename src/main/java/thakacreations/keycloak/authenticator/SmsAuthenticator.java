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

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * @author Abdul Nelfrank, https://thaka-creations.github.io/portfolio/, @abdulnelfrank
 * Modified to support both Browser and Direct Grant flows
 */
public class SmsAuthenticator implements Authenticator {

	private static final String MOBILE_NUMBER_FIELD = "mobile_number";
	private static final String TPL_CODE = "login-sms.ftl";

	@Override
	public void authenticate(AuthenticationFlowContext context) {
		// Check if OTP is provided in the request (Direct Grant second attempt)
		String providedOtp = context.getHttpRequest().getDecodedFormParameters().getFirst(SmsConstants.OTP);
		
		if (providedOtp != null) {
			// OTP provided - validate it
			validateOtp(context, providedOtp);
			return;
		}

	// No OTP provided - generate and send it
	AuthenticatorConfigModel config = context.getAuthenticatorConfig();
	KeycloakSession session = context.getSession();
	UserModel user = context.getUser();

	String mobileNumber = user.getFirstAttribute(MOBILE_NUMBER_FIELD);
	// mobileNumber of course has to be further validated on proper format, country code, ...

	// Get configuration with defaults if config is not set
	Map<String, String> configMap = config != null ? config.getConfig() : new HashMap<>();
	int length = Integer.parseInt(configMap.getOrDefault(SmsConstants.CODE_LENGTH, "6"));
	int ttl = Integer.parseInt(configMap.getOrDefault(SmsConstants.CODE_TTL, "300"));
	boolean isSimulationMode = Boolean.parseBoolean(configMap.getOrDefault(SmsConstants.SIMULATION_MODE, "true"));

	String code = SecretGenerator.getInstance().randomString(length, SecretGenerator.DIGITS);
	AuthenticationSessionModel authSession = context.getAuthenticationSession();
	authSession.setAuthNote(SmsConstants.CODE, code);
	authSession.setAuthNote(SmsConstants.CODE_TTL, Long.toString(System.currentTimeMillis() + (ttl * 1000L)));
	
	// Also store in user session for Direct Grant persistence
	authSession.setUserSessionNote(SmsConstants.CODE, code);
	authSession.setUserSessionNote(SmsConstants.CODE_TTL, Long.toString(System.currentTimeMillis() + (ttl * 1000L)));

	boolean isDirectGrant = isDirectGrantFlow(context);

		try {
			// Send SMS in production mode
			if (!isSimulationMode) {
				Theme theme = session.theme().getTheme(Theme.Type.LOGIN);
				Locale locale = session.getContext().resolveLocale(user);
			String smsAuthText = theme.getMessages(locale).getProperty("smsAuthText");
			String smsText = String.format(smsAuthText, code, Math.floorDiv(ttl, 60));

			SmsServiceFactory.get(configMap).send(mobileNumber, smsText);
			}

		// Return appropriate response based on flow type
		if (isDirectGrant) {
			// Direct Grant flow - return JSON response with 200 OK for successful OTP generation
			Map<String, Object> responseData = new HashMap<>();
			responseData.put("status", "otp_required");
			responseData.put("message", isSimulationMode ? "OTP verification required" : "OTP sent to your phone");
			responseData.put("expires_in", ttl);
			
			// Include OTP in response only in simulation mode
			if (isSimulationMode) {
				responseData.put("otp", code);
			}

			Response response = Response.status(Response.Status.OK)
				.type(MediaType.APPLICATION_JSON)
				.entity(responseData)
				.build();
			
			context.failure(AuthenticationFlowError.INVALID_CREDENTIALS, response);
			} else {
				// Browser flow - show form
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
		// This is called for browser flow form submission
		String enteredCode = context.getHttpRequest().getDecodedFormParameters().getFirst(SmsConstants.CODE);
		validateOtp(context, enteredCode);
	}

	private void validateOtp(AuthenticationFlowContext context, String enteredCode) {
		AuthenticationSessionModel authSession = context.getAuthenticationSession();
		
		// Try to get from auth notes first, then from user session notes (for Direct Grant)
		String code = authSession.getAuthNote(SmsConstants.CODE);
		String ttl = authSession.getAuthNote(SmsConstants.CODE_TTL);
		
		if (code == null || ttl == null) {
			// Fallback to user session notes for Direct Grant flow
			code = authSession.getUserSessionNotes().get(SmsConstants.CODE);
			ttl = authSession.getUserSessionNotes().get(SmsConstants.CODE_TTL);
		}
		
		boolean isDirectGrant = isDirectGrantFlow(context);

		if (code == null || ttl == null) {
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
			if (Long.parseLong(ttl) < System.currentTimeMillis()) {
				// expired
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
				// valid - clear the OTP to prevent reuse
				authSession.removeAuthNote(SmsConstants.CODE);
				authSession.removeAuthNote(SmsConstants.CODE_TTL);
				// User session notes will be cleared when authentication completes
				context.success();
			}
		} else {
			// invalid
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

	/**
	 * Detects if the current request is a Direct Grant flow
	 * by checking the request path (token endpoint) or content type expectations
	 */
	private boolean isDirectGrantFlow(AuthenticationFlowContext context) {
		// Check if the request URI contains the token endpoint
		String requestUri = context.getUriInfo().getRequestUri().getPath();
		if (requestUri.contains("/token")) {
			return true;
		}

		// Check Accept header - Direct Grant clients typically expect JSON
		String acceptHeader = context.getHttpRequest().getHttpHeaders().getHeaderString("Accept");
		if (acceptHeader != null && acceptHeader.contains(MediaType.APPLICATION_JSON) 
				&& !acceptHeader.contains(MediaType.TEXT_HTML)) {
			return true;
		}

		// Default to browser flow
		return false;
	}

	@Override
	public boolean requiresUser() {
		return true;
	}

	@Override
	public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
		return user.getFirstAttribute(MOBILE_NUMBER_FIELD) != null;
	}

	@Override
	public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {
		// this will only work if you have the required action from here configured:
		// https://github.com/dasniko/keycloak-extensions-demo/tree/main/requiredaction
		user.addRequiredAction("mobile-number-ra");
	}

	@Override
	public void close() {
	}

}

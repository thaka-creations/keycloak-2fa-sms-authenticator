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
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author Abdul Nelfrank, https://thaka-creations.github.io/portfolio/, @abdulnelfrank
 * Modified to support both Browser and Direct Grant flows
 */
public class SmsAuthenticator implements Authenticator {

	private static final String MOBILE_NUMBER_FIELD = "attributes.phone_number";
	private static final String TPL_CODE = "login-sms.ftl";
	
	// In-memory cache for OTP storage (for Direct Grant flow)
	// Key format: "realmId:username"
	private static final Map<String, OtpData> OTP_CACHE = new ConcurrentHashMap<>();
	
	private static class OtpData {
		String code;
		long expiryTime;
		
		OtpData(String code, long expiryTime) {
			this.code = code;
			this.expiryTime = expiryTime;
		}
		
		boolean isExpired() {
			return System.currentTimeMillis() > expiryTime;
		}
	}

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
	long expiryTime = System.currentTimeMillis() + (ttl * 1000L);
	
	// Store in session for browser flows
	authSession.setAuthNote(SmsConstants.CODE, code);
	authSession.setAuthNote(SmsConstants.CODE_TTL, Long.toString(expiryTime));
	
	// Store in cache for Direct Grant flows (persistent across requests)
	String cacheKey = getCacheKey(context);
	OTP_CACHE.put(cacheKey, new OtpData(code, expiryTime));
	
	// Clean up expired entries periodically
	cleanExpiredEntries();

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
		boolean isDirectGrant = isDirectGrantFlow(context);
		
		String code = null;
		Long expiryTime = null;
		
		// Try to get from auth notes first (browser flow)
		code = authSession.getAuthNote(SmsConstants.CODE);
		String ttlStr = authSession.getAuthNote(SmsConstants.CODE_TTL);
		
		if (code != null && ttlStr != null) {
			expiryTime = Long.parseLong(ttlStr);
		} else {
			// Fallback to cache for Direct Grant flow
			String cacheKey = getCacheKey(context);
			OtpData otpData = OTP_CACHE.get(cacheKey);
			
			if (otpData != null) {
				code = otpData.code;
				expiryTime = otpData.expiryTime;
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
				
				// Clear from cache as well
				String cacheKey = getCacheKey(context);
				OTP_CACHE.remove(cacheKey);
				
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

	/**
	 * Generate cache key for OTP storage
	 * Format: realmId:username
	 */
	private String getCacheKey(AuthenticationFlowContext context) {
		String realmId = context.getRealm().getId();
		String username = context.getUser().getUsername();
		return realmId + ":" + username;
	}
	
	/**
	 * Clean up expired OTP entries from cache
	 */
	private void cleanExpiredEntries() {
		long now = System.currentTimeMillis();
		OTP_CACHE.entrySet().removeIf(entry -> entry.getValue().isExpired());
	}

	@Override
	public void close() {
	}

}

package thakacreations.keycloak.authenticator;

import lombok.experimental.UtilityClass;

@UtilityClass
public class SmsConstants {
	public static final String CODE = "code";
	public static final String CODE_LENGTH = "length";
	public static final String CODE_TTL = "ttl";
	public static final String SENDER_ID = "senderId";
	public static final String SIMULATION_MODE = "simulation";
	public static final String OTP = "otp";
	public static final String OTP_RELAY_SECRET = "otpRelaySecret";
	public static final String OTP_RELAY_HEADER = "X-OTP-Relay-Secret";
	public static final String MAX_ATTEMPTS = "maxAttempts";
}

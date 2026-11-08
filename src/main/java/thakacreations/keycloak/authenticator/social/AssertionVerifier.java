package thakacreations.keycloak.authenticator.social;

import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;

/**
 * Verifies the short-lived HS256 assertion user-service sends after it has checked a Google or Apple identity token.
 */
public final class AssertionVerifier {

	private static final ObjectMapper JSON = new ObjectMapper();
	private static final long CLOCK_SKEW_SECONDS = 30;

	/** Claims of a verified assertion. */
	public record Claims(String subject, String jti, long expiresAt) {}

	/** Thrown when the assertion is malformed, forged, expired or meant for someone else. */
	public static final class InvalidAssertion extends Exception {
		public InvalidAssertion(String message) {
			super(message);
		}
	}

	private AssertionVerifier() {}

	/** Returns the claims when signature, issuer, audience and lifetime are all valid. */
	public static Claims verify(String token, byte[] secret, String issuer, String audience, long maxAgeSeconds, long nowSeconds)
			throws InvalidAssertion {
		if (secret == null || secret.length < 32) {
			throw new InvalidAssertion("assertion secret is not configured");
		}
		String[] parts = token.split("\\.", -1);
		if (parts.length != 3) {
			throw new InvalidAssertion("malformed assertion");
		}
		Map<String, Object> header = decode(parts[0]);
		if (!"HS256".equals(header.get("alg"))) {
			throw new InvalidAssertion("unsupported algorithm");
		}
		byte[] expected = hmac(secret, parts[0] + "." + parts[1]);
		byte[] actual;
		try {
			actual = Base64.getUrlDecoder().decode(parts[2]);
		} catch (IllegalArgumentException e) {
			throw new InvalidAssertion("malformed signature");
		}
		if (!MessageDigest.isEqual(expected, actual)) {
			throw new InvalidAssertion("bad signature");
		}
		Map<String, Object> claims = decode(parts[1]);
		if (!issuer.equals(claims.get("iss"))) {
			throw new InvalidAssertion("wrong issuer");
		}
		if (!audience.equals(claims.get("aud"))) {
			throw new InvalidAssertion("wrong audience");
		}
		long iat = number(claims, "iat");
		long exp = number(claims, "exp");
		if (exp <= nowSeconds || iat > nowSeconds + CLOCK_SKEW_SECONDS || exp - iat > maxAgeSeconds) {
			throw new InvalidAssertion("expired or too long-lived");
		}
		String sub = text(claims, "sub");
		String jti = text(claims, "jti");
		return new Claims(sub, jti, exp);
	}

	private static Map<String, Object> decode(String part) throws InvalidAssertion {
		try {
			@SuppressWarnings("unchecked")
			Map<String, Object> map = JSON.readValue(Base64.getUrlDecoder().decode(part), Map.class);
			return map;
		} catch (Exception e) {
			throw new InvalidAssertion("malformed assertion");
		}
	}

	private static byte[] hmac(byte[] secret, String data) throws InvalidAssertion {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret, "HmacSHA256"));
			return mac.doFinal(data.getBytes(StandardCharsets.US_ASCII));
		} catch (Exception e) {
			throw new InvalidAssertion("signature check failed");
		}
	}

	private static long number(Map<String, Object> claims, String name) throws InvalidAssertion {
		if (claims.get(name) instanceof Number n) {
			return n.longValue();
		}
		throw new InvalidAssertion("missing " + name);
	}

	private static String text(Map<String, Object> claims, String name) throws InvalidAssertion {
		if (claims.get(name) instanceof String s && !s.isBlank()) {
			return s;
		}
		throw new InvalidAssertion("missing " + name);
	}
}

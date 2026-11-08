package thakacreations.keycloak.authenticator.social;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AssertionVerifierTest {

	private static final byte[] SECRET = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
	private static final long NOW = 1_800_000_000L;

	private static String b64(String s) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
	}

	private static String sign(byte[] secret, String header, String payload) throws Exception {
		String signingInput = b64(header) + "." + b64(payload);
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(secret, "HmacSHA256"));
		return signingInput + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(signingInput.getBytes(StandardCharsets.US_ASCII)));
	}

	private static String token(String payload) throws Exception {
		return sign(SECRET, "{\"alg\":\"HS256\",\"typ\":\"JWT\"}", payload);
	}

	private static String claims(String iss, String aud, long iat, long exp) {
		return String.format("{\"iss\":\"%s\",\"aud\":\"%s\",\"sub\":\"kc-1\",\"jti\":\"j1\",\"iat\":%d,\"exp\":%d}", iss, aud, iat, exp);
	}

	private static AssertionVerifier.Claims verify(String token) throws Exception {
		return AssertionVerifier.verify(token, SECRET, "user-service", "capedigi", 60, NOW);
	}

	@Test
	void acceptsAValidAssertion() throws Exception {
		AssertionVerifier.Claims c = verify(token(claims("user-service", "capedigi", NOW, NOW + 60)));
		assertEquals("kc-1", c.subject());
		assertEquals("j1", c.jti());
	}

	@Test
	void rejectsForgedSignature() throws Exception {
		String forged = sign("another-secret-another-secret-xx".getBytes(StandardCharsets.UTF_8),
			"{\"alg\":\"HS256\"}", claims("user-service", "capedigi", NOW, NOW + 60));
		assertThrows(AssertionVerifier.InvalidAssertion.class, () -> verify(forged));
	}

	@Test
	void rejectsNoneAlgorithmAndMalformedTokens() {
		String none = b64("{\"alg\":\"none\"}") + "." + b64(claims("user-service", "capedigi", NOW, NOW + 60)) + ".";
		assertThrows(AssertionVerifier.InvalidAssertion.class, () -> verify(none));
		assertThrows(AssertionVerifier.InvalidAssertion.class, () -> verify("not-a-jwt"));
	}

	@Test
	void rejectsWrongIssuerOrRealm() {
		assertThrows(AssertionVerifier.InvalidAssertion.class, () -> verify(token(claims("someone", "capedigi", NOW, NOW + 60))));
		assertThrows(AssertionVerifier.InvalidAssertion.class, () -> verify(token(claims("user-service", "capemedia", NOW, NOW + 60))));
	}

	@Test
	void rejectsExpiredFutureAndLongLivedAssertions() {
		assertThrows(AssertionVerifier.InvalidAssertion.class, () -> verify(token(claims("user-service", "capedigi", NOW - 120, NOW - 60))));
		assertThrows(AssertionVerifier.InvalidAssertion.class, () -> verify(token(claims("user-service", "capedigi", NOW + 600, NOW + 640))));
		assertThrows(AssertionVerifier.InvalidAssertion.class, () -> verify(token(claims("user-service", "capedigi", NOW, NOW + 3600))));
	}

	@Test
	void refusesShortOrMissingSecret() {
		assertThrows(AssertionVerifier.InvalidAssertion.class,
			() -> AssertionVerifier.verify(token(claims("user-service", "capedigi", NOW, NOW + 60)), "short".getBytes(), "user-service", "capedigi", 60, NOW));
	}
}

package thakacreations.keycloak.authenticator;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmsAuthenticatorRelayTest {

	@Test
	void relaySecretMustBeConfiguredAndPresented() {
		assertTrue(SmsAuthenticator.relaySecretMatches("s3cret", "s3cret"));
		assertFalse(SmsAuthenticator.relaySecretMatches("s3cret", "wrong"));
		assertFalse(SmsAuthenticator.relaySecretMatches("s3cret", null));
		assertFalse(SmsAuthenticator.relaySecretMatches(null, "anything"));
		assertFalse(SmsAuthenticator.relaySecretMatches(" ", " "));
	}

	@Test
	void legacyRelayWithoutSecretButNeverToWrongSecret() {
		assertTrue(SmsAuthenticator.mayRelay(null, null));
		assertTrue(SmsAuthenticator.mayRelay("", null));
		assertTrue(SmsAuthenticator.mayRelay("s3cret", "s3cret"));
		assertFalse(SmsAuthenticator.mayRelay("s3cret", null));
		assertFalse(SmsAuthenticator.mayRelay("s3cret", "wrong"));
	}

	@Test
	void keycloakDoesNotTextWhenUserServiceTookOverDelivery() {
		assertFalse(SmsAuthenticator.shouldSendSmsFromKeycloak(true, true));
		assertTrue(SmsAuthenticator.shouldSendSmsFromKeycloak(true, false));
		assertFalse(SmsAuthenticator.shouldSendSmsFromKeycloak(false, false));
	}

	@Test
	void relayFieldsIncludeUserIdAndPhone() {
		Map<String, Object> fields = SmsAuthenticator.relayFields("123456", "a@x.test", "u-1", " +254700000001 ");
		assertEquals("123456", fields.get("otp"));
		assertEquals("a@x.test", fields.get("email"));
		assertEquals("u-1", fields.get("user_id"));
		assertEquals("+254700000001", fields.get("phone_number"));
	}

	@Test
	void relayFieldsOmitBlankOptionalValues() {
		Map<String, Object> fields = SmsAuthenticator.relayFields("123456", "a@x.test", " ", null);
		assertFalse(fields.containsKey("user_id"));
		assertFalse(fields.containsKey("phone_number"));
		assertEquals(2, fields.size());
	}
}

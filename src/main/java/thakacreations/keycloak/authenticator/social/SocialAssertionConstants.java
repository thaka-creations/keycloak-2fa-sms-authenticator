package thakacreations.keycloak.authenticator.social;

/** Config keys and defaults for the social assertion authenticator. */
public final class SocialAssertionConstants {
	public static final String ASSERTION_PARAM = "assertion";
	public static final String SECRET = "assertionSecret";
	public static final String ISSUER = "issuer";
	public static final String MAX_AGE = "maxAgeSeconds";
	public static final String DEFAULT_ISSUER = "user-service";
	public static final String DEFAULT_MAX_AGE = "60";

	private SocialAssertionConstants() {}
}

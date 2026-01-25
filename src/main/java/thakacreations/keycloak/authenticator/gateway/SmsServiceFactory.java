package thakacreations.keycloak.authenticator.gateway;

import thakacreations.keycloak.authenticator.SmsConstants;
import org.jboss.logging.Logger;

import java.util.Map;

/**
 * @author Abdul Nelfrank, https://thaka-creations.github.io/portfolio/, @abdulnelfrank
 */
public class SmsServiceFactory {

	private static final Logger log = Logger.getLogger(SmsServiceFactory.class);

	public static SmsService get(Map<String, String> config) {
		if (Boolean.parseBoolean(config.getOrDefault(SmsConstants.SIMULATION_MODE, "false"))) {
			return (phoneNumber, message) ->
				log.warnf("***** SIMULATION MODE ***** Would send SMS to %s with text: %s", phoneNumber, message);
		} else {
			return new AwsSmsService(config);
		}
	}

}

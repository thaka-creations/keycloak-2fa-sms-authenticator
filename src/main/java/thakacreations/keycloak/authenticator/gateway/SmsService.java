package thakacreations.keycloak.authenticator.gateway;

import java.util.Map;

/**
 * @author Abdul Nelfrank, https://thaka-creations.github.io/portfolio/, @abdulnelfrank
 */
public interface SmsService {

	void send(String phoneNumber, String message);

}

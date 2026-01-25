# Keycloak 2FA SMS Authenticator

A Keycloak Authentication SPI implementation providing a second-factor authentication step using a one-time password (OTP) sent via SMS (leveraging AWS SNS).

---

## Features

- **2FA for Keycloak**: Adds SMS OTP step after username/password login.
- **Direct Grant Support**: Works with [Direct Grant (Resource Owner Password Credentials) flow](DIRECT_GRANT_GUIDE.md). No extra setup required.
- **Simulation Mode**: Returns OTP in the API response for easy testing.
- **Configurable OTP Expiry**: OTPs expire after a set time (default: 5 minutes).
- **Single-use OTP**: Each OTP can be used only once.

---

## Quick Start

> Detailed setup and configuration instructions are provided in the [Quick Reference](QUICK_REFERENCE.md) and [Direct Grant Guide](DIRECT_GRANT_GUIDE.md).

1. **Build**
    ```sh
    mvn clean package
    ```

2. **Copy JAR**
   Copy the plugin JAR from `target/` to your Keycloak `standalone/deployments/`.

3. **Configure the Authenticator**
   - In the Keycloak Admin Console, add the SMS 2FA authenticator to your authentication flows.
   - Set required environment variables or system properties for AWS SNS credentials and simulation/production mode.

4. **Test**
   - Login with a test user. After password, the system sends (or returns in simulation) an OTP which must be entered to complete authentication.

---

## Resources

- [Quick Reference](QUICK_REFERENCE.md)
- [Direct Grant Guide](DIRECT_GRANT_GUIDE.md)

---

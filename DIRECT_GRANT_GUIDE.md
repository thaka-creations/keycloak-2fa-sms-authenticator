# Direct Grant Flow Support for SMS 2FA Authenticator

## Overview

This authenticator now supports **both Browser-based and Direct Grant (Resource Owner Password Credentials) flows** with automatic detection. No configuration is required - the authenticator automatically detects which flow is being used and responds accordingly.

## Key Features

- ✅ **Auto-detection**: Automatically detects Browser vs Direct Grant flow
- ✅ **Two-step authentication**: Username/password → OTP generation → OTP verification → tokens
- ✅ **Simulation mode**: Returns OTP in response for testing (production mode sends SMS)
- ✅ **Security**: OTP is single-use and expires after configured TTL
- ✅ **JSON responses**: Proper error messages for Direct Grant clients

## How It Works

### Direct Grant Flow (Two Requests)

#### Request 1: Initial Authentication (Triggers OTP)
```bash
POST /realms/{realm}/protocol/openid-connect/token
Content-Type: application/x-www-form-urlencoded

grant_type=password&username={username}&password={password}&client_id={client_id}
```

**Response (200 OK):**

**Simulation Mode:**
```json
{
  "status": "otp_required",
  "message": "OTP verification required",
  "otp": "123456",
  "expires_in": 300
}
```

**Production Mode:**
```json
{
  "status": "otp_required",
  "message": "OTP sent to your phone",
  "expires_in": 300
}
```

#### Request 2: Verify OTP (Get Tokens)
```bash
POST /realms/{realm}/protocol/openid-connect/token
Content-Type: application/x-www-form-urlencoded

grant_type=password&username={username}&password={password}&otp={otp_code}&client_id={client_id}
```

**Success Response (200 OK):**
```json
{
  "access_token": "eyJhbGciOiJSUzI1NiIs...",
  "expires_in": 300,
  "refresh_expires_in": 1800,
  "refresh_token": "eyJhbGciOiJIUzI1NiIs...",
  "token_type": "Bearer",
  "not-before-policy": 0,
  "session_state": "...",
  "scope": "profile email"
}
```

### Browser Flow

Browser flow works exactly as before - users see a form to enter the OTP code after username/password authentication.

## Configuration in Keycloak

1. **Add to Authentication Flow:**
   - Go to: Authentication → Flows → Direct Grant
   - Add the "SMS Authentication" execution to the flow
   - Set it as REQUIRED or ALTERNATIVE

2. **Configure the Authenticator:**
   - Code length (default: 6 digits)
   - Time-to-live (default: 300 seconds = 5 minutes)
   - Sender ID (displayed as SMS sender)
   - Simulation mode (true/false)

3. **User Attribute:**
   - Users must have a `mobile_number` attribute set in their profile

## Error Responses

All error responses follow this format for Direct Grant:

### OTP Required
```json
{
  "status": "otp_required",
  "message": "OTP verification required",
  "expires_in": 300
}
```
HTTP Status: 200

### Invalid OTP
```json
{
  "error": "invalid_otp",
  "message": "Invalid OTP code"
}
```
HTTP Status: 401

### OTP Expired
```json
{
  "error": "otp_expired",
  "message": "OTP has expired"
}
```
HTTP Status: 400

### SMS Send Failed
```json
{
  "error": "sms_send_failed",
  "message": "Error details..."
}
```
HTTP Status: 500

### Invalid Request
```json
{
  "error": "invalid_request",
  "message": "No OTP session found"
}
```
HTTP Status: 400

## Client Implementation Examples

### JavaScript/TypeScript
```typescript
async function loginWithOTP(username: string, password: string): Promise<TokenResponse> {
  const tokenUrl = 'https://keycloak.example.com/realms/myrealm/protocol/openid-connect/token';
  const clientId = 'my-client';

  // First request - trigger OTP
  const response1 = await fetch(tokenUrl, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded',
      'Accept': 'application/json'
    },
    body: new URLSearchParams({
      grant_type: 'password',
      username: username,
      password: password,
      client_id: clientId
    })
  });

  const data1 = await response1.json();
  
  if (response1.status === 200 && data1.status === 'otp_required') {
    let otp: string;
    
    if (data1.otp) {
      // Simulation mode - OTP in response
      console.log('OTP (simulation):', data1.otp);
      otp = data1.otp; // Auto-fill for testing
    } else {
      // Production mode - prompt user
      otp = await promptUserForOTP();
    }

    // Second request - verify OTP
    const response2 = await fetch(tokenUrl, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/x-www-form-urlencoded',
        'Accept': 'application/json'
      },
      body: new URLSearchParams({
        grant_type: 'password',
        username: username,
        password: password,
        otp: otp,
        client_id: clientId
      })
    });

    if (response2.ok) {
      return await response2.json();
    } else {
      const error = await response2.json();
      throw new Error(error.message || 'OTP verification failed');
    }
  }

  throw new Error('Authentication failed');
}
```

### Python
```python
import requests

def login_with_otp(username: str, password: str, client_id: str) -> dict:
    token_url = 'https://keycloak.example.com/realms/myrealm/protocol/openid-connect/token'
    
    # First request - trigger OTP
    response1 = requests.post(
        token_url,
        data={
            'grant_type': 'password',
            'username': username,
            'password': password,
            'client_id': client_id
        },
        headers={'Accept': 'application/json'}
    )
    
    data1 = response1.json()
    
    if response1.status_code == 200 and data1.get('status') == 'otp_required':
        # Get OTP (from response in simulation, or prompt user in production)
        otp = data1.get('otp') or input('Enter OTP code: ')
        
        # Second request - verify OTP
        response2 = requests.post(
            token_url,
            data={
                'grant_type': 'password',
                'username': username,
                'password': password,
                'otp': otp,
                'client_id': client_id
            },
            headers={'Accept': 'application/json'}
        )
        
        if response2.ok:
            return response2.json()
        else:
            raise Exception(response2.json().get('message', 'OTP verification failed'))
    
    raise Exception('Authentication failed')
```

### cURL Examples

**First request (trigger OTP):**
```bash
curl -X POST 'https://keycloak.example.com/realms/myrealm/protocol/openid-connect/token' \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -H 'Accept: application/json' \
  -d 'grant_type=password' \
  -d 'username=testuser' \
  -d 'password=testpass' \
  -d 'client_id=my-client'
```

**Second request (verify OTP):**
```bash
curl -X POST 'https://keycloak.example.com/realms/myrealm/protocol/openid-connect/token' \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -H 'Accept: application/json' \
  -d 'grant_type=password' \
  -d 'username=testuser' \
  -d 'password=testpass' \
  -d 'otp=123456' \
  -d 'client_id=my-client'
```

## Testing

### Enable Simulation Mode

In Keycloak Admin Console:
1. Go to Authentication → Flows → Direct Grant → SMS Authentication (Config)
2. Set "Simulation mode" to `true`
3. Save

With simulation mode enabled:
- OTP is returned in the API response
- No actual SMS is sent
- Perfect for development and automated testing

### Automated Testing Example

```typescript
describe('SMS 2FA Authentication', () => {
  it('should authenticate with OTP', async () => {
    // First request
    const response1 = await authenticate('user', 'pass');
    expect(response1.status).toBe(200);
    expect(response1.data.status).toBe('otp_required');
    
    const otp = response1.data.otp; // Simulation mode
    
    // Second request
    const response2 = await authenticate('user', 'pass', otp);
    expect(response2.status).toBe(200);
    expect(response2.data.access_token).toBeDefined();
  });
});
```

## Security Considerations

### What's Implemented
- ✅ OTP is single-use (cleared after validation)
- ✅ OTP expires after configured TTL
- ✅ OTP only exposed in simulation mode
- ✅ Proper session management using Keycloak's AuthenticationSessionModel

### What's NOT Implemented (consider adding)
- ❌ Rate limiting (brute force protection)
- ❌ Maximum attempts before lockout
- ❌ IP-based restrictions
- ❌ Device fingerprinting

### Production Recommendations
1. **Always use HTTPS** in production
2. **Disable simulation mode** in production
3. **Use short OTP TTL** (5 minutes or less)
4. **Implement rate limiting** at infrastructure level (e.g., API gateway)
5. **Monitor failed attempts** and suspicious patterns
6. **Consider using Authorization Code Flow** instead of Direct Grant for better security

## Flow Detection Logic

The authenticator detects Direct Grant vs Browser flow by checking:

1. **Request URI**: Does it contain `/token` endpoint?
2. **Accept Header**: Does it accept `application/json` but not `text/html`?

If either condition is true, it's treated as Direct Grant flow.

## Migration from Original Version

If you're using the original browser-only authenticator:

1. **No breaking changes** for browser flows - they work exactly the same
2. **Direct Grant flows** now supported automatically
3. **Rebuild and redeploy** the JAR file
4. **Update Keycloak version** to 26.0.0+ if needed

## Build Instructions

```bash
# Compile
mvn clean compile

# Package (creates JAR with dependencies)
mvn clean package

# JAR location
target/thakacreations.keycloak-2fa-sms-authenticator.jar
```

**Note:** Package structure changed from `dasniko` to `thakacreations`

## Deployment

1. Copy JAR to Keycloak providers directory:
   ```bash
   cp target/thakacreations.keycloak-2fa-sms-authenticator.jar /opt/keycloak/providers/
   ```

2. Restart Keycloak:
   ```bash
   /opt/keycloak/bin/kc.sh start
   ```

3. Configure in Admin Console (see Configuration section above)

## Troubleshooting

### OTP not being sent
- Check user has `mobile_number` attribute
- Check AWS SNS configuration (production mode)
- Check Keycloak logs for SMS send errors

### "No OTP session found" error
- Session may have expired
- Restart authentication from first request
- Check session timeout settings

### Browser flow showing JSON instead of form
- Clear browser cache
- Check Accept headers being sent
- Verify authenticator is configured correctly in browser flow

## Support

For issues and questions:
- Original repository: https://github.com/dasniko/keycloak-2fa-sms-authenticator
- This implementation adds Direct Grant support

## License

Same license as original repository.

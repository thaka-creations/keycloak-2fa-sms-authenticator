# Quick Reference - SMS 2FA with Direct Grant

## Direct Grant Flow (Two API Calls)

### 1️⃣ First Call - Get OTP
```bash
POST /realms/{realm}/protocol/openid-connect/token
Content-Type: application/x-www-form-urlencoded

grant_type=password
username={username}
password={password}
client_id={client_id}
```

**Response: 401 Unauthorized**
```json
{
  "error": "otp_required",
  "message": "OTP verification required",
  "otp": "123456",        // ← Only in simulation mode!
  "expires_in": 300
}
```

### 2️⃣ Second Call - Verify OTP
```bash
POST /realms/{realm}/protocol/openid-connect/token
Content-Type: application/x-www-form-urlencoded

grant_type=password
username={username}
password={password}
otp={otp_code}          // ← Add this parameter
client_id={client_id}
```

**Response: 200 OK**
```json
{
  "access_token": "eyJhbGc...",
  "refresh_token": "eyJhbGc...",
  "token_type": "Bearer",
  "expires_in": 300
}
```

---

## Configuration Checklist

- [ ] User has `mobile_number` attribute set
- [ ] SMS Authentication added to Direct Grant flow
- [ ] Simulation mode enabled for testing (disable in production!)
- [ ] AWS SNS configured for production SMS sending

---

## Common Errors

| Error Code | Meaning | Solution |
|------------|---------|----------|
| `otp_required` | Need to provide OTP | Get OTP from response (simulation) or user input |
| `invalid_otp` | Wrong code | Check the code, retry |
| `otp_expired` | Code timeout | Request new OTP (start over) |
| `sms_send_failed` | SMS error | Check AWS SNS config, phone number format |

---

## JavaScript Example

```javascript
const tokenUrl = 'https://keycloak.example.com/realms/myrealm/protocol/openid-connect/token';

// Step 1: Get OTP
const step1 = await fetch(tokenUrl, {
  method: 'POST',
  headers: { 
    'Content-Type': 'application/x-www-form-urlencoded',
    'Accept': 'application/json'
  },
  body: new URLSearchParams({
    grant_type: 'password',
    username: 'user@example.com',
    password: 'password123',
    client_id: 'my-client'
  })
});

const data1 = await step1.json();
const otp = data1.otp || prompt('Enter OTP:'); // Auto in simulation, prompt in production

// Step 2: Verify OTP
const step2 = await fetch(tokenUrl, {
  method: 'POST',
  headers: { 
    'Content-Type': 'application/x-www-form-urlencoded',
    'Accept': 'application/json'
  },
  body: new URLSearchParams({
    grant_type: 'password',
    username: 'user@example.com',
    password: 'password123',
    otp: otp,
    client_id: 'my-client'
  })
});

const tokens = await step2.json();
console.log('Access token:', tokens.access_token);
```

---

## Python Example

```python
import requests

url = 'https://keycloak.example.com/realms/myrealm/protocol/openid-connect/token'

# Step 1: Get OTP
r1 = requests.post(url, data={
    'grant_type': 'password',
    'username': 'user@example.com',
    'password': 'password123',
    'client_id': 'my-client'
}, headers={'Accept': 'application/json'})

otp = r1.json().get('otp') or input('Enter OTP: ')

# Step 2: Verify OTP
r2 = requests.post(url, data={
    'grant_type': 'password',
    'username': 'user@example.com',
    'password': 'password123',
    'otp': otp,
    'client_id': 'my-client'
}, headers={'Accept': 'application/json'})

tokens = r2.json()
print('Access token:', tokens['access_token'])
```

---

## cURL Example

```bash
# Step 1: Get OTP
curl -X POST 'https://keycloak.example.com/realms/myrealm/protocol/openid-connect/token' \
  -H 'Accept: application/json' \
  -d 'grant_type=password' \
  -d 'username=user@example.com' \
  -d 'password=password123' \
  -d 'client_id=my-client'

# Response: {"error":"otp_required","otp":"123456",...}

# Step 2: Verify OTP (use the OTP from response or SMS)
curl -X POST 'https://keycloak.example.com/realms/myrealm/protocol/openid-connect/token' \
  -H 'Accept: application/json' \
  -d 'grant_type=password' \
  -d 'username=user@example.com' \
  -d 'password=password123' \
  -d 'otp=123456' \
  -d 'client_id=my-client'

# Response: {"access_token":"eyJ...","token_type":"Bearer",...}
```

---

## Testing Tips

✅ **Enable simulation mode** to see OTP in API response  
✅ **Use Postman/Insomnia** for manual testing  
✅ **Check Keycloak logs** for debugging  
✅ **Set short TTL** (60s) during testing for faster iteration  
✅ **Use Accept: application/json header** to ensure JSON responses  

---

## Deployment

```bash
# 1. Build
mvn clean package

# 2. Copy JAR
cp target/thakacreations.keycloak-2fa-sms-authenticator.jar /opt/keycloak/providers/

# 3. Restart Keycloak
/opt/keycloak/bin/kc.sh start

# 4. Configure in Admin Console
# Authentication → Direct Grant → Add SMS Authentication
```

---

## Security Checklist

Production Deployment:
- [ ] Simulation mode disabled
- [ ] HTTPS enabled
- [ ] AWS SNS credentials secured
- [ ] Short OTP TTL (≤ 5 minutes)
- [ ] Rate limiting configured at infrastructure level
- [ ] Monitoring and alerting set up
- [ ] Consider using Auth Code Flow instead of Direct Grant

---

## Need More Info?

📖 **Full Documentation**: See `DIRECT_GRANT_GUIDE.md`  
📝 **Changes Summary**: See `CHANGES.md`  
🔗 **Original Repo**: https://github.com/dasniko/keycloak-2fa-sms-authenticator

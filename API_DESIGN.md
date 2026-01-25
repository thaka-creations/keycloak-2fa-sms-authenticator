# API Design - Same Endpoint with 200 Status

## Overview

The SMS 2FA authenticator uses a **single endpoint** (`/token`) with **200 OK** for successful OTP generation, followed by error codes for validation failures.

## Final API Design

### Flow

```
POST /token (username + password)
  ↓ 200 OK
  { "status": "otp_required", "otp": "123456", "expires_in": 300 }

POST /token (username + password + otp)
  ↓ 200 OK (success) or 400/401 (failure)
  { "access_token": "...", ... }
```

## Response Codes

### Success Responses

| Request | Status | Response Body |
|---------|--------|---------------|
| First: username + password | **200 OK** | `{"status": "otp_required", "otp": "123456", "expires_in": 300}` |
| Second: username + password + otp (valid) | **200 OK** | `{"access_token": "...", "refresh_token": "..."}` |

### Error Responses

| Scenario | Status | Response Body |
|----------|--------|---------------|
| Invalid OTP | **401 Unauthorized** | `{"error": "invalid_otp", "message": "Invalid OTP code"}` |
| Expired OTP | **400 Bad Request** | `{"error": "otp_expired", "message": "OTP has expired"}` |
| SMS Send Failed | **500 Internal Error** | `{"error": "sms_send_failed", "message": "..."}` |
| No OTP Session | **400 Bad Request** | `{"error": "invalid_request", "message": "No OTP session found"}` |

## Rationale

### Why 200 for OTP Generation?

✅ **Semantically correct**: The request succeeded - OTP was generated and sent  
✅ **Clearer intent**: Client knows the first step completed successfully  
✅ **Better UX**: No need to handle "error" when operation succeeded  
✅ **Explicit state**: `"status": "otp_required"` clearly indicates next action

### Why Same Endpoint?

✅ **Simpler implementation**: Leverages Keycloak's native authentication flow  
✅ **Fewer moving parts**: No custom REST endpoints needed  
✅ **Consistent**: Standard OAuth2/OIDC token endpoint  
✅ **Less code**: Reuses existing authenticator logic

## Client Implementation

### JavaScript Example

```javascript
const tokenUrl = 'https://keycloak.example.com/realms/myrealm/protocol/openid-connect/token';

// Step 1: Trigger OTP
const response1 = await fetch(tokenUrl, {
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

const data1 = await response1.json();

if (response1.status === 200 && data1.status === 'otp_required') {
  // Success - OTP generated
  const otp = data1.otp || prompt('Enter OTP:');
  
  // Step 2: Verify OTP
  const response2 = await fetch(tokenUrl, {
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

  if (response2.ok) {
    const tokens = await response2.json();
    console.log('Access token:', tokens.access_token);
  } else {
    const error = await response2.json();
    console.error('OTP verification failed:', error.message);
  }
}
```

### Python Example

```python
import requests

url = 'https://keycloak.example.com/realms/myrealm/protocol/openid-connect/token'

# Step 1: Trigger OTP
response1 = requests.post(url, data={
    'grant_type': 'password',
    'username': 'user@example.com',
    'password': 'password123',
    'client_id': 'my-client'
}, headers={'Accept': 'application/json'})

data1 = response1.json()

if response1.status_code == 200 and data1.get('status') == 'otp_required':
    # Success - OTP generated
    otp = data1.get('otp') or input('Enter OTP: ')
    
    # Step 2: Verify OTP
    response2 = requests.post(url, data={
        'grant_type': 'password',
        'username': 'user@example.com',
        'password': 'password123',
        'otp': otp,
        'client_id': 'my-client'
    }, headers={'Accept': 'application/json'})
    
    if response2.ok:
        tokens = response2.json()
        print('Access token:', tokens['access_token'])
    else:
        error = response2.json()
        print('OTP verification failed:', error.get('message'))
```

## Error Handling Strategy

### Client Should Handle:

1. **200 + "status": "otp_required"** → Prompt for OTP
2. **200 + "access_token"** → Success, store tokens
3. **400/401 + "error"** → Show error message to user
4. **500** → Show generic error, retry later

### Example Error Handler

```javascript
async function handleTokenResponse(response) {
  const data = await response.json();
  
  if (response.status === 200) {
    if (data.status === 'otp_required') {
      return { needsOtp: true, otp: data.otp, expiresIn: data.expires_in };
    } else if (data.access_token) {
      return { success: true, tokens: data };
    }
  } else {
    return { 
      error: true, 
      code: data.error, 
      message: data.message 
    };
  }
}
```

## Testing

### Test Cases

1. **OTP Generation (200)**
   ```bash
   curl -X POST '.../token' -d 'grant_type=password&username=user&password=pass&client_id=app'
   # Expected: 200 OK with "status": "otp_required"
   ```

2. **Valid OTP (200)**
   ```bash
   curl -X POST '.../token' -d 'grant_type=password&username=user&password=pass&otp=123456&client_id=app'
   # Expected: 200 OK with "access_token"
   ```

3. **Invalid OTP (401)**
   ```bash
   curl -X POST '.../token' -d 'grant_type=password&username=user&password=pass&otp=999999&client_id=app'
   # Expected: 401 with "error": "invalid_otp"
   ```

4. **Expired OTP (400)**
   ```bash
   # Wait for TTL to expire, then:
   curl -X POST '.../token' -d 'grant_type=password&username=user&password=pass&otp=123456&client_id=app'
   # Expected: 400 with "error": "otp_expired"
   ```

## Advantages of This Design

| Aspect | Benefit |
|--------|---------|
| **Simplicity** | Same endpoint for both steps |
| **Clarity** | 200 = success, 4xx/5xx = errors |
| **Standards** | Uses standard OAuth2 token endpoint |
| **Debugging** | Clear status codes for each scenario |
| **Client Code** | Straightforward conditional logic |

## Alternative Designs Considered

### Alternative 1: Separate Endpoints
- `/token` for login
- `/verify-otp` for OTP verification

**Rejected because**: More complex, requires custom endpoints, non-standard OAuth2

### Alternative 2: 401 for OTP Required
- Return 401 when OTP is needed

**Rejected because**: Confusing - 401 typically means "authentication failed", not "provide more info"

### Alternative 3: 202 Accepted
- Return 202 for "processing, provide OTP next"

**Rejected because**: Not standard usage of 202, less intuitive than 200

## Summary

The final design uses:
- ✅ **Single endpoint**: `/token`
- ✅ **200 OK**: For successful OTP generation
- ✅ **"status" field**: To indicate OTP requirement
- ✅ **Standard error codes**: 400/401/500 for failures

This provides a clean, intuitive API that's easy to implement and use!

---

**Design Finalized**: January 25, 2026  
**Status**: ✅ Implemented & Deployed

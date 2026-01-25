# Scalability Upgrade Guide - Distributed Cache Implementation

## Overview

This guide provides step-by-step instructions for upgrading the OTP storage from in-memory HashMap to a distributed cache solution when scaling to multiple Keycloak instances.

**Current Implementation:** Static in-memory HashMap (single instance only)  
**Upgrade Options:** Keycloak Infinispan (recommended) or Redis

---

## When to Upgrade

### Keep Current Implementation If:
- ✅ Running single Keycloak instance
- ✅ Development/staging environment
- ✅ < 1000 concurrent users
- ✅ Can tolerate brief downtime during restarts

### Upgrade Required When:
- ❌ Deploying multiple Keycloak instances
- ❌ Load balancing without sticky sessions
- ❌ High availability requirements
- ❌ Zero-downtime deployment needed
- ❌ > 1000 concurrent users

---

## Option 1: Keycloak Infinispan (Recommended)

### Overview
Keycloak has built-in Infinispan distributed cache that automatically replicates data across cluster nodes.

### Advantages
- ✅ **Built-in**: No external dependencies
- ✅ **Auto-replication**: Syncs across all instances
- ✅ **Failover**: Survives instance restarts
- ✅ **TTL support**: Automatic expiry
- ✅ **Minimal config**: Works with Keycloak cluster

### Prerequisites
- Keycloak cluster setup with Infinispan enabled
- All instances can communicate (same network)

### Implementation Steps

#### Step 1: Update Dependencies (pom.xml)

No new dependencies needed - Infinispan is already included in Keycloak!

#### Step 2: Create OTP Cache Provider

Create new file: `src/main/java/thakacreations/keycloak/authenticator/cache/OtpCacheProvider.java`

```java
package thakacreations.keycloak.authenticator.cache;

import org.infinispan.Cache;
import org.keycloak.connections.infinispan.InfinispanConnectionProvider;
import org.keycloak.models.KeycloakSession;

import java.util.concurrent.TimeUnit;

public class OtpCacheProvider {
    
    private static final String CACHE_NAME = "otp-codes";
    
    private final Cache<String, OtpData> cache;
    
    public OtpCacheProvider(KeycloakSession session) {
        InfinispanConnectionProvider provider = session.getProvider(InfinispanConnectionProvider.class);
        this.cache = provider.getCache(CACHE_NAME);
    }
    
    public void put(String key, String code, long expiryTime) {
        OtpData data = new OtpData(code, expiryTime);
        long ttlSeconds = (expiryTime - System.currentTimeMillis()) / 1000;
        cache.put(key, data, ttlSeconds, TimeUnit.SECONDS);
    }
    
    public OtpData get(String key) {
        return cache.get(key);
    }
    
    public void remove(String key) {
        cache.remove(key);
    }
    
    public static class OtpData {
        private final String code;
        private final long expiryTime;
        
        public OtpData(String code, long expiryTime) {
            this.code = code;
            this.expiryTime = expiryTime;
        }
        
        public String getCode() {
            return code;
        }
        
        public long getExpiryTime() {
            return expiryTime;
        }
        
        public boolean isExpired() {
            return System.currentTimeMillis() > expiryTime;
        }
    }
}
```

#### Step 3: Configure Infinispan Cache

Create/update: `src/main/resources/META-INF/keycloak-cache-infinispan.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<infinispan xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
            xmlns="urn:infinispan:config:14.0"
            xsi:schemaLocation="urn:infinispan:config:14.0 http://www.infinispan.org/schemas/infinispan-config-14.0.xsd">

    <cache-container name="keycloak">
        <replicated-cache name="otp-codes">
            <encoding>
                <key media-type="application/x-java-object"/>
                <value media-type="application/x-java-object"/>
            </encoding>
            <expiration lifespan="300000" max-idle="-1"/>
            <memory max-count="10000"/>
        </replicated-cache>
    </cache-container>
</infinispan>
```

#### Step 4: Update SmsAuthenticator.java

Replace the static HashMap with Infinispan cache:

```java
// REMOVE these lines:
private static final Map<String, OtpData> OTP_CACHE = new ConcurrentHashMap<>();
private static class OtpData { ... }

// ADD:
import thakacreations.keycloak.authenticator.cache.OtpCacheProvider;

// In authenticate() method - REPLACE:
// String cacheKey = getCacheKey(context);
// OTP_CACHE.put(cacheKey, new OtpData(code, expiryTime));

// WITH:
String cacheKey = getCacheKey(context);
OtpCacheProvider cacheProvider = new OtpCacheProvider(session);
cacheProvider.put(cacheKey, code, expiryTime);

// In validateOtp() method - REPLACE:
// String cacheKey = getCacheKey(context);
// OtpData otpData = OTP_CACHE.get(cacheKey);

// WITH:
String cacheKey = getCacheKey(context);
OtpCacheProvider cacheProvider = new OtpCacheProvider(context.getSession());
OtpCacheProvider.OtpData otpData = cacheProvider.get(cacheKey);

// When clearing OTP - REPLACE:
// OTP_CACHE.remove(cacheKey);

// WITH:
cacheProvider.remove(cacheKey);
```

#### Step 5: Build and Deploy

```bash
mvn clean package
cp target/thakacreations.keycloak-2fa-sms-authenticator.jar /opt/keycloak/providers/
```

#### Step 6: Restart All Keycloak Instances

```bash
# Restart all instances in your cluster
/opt/keycloak/bin/kc.sh restart
```

### Testing Infinispan Implementation

**Test 1: Same Instance**
```bash
# Should work (no change from before)
curl [first request] → 200 OK with OTP
curl [second request with OTP] → 200 OK with tokens
```

**Test 2: Different Instances**
```bash
# Direct request to Instance A
curl http://instance-a:8080/... → 200 OK with OTP

# Direct request to Instance B (with OTP from Instance A)
curl http://instance-b:8080/... → 200 OK with tokens ✓
```

**Test 3: Instance Failover**
```bash
# Request 1 to Instance A
curl http://lb/... → 200 OK with OTP

# Stop Instance A
docker stop keycloak-a

# Request 2 to Instance B (load balancer redirects)
curl http://lb/... → 200 OK with tokens ✓
```

---

## Option 2: Redis Cache

### Overview
Use external Redis cache for distributed OTP storage.

### Advantages
- ✅ **Independent scaling**: Scale Redis separately
- ✅ **High performance**: Very fast in-memory cache
- ✅ **TTL support**: Built-in expiration
- ✅ **Monitoring**: Better observability tools
- ✅ **Multi-service**: Other apps can use same Redis

### Disadvantages
- ➖ External dependency (Redis server required)
- ➖ Network latency (vs in-process Infinispan)
- ➖ Additional infrastructure to manage

### Prerequisites
- Redis server accessible from all Keycloak instances
- Network connectivity between Keycloak and Redis

### Implementation Steps

#### Step 1: Add Redis Dependency (pom.xml)

```xml
<dependency>
    <groupId>redis.clients</groupId>
    <artifactId>jedis</artifactId>
    <version>5.1.0</version>
</dependency>
```

#### Step 2: Create Redis Cache Provider

Create: `src/main/java/thakacreations/keycloak/authenticator/cache/RedisCacheProvider.java`

```java
package thakacreations.keycloak.authenticator.cache;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

public class RedisCacheProvider {
    
    private static JedisPool jedisPool;
    
    static {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(20);
        poolConfig.setMaxIdle(10);
        poolConfig.setMinIdle(5);
        
        // Get from environment or config
        String redisHost = System.getenv().getOrDefault("REDIS_HOST", "localhost");
        int redisPort = Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379"));
        String redisPassword = System.getenv("REDIS_PASSWORD");
        
        if (redisPassword != null && !redisPassword.isEmpty()) {
            jedisPool = new JedisPool(poolConfig, redisHost, redisPort, 2000, redisPassword);
        } else {
            jedisPool = new JedisPool(poolConfig, redisHost, redisPort);
        }
    }
    
    public void put(String key, String code, long expiryTime) {
        try (Jedis jedis = jedisPool.getResource()) {
            long ttlSeconds = (expiryTime - System.currentTimeMillis()) / 1000;
            jedis.setex(key, ttlSeconds, code + ":" + expiryTime);
        }
    }
    
    public OtpData get(String key) {
        try (Jedis jedis = jedisPool.getResource()) {
            String value = jedis.get(key);
            if (value == null) return null;
            
            String[] parts = value.split(":");
            return new OtpData(parts[0], Long.parseLong(parts[1]));
        }
    }
    
    public void remove(String key) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.del(key);
        }
    }
    
    public static class OtpData {
        private final String code;
        private final long expiryTime;
        
        public OtpData(String code, long expiryTime) {
            this.code = code;
            this.expiryTime = expiryTime;
        }
        
        public String getCode() {
            return code;
        }
        
        public long getExpiryTime() {
            return expiryTime;
        }
        
        public boolean isExpired() {
            return System.currentTimeMillis() > expiryTime;
        }
    }
}
```

#### Step 3: Update SmsAuthenticator.java

```java
// REMOVE:
private static final Map<String, OtpData> OTP_CACHE = new ConcurrentHashMap<>();

// ADD:
import thakacreations.keycloak.authenticator.cache.RedisCacheProvider;

// In authenticate() - REPLACE HashMap with Redis:
String cacheKey = getCacheKey(context);
RedisCacheProvider cacheProvider = new RedisCacheProvider();
cacheProvider.put(cacheKey, code, expiryTime);

// In validateOtp() - REPLACE HashMap with Redis:
String cacheKey = getCacheKey(context);
RedisCacheProvider cacheProvider = new RedisCacheProvider();
RedisCacheProvider.OtpData otpData = cacheProvider.get(cacheKey);
```

#### Step 4: Setup Redis

**Using Docker Compose:**

```yaml
# Add to docker-compose.yml
services:
  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"
    volumes:
      - redis-data:/data
    command: redis-server --appendonly yes
    
  keycloak:
    environment:
      - REDIS_HOST=redis
      - REDIS_PORT=6379
      # - REDIS_PASSWORD=your-password  # if using auth

volumes:
  redis-data:
```

**Or use existing Redis:**

```bash
# Set environment variables
export REDIS_HOST=your-redis.example.com
export REDIS_PORT=6379
export REDIS_PASSWORD=your-password
```

#### Step 5: Build and Deploy

```bash
mvn clean package
cp target/thakacreations.keycloak-2fa-sms-authenticator.jar /opt/keycloak/providers/
```

#### Step 6: Restart Keycloak

```bash
docker-compose restart keycloak
```

### Testing Redis Implementation

**Test 1: Verify Redis Connection**
```bash
# Check Redis is accessible
redis-cli -h your-redis-host ping
# Should return: PONG
```

**Test 2: Check OTP Storage**
```bash
# After first request, check Redis
redis-cli -h your-redis-host
> KEYS otp:*
> GET "realm-id:username"
```

**Test 3: Multi-Instance Test**
```bash
# Request 1 to any instance
curl http://lb/... → 200 OK with OTP

# Request 2 to any instance
curl http://lb/... → 200 OK with tokens ✓
```

---

## Migration Checklist

### Pre-Migration
- [ ] Decide on Infinispan or Redis
- [ ] Review current deployment architecture
- [ ] Test in staging/dev environment first
- [ ] Backup current configuration
- [ ] Document rollback procedure

### Migration Steps
- [ ] Implement chosen cache solution
- [ ] Update dependencies (if Redis)
- [ ] Update SmsAuthenticator.java
- [ ] Add configuration files
- [ ] Build and test locally
- [ ] Deploy to staging
- [ ] Run integration tests
- [ ] Monitor for errors
- [ ] Deploy to production
- [ ] Verify multi-instance operation

### Post-Migration
- [ ] Monitor cache hit/miss rates
- [ ] Check memory usage
- [ ] Verify TTL expiration works
- [ ] Test failover scenarios
- [ ] Update documentation
- [ ] Train team on new architecture

---

## Performance Comparison

| Metric | Static HashMap | Infinispan | Redis |
|--------|---------------|------------|-------|
| **Single Instance Speed** | 10/10 | 9/10 | 8/10 |
| **Multi-Instance Support** | 0/10 | 10/10 | 10/10 |
| **Failover** | 0/10 | 10/10 | 10/10 |
| **Setup Complexity** | 10/10 | 8/10 | 6/10 |
| **External Dependencies** | None | None | Redis |
| **Memory Efficiency** | 10/10 | 9/10 | 10/10 |
| **Monitoring** | 5/10 | 7/10 | 10/10 |
| **Scalability** | Low | High | Very High |

---

## Cost Considerations

### Infinispan (Recommended for Most)
- **Cost**: Free (built into Keycloak)
- **Infrastructure**: None (uses existing Keycloak cluster)
- **Maintenance**: Low (managed by Keycloak)
- **Best for**: Standard Keycloak deployments

### Redis
- **Cost**: 
  - Self-hosted: ~$20-50/month (small instance)
  - Managed (AWS ElastiCache): ~$30-100/month
- **Infrastructure**: Additional server/service
- **Maintenance**: Medium (if self-hosted), Low (if managed)
- **Best for**: 
  - Very high traffic (>10k concurrent users)
  - Need advanced caching features
  - Already using Redis for other services

---

## Troubleshooting

### Infinispan Issues

**Problem: Cache not replicating**
```bash
# Check cluster status
/opt/keycloak/bin/kcadm.sh config credentials ...
/opt/keycloak/bin/kcadm.sh get server-info
```

**Solution:**
- Verify all instances can communicate
- Check firewall rules (port 7800 for JGroups)
- Review Infinispan logs

**Problem: "Cache not found"**
- Ensure `keycloak-cache-infinispan.xml` is in classpath
- Check cache name matches exactly

### Redis Issues

**Problem: Connection refused**
```
Could not connect to Redis at localhost:6379
```

**Solution:**
- Verify Redis is running: `redis-cli ping`
- Check REDIS_HOST environment variable
- Verify network connectivity

**Problem: Authentication failed**
```
NOAUTH Authentication required
```

**Solution:**
- Set REDIS_PASSWORD environment variable
- Update RedisCacheProvider to use password

---

## Rollback Procedure

If issues occur after upgrade:

### Step 1: Redeploy Old JAR
```bash
# Keep backup of old JAR
cp /backup/thakacreations.keycloak-2fa-sms-authenticator.jar /opt/keycloak/providers/
```

### Step 2: Remove Cache Configuration
```bash
# Remove Infinispan config if added
rm /opt/keycloak/conf/keycloak-cache-infinispan.xml
```

### Step 3: Restart Keycloak
```bash
docker-compose restart keycloak
```

### Step 4: Verify
```bash
# Test authentication works
curl [test request]
```

---

## Support & Resources

### Keycloak Infinispan Documentation
- https://www.keycloak.org/server/caching
- https://infinispan.org/docs/stable/titles/embedding/embedding.html

### Redis Documentation
- https://redis.io/docs/
- https://github.com/redis/jedis

### Community Support
- Keycloak Discord/Mailing List
- Stack Overflow

---

## Summary

**Current State:**
- ✅ Working perfectly for single instance
- ✅ Fast and simple
- ❌ Not suitable for multi-instance

**After Upgrade:**
- ✅ Supports multi-instance deployments
- ✅ High availability
- ✅ Zero-downtime deployments
- ✅ Better for production at scale

**When to Upgrade:**
- When scaling to 2+ instances
- Before going to production with HA requirements
- When concurrent users exceed 1000

---

**Document Version**: 1.0  
**Last Updated**: January 25, 2026  
**Status**: Ready for implementation when needed  
**Recommended Option**: Keycloak Infinispan (easiest, no new dependencies)

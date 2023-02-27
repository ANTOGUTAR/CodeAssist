package org.jetbrains.kotlin.com.intellij.util.io;

import org.jetbrains.kotlin.com.intellij.util.SystemProperties;
import org.jetbrains.kotlin.com.intellij.util.containers.SLRUMap;
import org.jetbrains.kotlin.com.intellij.util.containers.ShareableKey;

public final class PersistentEnumeratorCache {
  private static final int ENUMERATION_CACHE_SIZE = SystemProperties.getIntProperty("idea.enumerationCacheSize", 8192);
  private static final CacheKey ourFlyweight = new FlyweightKey();
  private static final SLRUMap<Object, Integer> ourEnumerationCache = new SLRUMap<>(ENUMERATION_CACHE_SIZE, ENUMERATION_CACHE_SIZE);

  static void cacheId(Object value, int id, PersistentEnumeratorBase<?> owner) {
    synchronized (ourEnumerationCache) {
      ourEnumerationCache.put(new CacheKey(value, owner), id);
    }
  }

  static int getCachedId(Object value, PersistentEnumeratorBase<?> owner) {
    synchronized (ourEnumerationCache) {
      final Integer cachedId = ourEnumerationCache.get(sharedKey(value, owner));
        if (cachedId != null) {
            return cachedId.intValue();
        }
    }
    return PersistentEnumeratorBase.NULL_ID;
  }

  private static class CacheKey implements ShareableKey {
    public PersistentEnumeratorBase<?> owner;
    public Object key;

    private CacheKey(Object key, PersistentEnumeratorBase<?> owner) {
      this.key = key;
      this.owner = owner;
    }

    @Override
    public ShareableKey getStableCopy() {
      return this;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CacheKey)) {
            return false;
        }

      final CacheKey cacheKey = (CacheKey)o;

        if (!key.equals(cacheKey.key)) {
            return false;
        }
        if (!owner.equals(cacheKey.owner)) {
            return false;
        }

      return true;
    }

    @Override
    public int hashCode() {
      return key.hashCode();
    }
  }

  private static class FlyweightKey extends CacheKey {
    FlyweightKey() {
      super(null, null);
    }

    @Override
    public ShareableKey getStableCopy() {
      return new CacheKey(key, owner);
    }
  }

  private static CacheKey sharedKey(Object key, PersistentEnumeratorBase<?> owner) {
    ourFlyweight.key = key;
    ourFlyweight.owner = owner;
    return ourFlyweight;
  }

  public static void clearCacheForTests() {
    ourEnumerationCache.clear();
  }
}
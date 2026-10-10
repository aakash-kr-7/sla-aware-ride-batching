package com.ridebatching.zone;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.CacheEvict;
import com.ridebatching.config.CacheConfig;

@Repository
public interface ServiceableZoneRepository extends JpaRepository<ServiceableZone, Long> {
    
    @Cacheable(CacheConfig.ACTIVE_ZONES_CACHE)
    List<ServiceableZone> findByActiveTrue();

    @Override
    @CacheEvict(value = CacheConfig.ACTIVE_ZONES_CACHE, allEntries = true)
    <S extends ServiceableZone> S save(S entity);

    @Override
    @CacheEvict(value = CacheConfig.ACTIVE_ZONES_CACHE, allEntries = true)
    <S extends ServiceableZone> List<S> saveAll(Iterable<S> entities);

    @Override
    @CacheEvict(value = CacheConfig.ACTIVE_ZONES_CACHE, allEntries = true)
    void delete(ServiceableZone entity);

    @Override
    @CacheEvict(value = CacheConfig.ACTIVE_ZONES_CACHE, allEntries = true)
    void deleteAll();
}

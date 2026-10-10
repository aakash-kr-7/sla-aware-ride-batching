package com.ridebatching.zone;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ServiceableZoneRepository extends JpaRepository<ServiceableZone, Long> {
    List<ServiceableZone> findByActiveTrue();
}

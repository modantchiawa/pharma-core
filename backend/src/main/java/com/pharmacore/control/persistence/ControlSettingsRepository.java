package com.pharmacore.control.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ControlSettingsRepository extends JpaRepository<ControlSettingsEntity, UUID> {
}

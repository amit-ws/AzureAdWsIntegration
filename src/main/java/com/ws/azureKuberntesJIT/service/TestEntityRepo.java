package com.ws.azureKuberntesJIT.service;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TestEntityRepo extends JpaRepository<TestEntity, Long> {
}

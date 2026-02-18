package com.ws.azureKuberntesJIT.service;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TestListEntityRepo extends JpaRepository<TestListEntity, Long> {
}

package com.copiproxy.service;

import com.copiproxy.model.ApiKeyRecord;
import com.copiproxy.model.CopilotMeta;
import com.copiproxy.repo.ApiKeyRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ApiKeyService {
    private final ApiKeyRepository repository;
    private final GithubCopilotService githubCopilotService;

    public ApiKeyService(ApiKeyRepository repository, GithubCopilotService githubCopilotService) {
        this.repository = repository;
        this.githubCopilotService = githubCopilotService;
    }

    public ApiKeyRecord create(String name, String key) {
        ApiKeyRecord record = repository.create(name, key);
        try {
            CopilotMeta meta = githubCopilotService.fetchCopilotMeta(key);
            repository.upsertMeta(record.id(), meta);
        } catch (RuntimeException ignored) {
            // Key can still be stored even if metadata fetch fails.
        }
        return repository.findById(record.id()).orElseThrow();
    }

    public List<ApiKeyRecord> list() {
        return repository.findAll();
    }

    public ApiKeyRecord updateName(String id, String name) {
        repository.updateName(id, name);
        return repository.findById(id).orElseThrow();
    }

    public void delete(String id) {
        repository.delete(id);
    }

    public ApiKeyRecord setDefault(String id) {
        repository.setDefault(id);
        return repository.findById(id).orElseThrow();
    }

    public ApiKeyRecord refreshMeta(String id) {
        ApiKeyRecord record = repository.findById(id).orElseThrow();
        CopilotMeta meta = githubCopilotService.fetchCopilotMeta(record.key());
        repository.upsertMeta(id, meta);
        return repository.findById(id).orElseThrow();
    }

    public ApiKeyRecord getDefaultKey() {
        return repository.findDefault().orElse(null);
    }
}

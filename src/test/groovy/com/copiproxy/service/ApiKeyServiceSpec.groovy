package com.copiproxy.service

import com.copiproxy.model.ApiKeyRecord
import com.copiproxy.model.CopilotMeta
import com.copiproxy.repo.ApiKeyRepository
import spock.lang.Specification

class ApiKeyServiceSpec extends Specification {

    def repository = Mock(ApiKeyRepository)
    def githubCopilotService = Mock(GithubCopilotService)
    def service = new ApiKeyService(repository, githubCopilotService)

    def "create stores metadata when GitHub meta call succeeds"() {
        given:
        def created = new ApiKeyRecord("id-1", "name", "key-1", false, 1L, null, 0, null)
        def withMeta = new ApiKeyRecord("id-1", "name", "key-1", false, 1L, null, 0,
                new CopilotMeta("copilot-token", 100000L, null, 10, 20))
        def meta = withMeta.meta()

        when:
        def result = service.create("name", "key-1")

        then:
        1 * repository.create("name", "key-1") >> created
        1 * githubCopilotService.fetchCopilotMeta("key-1") >> meta
        1 * repository.upsertMeta("id-1", meta)
        1 * repository.findById("id-1") >> Optional.of(withMeta)
        result == withMeta
    }

    def "create continues when metadata fetch fails"() {
        given:
        def created = new ApiKeyRecord("id-2", "name", "key-2", false, 1L, null, 0, null)

        when:
        def result = service.create("name", "key-2")

        then:
        1 * repository.create("name", "key-2") >> created
        1 * githubCopilotService.fetchCopilotMeta("key-2") >> { throw new RuntimeException("boom") }
        0 * repository.upsertMeta(_, _)
        1 * repository.findById("id-2") >> Optional.of(created)
        result == created
    }

    def "list delegates to repository"() {
        given:
        def rows = [
                new ApiKeyRecord("id-1", "a", "k1", false, 1L, null, 0, null),
                new ApiKeyRecord("id-2", "b", "k2", false, 2L, null, 0, null)
        ]

        when:
        def result = service.list()

        then:
        1 * repository.findAll() >> rows
        result == rows
    }

    def "updateName updates and returns record"() {
        given:
        def row = new ApiKeyRecord("id-3", "new-name", "k3", false, 1L, null, 0, null)

        when:
        def result = service.updateName("id-3", "new-name")

        then:
        1 * repository.updateName("id-3", "new-name")
        1 * repository.findById("id-3") >> Optional.of(row)
        result == row
    }

    def "delete delegates to repository"() {
        when:
        service.delete("id-4")

        then:
        1 * repository.delete("id-4")
    }

    def "setDefault delegates and returns record"() {
        given:
        def row = new ApiKeyRecord("id-5", "name", "k5", true, 1L, null, 0, null)

        when:
        def result = service.setDefault("id-5")

        then:
        1 * repository.setDefault("id-5")
        1 * repository.findById("id-5") >> Optional.of(row)
        result == row
    }

    def "refreshMeta fetches by existing key and persists meta"() {
        given:
        def existing = new ApiKeyRecord("id-6", "name", "k6", false, 1L, null, 0, null)
        def refreshed = new ApiKeyRecord("id-6", "name", "k6", false, 1L, null, 0,
                new CopilotMeta("token-6", 12345L, 999L, 1, 2))

        when:
        def result = service.refreshMeta("id-6")

        then:
        2 * repository.findById("id-6") >>> [Optional.of(existing), Optional.of(refreshed)]
        1 * githubCopilotService.fetchCopilotMeta("k6") >> refreshed.meta()
        1 * repository.upsertMeta("id-6", refreshed.meta())
        result == refreshed
    }

    def "getDefaultKey returns null when missing"() {
        when:
        def result = service.getDefaultKey()

        then:
        1 * repository.findDefault() >> Optional.empty()
        result == null
    }
}

package com.copiproxy.model

import spock.lang.Specification

class RecordSpec extends Specification {

    def "CopilotMeta record stores values"() {
        when:
        def meta = new CopilotMeta("tok", 100L, 200L, 3, 4)

        then:
        meta.token() == "tok"
        meta.expiresAt() == 100L
        meta.resetTime() == 200L
        meta.chatQuota() == 3
        meta.completionsQuota() == 4
    }

    def "ApiKeyRecord record stores values"() {
        given:
        def meta = new CopilotMeta("tok2", 300L, null, null, null)

        when:
        def rec = new ApiKeyRecord("id", "name", "key", true, 999L, 111L, 7, meta)

        then:
        rec.id() == "id"
        rec.name() == "name"
        rec.key() == "key"
        rec.isDefault()
        rec.createdAt() == 999L
        rec.lastUsed() == 111L
        rec.usageCount() == 7
        rec.meta() == meta
    }
}

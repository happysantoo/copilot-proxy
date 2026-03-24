package com.copiproxy.web

import com.copiproxy.model.ApiKeyRecord
import com.copiproxy.service.ApiKeyService
import com.copiproxy.service.GithubCopilotService
import spock.lang.Specification

class AdminControllerSpec extends Specification {

    def apiKeyService = Mock(ApiKeyService)
    def githubCopilotService = Mock(GithubCopilotService)
    def controller = new AdminController(apiKeyService, githubCopilotService)

    def "delegates list/create/update/delete/setDefault/refresh"() {
        given:
        def rec = new ApiKeyRecord("id", "name", "k", false, 1L, null, 0, null)
        def rows = [rec]

        when:
        def listed = controller.list()
        def created = controller.create(new AdminController.CreateApiKeyRequest("name", "k"))
        def updated = controller.update("id", new AdminController.UpdateApiKeyRequest("new"))
        controller.delete("id")
        def defaulted = controller.setDefault(new AdminController.SetDefaultRequest("id"))
        def refreshed = controller.refresh("id")

        then:
        1 * apiKeyService.list() >> rows
        1 * apiKeyService.create("name", "k") >> rec
        1 * apiKeyService.updateName("id", "new") >> rec
        1 * apiKeyService.delete("id")
        1 * apiKeyService.setDefault("id") >> rec
        1 * apiKeyService.refreshMeta("id") >> rec
        listed == rows
        created == rec
        updated == rec
        defaulted == rec
        refreshed == rec
    }

    def "deviceFlow returns an emitter object"() {
        when:
        def emitter = controller.deviceFlow()

        then:
        emitter != null
    }
}

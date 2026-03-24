package com.copiproxy.service

import com.copiproxy.config.CopiProxyProperties
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import spock.lang.Specification

import java.nio.charset.StandardCharsets

class MessageTranslationServiceSpec extends Specification {

    ObjectMapper mapper = new ObjectMapper()

    MessageTranslationService service = new MessageTranslationService(
            new CopiProxyProperties(
                    "http://unused", "http://unused", "http://unused",
                    "http://unused", "unused",
                    "claude-opus-4-6",
                    "vscode/1.98.0", "copilot-chat/0.23.2",
                    "GitHubCopilotChat/0.23.2", "vscode-chat",
                    "github-copilot", "conversation-panel", "2025-01-21"
            )
    )

    // ── Request translation ──────────────────────────────────────────

    def "translateRequest maps simple text conversation"() {
        given:
        def body = mapper.writeValueAsBytes([
                model     : "claude-opus-4-6",
                max_tokens: 1024,
                messages  : [
                        [role: "user", content: "Hello"]
                ]
        ])

        when:
        def result = mapper.readTree(service.translateRequest(body))

        then:
        result.path("model").asText() == "claude-opus-4-6"
        result.path("max_tokens").asInt() == 1024
        result.path("messages").size() == 1
        result.path("messages").get(0).path("role").asText() == "user"
        result.path("messages").get(0).path("content").asText() == "Hello"
    }

    def "translateRequest injects default model when model is missing"() {
        given:
        def body = mapper.writeValueAsBytes([
                max_tokens: 100,
                messages  : [[role: "user", content: "hi"]]
        ])

        when:
        def result = mapper.readTree(service.translateRequest(body))

        then:
        result.path("model").asText() == "claude-opus-4-6"
    }

    def "translateRequest injects default model when model is blank"() {
        given:
        def body = mapper.writeValueAsBytes([
                model     : "",
                max_tokens: 100,
                messages  : [[role: "user", content: "hi"]]
        ])

        when:
        def result = mapper.readTree(service.translateRequest(body))

        then:
        result.path("model").asText() == "claude-opus-4-6"
    }

    def "translateRequest hoists system to first message"() {
        given:
        def body = mapper.writeValueAsBytes([
                model     : "claude-opus-4-6",
                max_tokens: 100,
                system    : "You are a coding assistant.",
                messages  : [[role: "user", content: "Hi"]]
        ])

        when:
        def result = mapper.readTree(service.translateRequest(body))

        then:
        result.path("messages").size() == 2
        result.path("messages").get(0).path("role").asText() == "system"
        result.path("messages").get(0).path("content").asText() == "You are a coding assistant."
        result.path("messages").get(1).path("role").asText() == "user"
    }

    def "translateRequest hoists system array to first message"() {
        given:
        def body = mapper.writeValueAsBytes([
                model     : "claude-opus-4-6",
                max_tokens: 100,
                system    : [[type: "text", text: "Part 1"], [type: "text", text: "Part 2"]],
                messages  : [[role: "user", content: "Hi"]]
        ])

        when:
        def result = mapper.readTree(service.translateRequest(body))

        then:
        result.path("messages").get(0).path("content").asText() == "Part 1\nPart 2"
    }

    def "translateRequest maps stop_sequences to stop"() {
        given:
        def body = mapper.writeValueAsBytes([
                model         : "claude-opus-4-6",
                max_tokens    : 100,
                stop_sequences: ["END", "STOP"],
                messages      : [[role: "user", content: "hi"]]
        ])

        when:
        def result = mapper.readTree(service.translateRequest(body))

        then:
        result.path("stop").size() == 2
        result.path("stop").get(0).asText() == "END"
    }

    def "translateRequest passes through temperature and top_p"() {
        given:
        def body = mapper.writeValueAsBytes([
                model     : "claude-opus-4-6",
                max_tokens: 100,
                temperature: 0.7,
                top_p      : 0.9,
                messages  : [[role: "user", content: "hi"]]
        ])

        when:
        def result = mapper.readTree(service.translateRequest(body))

        then:
        result.path("temperature").doubleValue() == 0.7d
        result.path("top_p").doubleValue() == 0.9d
    }

    def "translateRequest passes through stream flag"() {
        given:
        def body = mapper.writeValueAsBytes([
                model     : "claude-opus-4-6",
                max_tokens: 100,
                stream    : true,
                messages  : [[role: "user", content: "hi"]]
        ])

        when:
        def result = mapper.readTree(service.translateRequest(body))

        then:
        result.path("stream").asBoolean() == true
    }

    // ── Tool definitions ─────────────────────────────────────────────

    def "translateRequest converts tool definitions"() {
        given:
        def body = mapper.writeValueAsBytes([
                model     : "claude-opus-4-6",
                max_tokens: 100,
                messages  : [[role: "user", content: "hi"]],
                tools     : [
                        [
                                name        : "get_weather",
                                description : "Get weather info",
                                input_schema: [
                                        type      : "object",
                                        properties: [location: [type: "string"]],
                                        required  : ["location"]
                                ]
                        ]
                ]
        ])

        when:
        def result = mapper.readTree(service.translateRequest(body))

        then:
        result.path("tools").size() == 1
        def tool = result.path("tools").get(0)
        tool.path("type").asText() == "function"
        tool.path("function").path("name").asText() == "get_weather"
        tool.path("function").path("description").asText() == "Get weather info"
        tool.path("function").path("parameters").path("type").asText() == "object"
    }

    // ── Tool choice ──────────────────────────────────────────────────

    def "translateRequest maps tool_choice auto"() {
        given:
        def body = mapper.writeValueAsBytes([
                model      : "claude-opus-4-6",
                max_tokens : 100,
                messages   : [[role: "user", content: "hi"]],
                tool_choice: [type: "auto"]
        ])

        when:
        def result = mapper.readTree(service.translateRequest(body))

        then:
        result.path("tool_choice").asText() == "auto"
    }

    def "translateRequest maps tool_choice any to required"() {
        given:
        def body = mapper.writeValueAsBytes([
                model      : "claude-opus-4-6",
                max_tokens : 100,
                messages   : [[role: "user", content: "hi"]],
                tool_choice: [type: "any"]
        ])

        when:
        def result = mapper.readTree(service.translateRequest(body))

        then:
        result.path("tool_choice").asText() == "required"
    }

    def "translateRequest maps tool_choice specific tool"() {
        given:
        def body = mapper.writeValueAsBytes([
                model      : "claude-opus-4-6",
                max_tokens : 100,
                messages   : [[role: "user", content: "hi"]],
                tool_choice: [type: "tool", name: "get_weather"]
        ])

        when:
        def result = mapper.readTree(service.translateRequest(body))

        then:
        result.path("tool_choice").path("type").asText() == "function"
        result.path("tool_choice").path("function").path("name").asText() == "get_weather"
    }

    // ── Assistant tool_use messages ──────────────────────────────────

    def "translateRequest converts assistant tool_use blocks"() {
        given:
        def body = mapper.writeValueAsBytes([
                model     : "claude-opus-4-6",
                max_tokens: 100,
                messages  : [
                        [role: "user", content: "What's the weather?"],
                        [role: "assistant", content: [
                                [type: "text", text: "Let me check."],
                                [type: "tool_use", id: "toolu_123", name: "get_weather", input: [location: "SF"]]
                        ]]
                ]
        ])

        when:
        def result = mapper.readTree(service.translateRequest(body))

        then:
        def assistant = result.path("messages").get(1)
        assistant.path("role").asText() == "assistant"
        assistant.path("content").asText() == "Let me check."
        assistant.path("tool_calls").size() == 1
        assistant.path("tool_calls").get(0).path("id").asText() == "toolu_123"
        assistant.path("tool_calls").get(0).path("type").asText() == "function"
        assistant.path("tool_calls").get(0).path("function").path("name").asText() == "get_weather"
    }

    // ── User tool_result messages ────────────────────────────────────

    def "translateRequest converts user tool_result blocks to tool role"() {
        given:
        def body = mapper.writeValueAsBytes([
                model     : "claude-opus-4-6",
                max_tokens: 100,
                messages  : [
                        [role: "user", content: [
                                [type: "tool_result", tool_use_id: "toolu_123", content: "72 degrees"]
                        ]]
                ]
        ])

        when:
        def result = mapper.readTree(service.translateRequest(body))

        then:
        result.path("messages").size() == 1
        result.path("messages").get(0).path("role").asText() == "tool"
        result.path("messages").get(0).path("tool_call_id").asText() == "toolu_123"
        result.path("messages").get(0).path("content").asText() == "72 degrees"
    }

    def "translateRequest splits mixed tool_result and text into separate messages"() {
        given:
        def body = mapper.writeValueAsBytes([
                model     : "claude-opus-4-6",
                max_tokens: 100,
                messages  : [
                        [role: "user", content: [
                                [type: "tool_result", tool_use_id: "toolu_123", content: "72 degrees"],
                                [type: "text", text: "What about tomorrow?"]
                        ]]
                ]
        ])

        when:
        def result = mapper.readTree(service.translateRequest(body))

        then:
        result.path("messages").size() == 2
        result.path("messages").get(0).path("role").asText() == "tool"
        result.path("messages").get(0).path("content").asText() == "72 degrees"
        result.path("messages").get(1).path("role").asText() == "user"
        result.path("messages").get(1).path("content").asText() == "What about tomorrow?"
    }

    // ── Response translation ─────────────────────────────────────────

    def "translateResponse maps text response"() {
        given:
        def openAi = mapper.writeValueAsBytes([
                id     : "chatcmpl-xxx",
                choices: [[
                                   message      : [role: "assistant", content: "Hello!"],
                                   finish_reason: "stop"
                           ]],
                usage  : [prompt_tokens: 10, completion_tokens: 5, total_tokens: 15]
        ])

        when:
        def result = mapper.readTree(service.translateResponse(openAi, "claude-opus-4-6"))

        then:
        result.path("type").asText() == "message"
        result.path("role").asText() == "assistant"
        result.path("model").asText() == "claude-opus-4-6"
        result.path("content").size() == 1
        result.path("content").get(0).path("type").asText() == "text"
        result.path("content").get(0).path("text").asText() == "Hello!"
        result.path("stop_reason").asText() == "end_turn"
        result.path("usage").path("input_tokens").asInt() == 10
        result.path("usage").path("output_tokens").asInt() == 5
        result.path("id").asText().startsWith("msg_")
    }

    def "translateResponse maps tool_calls response"() {
        given:
        def openAi = mapper.writeValueAsBytes([
                choices: [[
                                   message      : [
                                           role      : "assistant",
                                           content   : null,
                                           tool_calls: [
                                                   [
                                                           id      : "call_abc",
                                                           type    : "function",
                                                           function: [name: "get_weather", arguments: '{"location":"SF"}']
                                                   ]
                                           ]
                                   ],
                                   finish_reason: "tool_calls"
                           ]],
                usage  : [prompt_tokens: 5, completion_tokens: 3]
        ])

        when:
        def result = mapper.readTree(service.translateResponse(openAi, "claude-opus-4-6"))

        then:
        result.path("stop_reason").asText() == "tool_use"
        result.path("content").size() == 1
        def toolUse = result.path("content").get(0)
        toolUse.path("type").asText() == "tool_use"
        toolUse.path("id").asText() == "call_abc"
        toolUse.path("name").asText() == "get_weather"
        toolUse.path("input").path("location").asText() == "SF"
    }

    def "translateResponse maps mixed text and tool_calls"() {
        given:
        def openAi = mapper.writeValueAsBytes([
                choices: [[
                                   message      : [
                                           role      : "assistant",
                                           content   : "Let me check.",
                                           tool_calls: [
                                                   [id: "call_1", type: "function", function: [name: "get_weather", arguments: '{}']]
                                           ]
                                   ],
                                   finish_reason: "tool_calls"
                           ]],
                usage  : [prompt_tokens: 5, completion_tokens: 10]
        ])

        when:
        def result = mapper.readTree(service.translateResponse(openAi, "claude-opus-4-6"))

        then:
        result.path("content").size() == 2
        result.path("content").get(0).path("type").asText() == "text"
        result.path("content").get(0).path("text").asText() == "Let me check."
        result.path("content").get(1).path("type").asText() == "tool_use"
    }

    def "translateRequest converts image content blocks"() {
        given:
        def body = mapper.writeValueAsBytes([
                model     : "claude-opus-4-6",
                max_tokens: 100,
                messages  : [
                        [role: "user", content: [
                                [type: "image", source: [type: "base64", media_type: "image/png", data: "abc123"]]
                        ]]
                ]
        ])

        when:
        def result = mapper.readTree(service.translateRequest(body))

        then:
        def userContent = result.path("messages").get(0).path("content")
        userContent.get(0).path("type").asText() == "image_url"
        userContent.get(0).path("image_url").path("url").asText() == "data:image/png;base64,abc123"
    }

    def "translateRequest handles tool_result with array content"() {
        given:
        def body = mapper.writeValueAsBytes([
                model     : "claude-opus-4-6",
                max_tokens: 100,
                messages  : [
                        [role: "user", content: [
                                [type: "tool_result", tool_use_id: "t1", content: [
                                        [type: "text", text: "line1"],
                                        [type: "text", text: "line2"]
                                ]]
                        ]]
                ]
        ])

        when:
        def result = mapper.readTree(service.translateRequest(body))

        then:
        result.path("messages").get(0).path("content").asText() == "line1\nline2"
    }

    def "translateRequest handles null user content"() {
        given:
        def body = mapper.writeValueAsBytes([
                model     : "claude-opus-4-6",
                max_tokens: 100,
                messages  : [[role: "user", content: null]]
        ])

        when:
        def result = mapper.readTree(service.translateRequest(body))

        then:
        result.path("messages").get(0).path("role").asText() == "user"
    }

    def "translateRequest handles null assistant content"() {
        given:
        def body = mapper.writeValueAsBytes([
                model     : "claude-opus-4-6",
                max_tokens: 100,
                messages  : [[role: "assistant", content: null]]
        ])

        when:
        def result = mapper.readTree(service.translateRequest(body))

        then:
        result.path("messages").get(0).path("role").asText() == "assistant"
    }

    def "translateRequest handles string assistant content"() {
        given:
        def body = mapper.writeValueAsBytes([
                model     : "claude-opus-4-6",
                max_tokens: 100,
                messages  : [[role: "assistant", content: "I said something"]]
        ])

        when:
        def result = mapper.readTree(service.translateRequest(body))

        then:
        result.path("messages").get(0).path("content").asText() == "I said something"
    }

    def "translateRequest maps tool_choice none"() {
        given:
        def body = mapper.writeValueAsBytes([
                model      : "claude-opus-4-6",
                max_tokens : 100,
                messages   : [[role: "user", content: "hi"]],
                tool_choice: [type: "none"]
        ])

        when:
        def result = mapper.readTree(service.translateRequest(body))

        then:
        result.path("tool_choice").asText() == "none"
    }

    def "translateRequest defaults max_tokens to 4096 when missing"() {
        given:
        def body = mapper.writeValueAsBytes([
                model   : "claude-opus-4-6",
                messages: [[role: "user", content: "hi"]]
        ])

        when:
        def result = mapper.readTree(service.translateRequest(body))

        then:
        result.path("max_tokens").asInt() == 4096
    }

    def "translateResponse handles missing usage gracefully"() {
        given:
        def openAi = mapper.writeValueAsBytes([
                choices: [[message: [role: "assistant", content: "ok"], finish_reason: "stop"]]
        ])

        when:
        def result = mapper.readTree(service.translateResponse(openAi, "claude-opus-4-6"))

        then:
        result.path("usage").path("input_tokens").asInt() == 0
        result.path("usage").path("output_tokens").asInt() == 0
    }

    def "translateResponse uses model from response when requestModel is null"() {
        given:
        def openAi = mapper.writeValueAsBytes([
                model  : "claude-sonnet-4.6",
                choices: [[message: [role: "assistant", content: "ok"], finish_reason: "stop"]],
                usage  : [prompt_tokens: 1, completion_tokens: 1]
        ])

        when:
        def result = mapper.readTree(service.translateResponse(openAi, null))

        then:
        result.path("model").asText() == "claude-sonnet-4.6"
    }

    def "mapFinishReason converts correctly"() {
        expect:
        service.mapFinishReason("stop") == "end_turn"
        service.mapFinishReason("tool_calls") == "tool_use"
        service.mapFinishReason("length") == "max_tokens"
        service.mapFinishReason(null) == "end_turn"
        service.mapFinishReason("unknown") == "end_turn"
    }

    // ── Streaming translation ────────────────────────────────────────

    def "StreamTranslator emits message_start on first chunk"() {
        given:
        def translator = service.newStreamTranslator("claude-opus-4-6")

        when:
        def events = translator.processChunk('{"choices":[{"delta":{"role":"assistant"},"finish_reason":null}]}')

        then:
        events.contains("event: message_start")
        events.contains('"type":"message_start"')
        events.contains('"model":"claude-opus-4-6"')
    }

    def "StreamTranslator emits text deltas"() {
        given:
        def translator = service.newStreamTranslator("claude-opus-4-6")
        translator.processChunk('{"choices":[{"delta":{"role":"assistant"},"finish_reason":null}]}')

        when:
        def events = translator.processChunk('{"choices":[{"delta":{"content":"Hello"},"finish_reason":null}]}')

        then:
        events.contains("event: content_block_start")
        events.contains('"type":"text"')
        events.contains("event: content_block_delta")
        events.contains('"text":"Hello"')
    }

    def "StreamTranslator emits tool_use blocks"() {
        given:
        def translator = service.newStreamTranslator("claude-opus-4-6")
        translator.processChunk('{"choices":[{"delta":{"role":"assistant"},"finish_reason":null}]}')

        when:
        def events = translator.processChunk('{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"get_weather","arguments":""}}]},"finish_reason":null}]}')

        then:
        events.contains("event: content_block_start")
        events.contains('"type":"tool_use"')
        events.contains('"id":"call_1"')
        events.contains('"name":"get_weather"')
    }

    def "StreamTranslator emits input_json_delta for tool arguments"() {
        given:
        def translator = service.newStreamTranslator("claude-opus-4-6")
        translator.processChunk('{"choices":[{"delta":{"role":"assistant"},"finish_reason":null}]}')
        translator.processChunk('{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"fn","arguments":""}}]},"finish_reason":null}]}')

        when:
        def events = translator.processChunk('{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\\"loc\\""}}]},"finish_reason":null}]}')

        then:
        events.contains("event: content_block_delta")
        events.contains('"type":"input_json_delta"')
        events.contains("partial_json")
    }

    def "StreamTranslator emits stop events on finish"() {
        given:
        def translator = service.newStreamTranslator("claude-opus-4-6")
        translator.processChunk('{"choices":[{"delta":{"role":"assistant"},"finish_reason":null}]}')
        translator.processChunk('{"choices":[{"delta":{"content":"Hi"},"finish_reason":null}]}')

        when:
        def events = translator.processChunk('{"choices":[{"delta":{},"finish_reason":"stop"}]}')

        then:
        events.contains("event: content_block_stop")
        events.contains("event: message_delta")
        events.contains('"stop_reason":"end_turn"')
        events.contains("event: message_stop")
    }

    def "StreamTranslator handles DONE signal"() {
        given:
        def translator = service.newStreamTranslator("claude-opus-4-6")

        when:
        def events = translator.processChunk("[DONE]")

        then:
        events.contains("event: message_stop")
    }

    def "StreamTranslator finish closes open blocks"() {
        given:
        def translator = service.newStreamTranslator("claude-opus-4-6")
        translator.processChunk('{"choices":[{"delta":{"role":"assistant"},"finish_reason":null}]}')
        translator.processChunk('{"choices":[{"delta":{"content":"Hi"},"finish_reason":null}]}')

        when:
        def events = translator.finish()

        then:
        events.contains("event: content_block_stop")
        events.contains("event: message_delta")
        events.contains("event: message_stop")
    }
}

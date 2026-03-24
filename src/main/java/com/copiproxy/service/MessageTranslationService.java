package com.copiproxy.service;

import com.copiproxy.config.CopiProxyProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.UUID;

@Service
public class MessageTranslationService {

    private static final Logger log = LoggerFactory.getLogger(MessageTranslationService.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final CopiProxyProperties properties;

    public MessageTranslationService(CopiProxyProperties properties) {
        this.properties = properties;
    }

    // ── Request: Anthropic → OpenAI ──────────────────────────────────────

    public byte[] translateRequest(byte[] anthropicBody) throws IOException {
        ObjectNode src = (ObjectNode) mapper.readTree(anthropicBody);
        ObjectNode dst = mapper.createObjectNode();

        String model = textOrDefault(src, "model", properties.defaultModel());
        dst.put("model", model);
        dst.put("max_tokens", src.has("max_tokens") ? src.get("max_tokens").asInt() : 4096);

        if (src.has("temperature")) dst.put("temperature", src.get("temperature").doubleValue());
        if (src.has("top_p")) dst.put("top_p", src.get("top_p").doubleValue());
        if (src.has("stream")) dst.put("stream", src.get("stream").asBoolean());

        if (src.has("stop_sequences") && src.get("stop_sequences").isArray()) {
            dst.set("stop", src.get("stop_sequences"));
        }

        ArrayNode openAiMessages = mapper.createArrayNode();

        if (src.has("system") && !src.get("system").isNull()) {
            openAiMessages.add(mapper.createObjectNode()
                    .put("role", "system")
                    .put("content", extractSystemText(src.get("system"))));
        }

        if (src.has("messages") && src.get("messages").isArray()) {
            for (JsonNode msg : src.get("messages")) {
                translateMessage(msg, openAiMessages);
            }
        }

        dst.set("messages", openAiMessages);

        if (src.has("tools") && src.get("tools").isArray()) {
            dst.set("tools", translateToolDefinitions(src.get("tools")));
        }

        if (src.has("tool_choice")) {
            dst.set("tool_choice", translateToolChoice(src.get("tool_choice")));
        }

        return mapper.writeValueAsBytes(dst);
    }

    // ── Response: OpenAI → Anthropic (non-streaming) ─────────────────────

    public byte[] translateResponse(byte[] openAiBody, String requestModel) throws IOException {
        JsonNode src = mapper.readTree(openAiBody);
        ObjectNode dst = mapper.createObjectNode();

        dst.put("id", "msg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        dst.put("type", "message");
        dst.put("role", "assistant");
        dst.put("model", requestModel != null ? requestModel : src.path("model").asText("unknown"));

        JsonNode choice = src.path("choices").path(0);
        JsonNode message = choice.path("message");

        ArrayNode content = mapper.createArrayNode();

        String textContent = message.path("content").asText(null);
        if (textContent != null && !textContent.isEmpty()) {
            content.add(mapper.createObjectNode().put("type", "text").put("text", textContent));
        }

        if (message.has("tool_calls") && message.get("tool_calls").isArray()) {
            for (JsonNode tc : message.get("tool_calls")) {
                ObjectNode toolUse = mapper.createObjectNode();
                toolUse.put("type", "tool_use");
                toolUse.put("id", tc.path("id").asText());
                toolUse.put("name", tc.path("function").path("name").asText());
                try {
                    toolUse.set("input", mapper.readTree(tc.path("function").path("arguments").asText("{}")));
                } catch (IOException e) {
                    toolUse.set("input", mapper.createObjectNode());
                }
                content.add(toolUse);
            }
        }

        dst.set("content", content);
        dst.put("stop_reason", mapFinishReason(choice.path("finish_reason").asText(null)));
        dst.putNull("stop_sequence");

        ObjectNode usage = mapper.createObjectNode();
        JsonNode srcUsage = src.path("usage");
        usage.put("input_tokens", srcUsage.path("prompt_tokens").asInt(0));
        usage.put("output_tokens", srcUsage.path("completion_tokens").asInt(0));
        dst.set("usage", usage);

        return mapper.writeValueAsBytes(dst);
    }

    // ── Streaming: create a new translator instance per request ───────────

    public StreamTranslator newStreamTranslator(String model) {
        return new StreamTranslator(model);
    }

    // ── StreamTranslator: stateful OpenAI SSE → Anthropic SSE ────────────

    public class StreamTranslator {
        private final String model;
        private boolean started = false;
        private int blockIndex = -1;
        private boolean blockOpen = false;
        private String currentBlockType = null;
        private int outputTokens = 0;
        private int inputTokens = 0;

        StreamTranslator(String model) {
            this.model = model;
        }

        /**
         * Process one OpenAI SSE data line (without the "data: " prefix).
         * Returns zero or more Anthropic SSE events as a single string.
         */
        public String processChunk(String data) {
            if ("[DONE]".equals(data.trim())) {
                return finish();
            }

            try {
                JsonNode chunk = mapper.readTree(data);
                JsonNode choice = chunk.path("choices").path(0);
                JsonNode delta = choice.path("delta");
                String finishReason = choice.path("finish_reason").asText(null);

                if (chunk.has("usage")) {
                    JsonNode u = chunk.get("usage");
                    inputTokens = u.path("prompt_tokens").asInt(inputTokens);
                    outputTokens = u.path("completion_tokens").asInt(outputTokens);
                }

                StringBuilder sb = new StringBuilder();

                if (!started) {
                    sb.append(emitMessageStart());
                    started = true;
                }

                if (delta.has("content") && !delta.get("content").isNull()) {
                    String text = delta.get("content").asText("");
                    if (!text.isEmpty()) {
                        if (!"text".equals(currentBlockType)) {
                            if (blockOpen) sb.append(emitContentBlockStop());
                            blockIndex++;
                            sb.append(emitContentBlockStart("text", blockIndex, null, null));
                            currentBlockType = "text";
                            blockOpen = true;
                        }
                        sb.append(emitTextDelta(blockIndex, text));
                    }
                }

                if (delta.has("tool_calls") && delta.get("tool_calls").isArray()) {
                    for (JsonNode tc : delta.get("tool_calls")) {
                        int tcIndex = tc.path("index").asInt(0);
                        if (tc.has("id") && tc.has("function") && tc.get("function").has("name")) {
                            if (blockOpen) sb.append(emitContentBlockStop());
                            blockIndex++;
                            String toolId = tc.path("id").asText();
                            String toolName = tc.path("function").path("name").asText();
                            sb.append(emitContentBlockStart("tool_use", blockIndex, toolId, toolName));
                            currentBlockType = "tool_use";
                            blockOpen = true;
                        }
                        if (tc.has("function") && tc.get("function").has("arguments")) {
                            String args = tc.path("function").path("arguments").asText("");
                            if (!args.isEmpty()) {
                                sb.append(emitInputJsonDelta(blockIndex, args));
                            }
                        }
                    }
                }

                if (finishReason != null && !"null".equals(finishReason)) {
                    if (blockOpen) {
                        sb.append(emitContentBlockStop());
                        blockOpen = false;
                    }
                    sb.append(emitMessageDelta(mapFinishReason(finishReason)));
                    sb.append(emitMessageStop());
                }

                return sb.toString();
            } catch (IOException e) {
                log.warn("Failed to parse OpenAI stream chunk: {}", data, e);
                return "";
            }
        }

        public String finish() {
            StringBuilder sb = new StringBuilder();
            if (!started) {
                sb.append(emitMessageStart());
                started = true;
            }
            if (blockOpen) {
                sb.append(emitContentBlockStop());
                blockOpen = false;
            }
            if (!sb.toString().contains("message_stop")) {
                sb.append(emitMessageDelta("end_turn"));
                sb.append(emitMessageStop());
            }
            return sb.toString();
        }

        private String emitMessageStart() {
            ObjectNode msg = mapper.createObjectNode();
            msg.put("id", "msg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
            msg.put("type", "message");
            msg.put("role", "assistant");
            msg.set("content", mapper.createArrayNode());
            msg.put("model", model);
            msg.putNull("stop_reason");
            msg.putNull("stop_sequence");
            ObjectNode usage = mapper.createObjectNode();
            usage.put("input_tokens", inputTokens);
            usage.put("output_tokens", 0);
            msg.set("usage", usage);

            ObjectNode event = mapper.createObjectNode();
            event.put("type", "message_start");
            event.set("message", msg);
            return sseEvent("message_start", event);
        }

        private String emitContentBlockStart(String type, int index, String toolId, String toolName) {
            ObjectNode event = mapper.createObjectNode();
            event.put("type", "content_block_start");
            event.put("index", index);

            ObjectNode block = mapper.createObjectNode();
            block.put("type", type);
            if ("text".equals(type)) {
                block.put("text", "");
            } else if ("tool_use".equals(type)) {
                block.put("id", toolId);
                block.put("name", toolName);
                block.set("input", mapper.createObjectNode());
            }
            event.set("content_block", block);
            return sseEvent("content_block_start", event);
        }

        private String emitTextDelta(int index, String text) {
            ObjectNode event = mapper.createObjectNode();
            event.put("type", "content_block_delta");
            event.put("index", index);
            ObjectNode delta = mapper.createObjectNode();
            delta.put("type", "text_delta");
            delta.put("text", text);
            event.set("delta", delta);
            return sseEvent("content_block_delta", event);
        }

        private String emitInputJsonDelta(int index, String partialJson) {
            ObjectNode event = mapper.createObjectNode();
            event.put("type", "content_block_delta");
            event.put("index", index);
            ObjectNode delta = mapper.createObjectNode();
            delta.put("type", "input_json_delta");
            delta.put("partial_json", partialJson);
            event.set("delta", delta);
            return sseEvent("content_block_delta", event);
        }

        private String emitContentBlockStop() {
            ObjectNode event = mapper.createObjectNode();
            event.put("type", "content_block_stop");
            event.put("index", blockIndex);
            return sseEvent("content_block_stop", event);
        }

        private String emitMessageDelta(String stopReason) {
            ObjectNode event = mapper.createObjectNode();
            event.put("type", "message_delta");
            ObjectNode delta = mapper.createObjectNode();
            delta.put("stop_reason", stopReason);
            delta.putNull("stop_sequence");
            event.set("delta", delta);
            ObjectNode usage = mapper.createObjectNode();
            usage.put("output_tokens", outputTokens);
            event.set("usage", usage);
            return sseEvent("message_delta", event);
        }

        private String emitMessageStop() {
            ObjectNode event = mapper.createObjectNode();
            event.put("type", "message_stop");
            return sseEvent("message_stop", event);
        }

        private String sseEvent(String eventType, ObjectNode data) {
            try {
                return "event: " + eventType + "\ndata: " + mapper.writeValueAsString(data) + "\n\n";
            } catch (IOException e) {
                return "";
            }
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────

    private void translateMessage(JsonNode msg, ArrayNode openAiMessages) {
        String role = msg.path("role").asText("");
        JsonNode content = msg.get("content");

        if ("assistant".equals(role)) {
            translateAssistantMessage(content, openAiMessages);
        } else if ("user".equals(role)) {
            translateUserMessage(content, openAiMessages);
        }
    }

    private void translateAssistantMessage(JsonNode content, ArrayNode openAiMessages) {
        ObjectNode oaiMsg = mapper.createObjectNode();
        oaiMsg.put("role", "assistant");

        if (content == null || content.isNull()) {
            oaiMsg.putNull("content");
            openAiMessages.add(oaiMsg);
            return;
        }

        if (content.isTextual()) {
            oaiMsg.put("content", content.asText());
            openAiMessages.add(oaiMsg);
            return;
        }

        if (content.isArray()) {
            StringBuilder textParts = new StringBuilder();
            ArrayNode toolCalls = mapper.createArrayNode();

            for (JsonNode block : content) {
                String type = block.path("type").asText("");
                if ("text".equals(type)) {
                    if (!textParts.isEmpty()) textParts.append("\n");
                    textParts.append(block.path("text").asText(""));
                } else if ("tool_use".equals(type)) {
                    ObjectNode tc = mapper.createObjectNode();
                    tc.put("id", block.path("id").asText());
                    tc.put("type", "function");
                    ObjectNode fn = mapper.createObjectNode();
                    fn.put("name", block.path("name").asText());
                    try {
                        fn.put("arguments", mapper.writeValueAsString(block.get("input")));
                    } catch (IOException e) {
                        fn.put("arguments", "{}");
                    }
                    tc.set("function", fn);
                    toolCalls.add(tc);
                }
            }

            if (!textParts.isEmpty()) {
                oaiMsg.put("content", textParts.toString());
            } else {
                oaiMsg.putNull("content");
            }

            if (!toolCalls.isEmpty()) {
                oaiMsg.set("tool_calls", toolCalls);
            }

            openAiMessages.add(oaiMsg);
        }
    }

    private void translateUserMessage(JsonNode content, ArrayNode openAiMessages) {
        if (content == null || content.isNull()) {
            openAiMessages.add(mapper.createObjectNode().put("role", "user").put("content", ""));
            return;
        }

        if (content.isTextual()) {
            openAiMessages.add(mapper.createObjectNode().put("role", "user").put("content", content.asText()));
            return;
        }

        if (content.isArray()) {
            ArrayNode userParts = mapper.createArrayNode();

            for (JsonNode block : content) {
                String type = block.path("type").asText("");

                if ("tool_result".equals(type)) {
                    ObjectNode toolMsg = mapper.createObjectNode();
                    toolMsg.put("role", "tool");
                    toolMsg.put("tool_call_id", block.path("tool_use_id").asText());
                    JsonNode resultContent = block.get("content");
                    if (resultContent != null && resultContent.isTextual()) {
                        toolMsg.put("content", resultContent.asText());
                    } else if (resultContent != null && resultContent.isArray()) {
                        StringBuilder sb = new StringBuilder();
                        for (JsonNode part : resultContent) {
                            if ("text".equals(part.path("type").asText(""))) {
                                if (!sb.isEmpty()) sb.append("\n");
                                sb.append(part.path("text").asText(""));
                            }
                        }
                        toolMsg.put("content", sb.toString());
                    } else {
                        toolMsg.put("content", "");
                    }
                    openAiMessages.add(toolMsg);
                } else if ("text".equals(type)) {
                    userParts.add(mapper.createObjectNode().put("type", "text").put("text", block.path("text").asText("")));
                } else if ("image".equals(type)) {
                    JsonNode source = block.get("source");
                    if (source != null && "base64".equals(source.path("type").asText(""))) {
                        String dataUrl = "data:" + source.path("media_type").asText("image/png") + ";base64," + source.path("data").asText("");
                        ObjectNode imageUrl = mapper.createObjectNode();
                        imageUrl.put("type", "image_url");
                        imageUrl.set("image_url", mapper.createObjectNode().put("url", dataUrl));
                        userParts.add(imageUrl);
                    }
                }
            }

            if (!userParts.isEmpty()) {
                if (userParts.size() == 1 && "text".equals(userParts.get(0).path("type").asText(""))) {
                    openAiMessages.add(mapper.createObjectNode()
                            .put("role", "user")
                            .put("content", userParts.get(0).path("text").asText("")));
                } else {
                    openAiMessages.add(mapper.createObjectNode().put("role", "user").set("content", userParts));
                }
            }
        }
    }

    private ArrayNode translateToolDefinitions(JsonNode anthropicTools) {
        ArrayNode oaiTools = mapper.createArrayNode();
        for (JsonNode tool : anthropicTools) {
            ObjectNode oaiTool = mapper.createObjectNode();
            oaiTool.put("type", "function");
            ObjectNode fn = mapper.createObjectNode();
            fn.put("name", tool.path("name").asText());
            if (tool.has("description")) fn.put("description", tool.path("description").asText());
            if (tool.has("input_schema")) fn.set("parameters", tool.get("input_schema"));
            oaiTool.set("function", fn);
            oaiTools.add(oaiTool);
        }
        return oaiTools;
    }

    private JsonNode translateToolChoice(JsonNode anthropicChoice) {
        if (anthropicChoice.isTextual()) {
            String val = anthropicChoice.asText();
            return switch (val) {
                case "any" -> mapper.getNodeFactory().textNode("required");
                case "none" -> mapper.getNodeFactory().textNode("none");
                default -> mapper.getNodeFactory().textNode(val);
            };
        }
        if (anthropicChoice.isObject()) {
            String type = anthropicChoice.path("type").asText("");
            return switch (type) {
                case "auto" -> mapper.getNodeFactory().textNode("auto");
                case "any" -> mapper.getNodeFactory().textNode("required");
                case "none" -> mapper.getNodeFactory().textNode("none");
                case "tool" -> {
                    ObjectNode obj = mapper.createObjectNode();
                    obj.put("type", "function");
                    obj.set("function", mapper.createObjectNode().put("name", anthropicChoice.path("name").asText()));
                    yield obj;
                }
                default -> mapper.getNodeFactory().textNode("auto");
            };
        }
        return mapper.getNodeFactory().textNode("auto");
    }

    String mapFinishReason(String openAiReason) {
        if (openAiReason == null) return "end_turn";
        return switch (openAiReason) {
            case "stop" -> "end_turn";
            case "tool_calls" -> "tool_use";
            case "length" -> "max_tokens";
            default -> "end_turn";
        };
    }

    private String extractSystemText(JsonNode system) {
        if (system.isTextual()) return system.asText();
        if (system.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode block : system) {
                if ("text".equals(block.path("type").asText(""))) {
                    if (!sb.isEmpty()) sb.append("\n");
                    sb.append(block.path("text").asText(""));
                }
            }
            return sb.toString();
        }
        return system.asText("");
    }

    private String textOrDefault(ObjectNode node, String field, String defaultValue) {
        if (node.has(field) && !node.get(field).isNull() && !node.get(field).asText("").isBlank()) {
            return node.get(field).asText();
        }
        return defaultValue;
    }
}

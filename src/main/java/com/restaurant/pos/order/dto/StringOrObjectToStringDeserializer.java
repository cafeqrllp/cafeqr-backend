package com.restaurant.pos.order.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;

/**
 * Robust Jackson deserializer that safely handles both String values and Object values
 * (e.g., { "id": "...", "name": "Beverages" } or { "categoryName": "Beverages" }).
 * Prevents 400 Bad Request deserialization errors when frontend or 3rd-party clients send an object.
 */
public class StringOrObjectToStringDeserializer extends JsonDeserializer<String> {

    @Override
    public String deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonToken token = p.currentToken();
        if (token == JsonToken.VALUE_STRING) {
            return p.getText();
        }
        if (token == JsonToken.VALUE_NULL) {
            return null;
        }
        if (token == JsonToken.START_OBJECT) {
            JsonNode node = p.getCodec().readTree(p);
            if (node == null || node.isNull()) {
                return null;
            }
            if (node.hasNonNull("name")) {
                return node.get("name").asText();
            }
            if (node.hasNonNull("categoryName")) {
                return node.get("categoryName").asText();
            }
            if (node.hasNonNull("label")) {
                return node.get("label").asText();
            }
            if (node.hasNonNull("title")) {
                return node.get("title").asText();
            }
            if (node.hasNonNull("displayName")) {
                return node.get("displayName").asText();
            }
            return null;
        }
        return p.getValueAsString();
    }
}
